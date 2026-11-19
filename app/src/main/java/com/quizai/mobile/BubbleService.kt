package com.quizai.mobile

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.widget.FrameLayout
import android.widget.Toast
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.WindowInsets
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

class BubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: LinearLayout
    private lateinit var bubbleIcon: TextView
    private lateinit var menuPanel: LinearLayout
    private lateinit var responseButton: TextView
    private lateinit var clipboardButton: TextView
    private var answerIdentifier: String? = null
    private var analyzeAfterClipboard = false
    private var cameraMode = false
    private var cameraArmed = false
    private var cameraWindow: FloatingCamera? = null
    private var captureGesture: HoldCapture? = null
    private var touching = false
    private var longPressHandled = false
    private var currentSymbol = ""
    private var currentDescription = "Consultar respuesta"
    private lateinit var bubbleFace: FrameLayout
    private lateinit var modeBadge: TextView
    private val longPress = Runnable {
        if (touching && !dragging) {
            longPressHandled = true
            cancelPendingTap()
            if (cameraMode) beginCameraHold()
        }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            cancelCameraHold()
            touching = false
            handler.removeCallbacks(longPress)
        }
    }
    private var singleTapPending = false
    private var secondTapInProgress = false
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val singleTap = Runnable {
        singleTapPending = false
        if (!destroyed) {
            setMenuOpen(false)
            partiallyHidden = false
            animateToX(visibleBubbleLeft())
            if (!cameraMode) readClipboard(analyzeAfterRead = true)
            else scheduleAutoHide()
        }
    }
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private val question = QuestionCollector()
    private val clipboardChunks = mutableListOf<String>()
    private var menuIconY = 0
    private var menuOpen = false
    private var dockedToLeft = false
    private var partiallyHidden = false
        set(value) {
            field = value
            if (::bubbleIcon.isInitialized) {
                bubbleIcon.setTextColor(if (value) Color.TRANSPARENT else Color.WHITE)
            }
        }
    private var awaitingClipboard = false
    private var busy = false
    private var destroyed = false
    private var bubbleAnimator: ValueAnimator? = null
    private var answerPopup: TextView? = null
    private var lastPopupUpdate = 0L
    private val dismissAnswerPopup = Runnable { removeAnswerPopup() }
    private val analysisListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AnalysisEngine.UPDATED && ::bubbleIcon.isInitialized && !destroyed) renderAnalysisState(showBriefAnswer = true)
    }

    private fun renderAnalysisState(showBriefAnswer: Boolean = false) {
        val state = AnalysisEngine.state(this)
        busy = AnalysisEngine.isBusy
        answerIdentifier = state.getString(AnalysisEngine.LABEL, "").orEmpty().ifBlank { null }
        when (state.getString(AnalysisEngine.STATE, "")) {
            AnalysisEngine.THINKING -> showResponseSymbol("...", "La IA esta pensando")
            AnalysisEngine.DONE -> {
                val label = answerIdentifier
                if (label != null) {
                    showResponseSymbol(if (label.all(Char::isDigit)) label else "$label)", "Respuesta: $label")
                    val update = state.getLong(AnalysisEngine.UPDATED, 0L)
                    val text = state.getString(AnalysisEngine.ANSWER_TEXT, "").orEmpty().trim()
                    if (showBriefAnswer && text.isNotEmpty() && update != lastPopupUpdate) {
                        lastPopupUpdate = update
                        showAnswerPopup("$label) $text")
                    }
                    clearQuestion()
                } else {
                    val message = state.getString(AnalysisEngine.RESULT, "Repite la foto con la pregunta completa").orEmpty()
                    val photo = state.getString(AnalysisEngine.SOURCE, "") == "Cámara"
                    showResponseSymbol(if (photo) "!" else "\u2013", message)
                    if (photo) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
            AnalysisEngine.ERROR -> showResponseSymbol("!", state.getString(AnalysisEngine.RESULT, "Error").orEmpty())
        }
        if (state.getString(AnalysisEngine.SOURCE, "") == "Cámara" &&
            state.getString(AnalysisEngine.STATE, "") in listOf(AnalysisEngine.DONE, AnalysisEngine.ERROR)) {
            partiallyHidden = false
            if (!menuOpen) animateToX(visibleBubbleLeft())
        }
        scheduleAutoHide()
    }

    private val autoHide = Runnable {
        if (!destroyed && !busy && !awaitingClipboard && !touching && cameraWindow == null) {
            setMenuOpen(false)
            hidePartially()
        }
    }
    private val clipboardTimeout = Runnable {
        if (awaitingClipboard) finishClipboardRead()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cameraMode = getSharedPreferences(BUBBLE_PREFERENCES, MODE_PRIVATE).getString(MODE_KEY, "CopyText") == "Cam"
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        restoreQuestion()
        AnalysisEngine.recover(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "QuizAI activa", NotificationManager.IMPORTANCE_LOW)
        )
        updateForeground(camera = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!::bubbleView.isInitialized) addBubble()
        if (intent?.action == ACTION_ENABLE_CAMERA) {
            val success = armCamera()
            if (success) selectMode(cam = true)
            sendBroadcast(Intent(CameraActivity.ACTION_MODE_READY).setPackage(packageName).putExtra("success", success))
        }
        return START_STICKY
    }

    private fun updateForeground(camera: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            startForeground(NOTIFICATION_ID, buildNotification(), special or if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun armCamera(): Boolean {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            updateForeground(camera = true)
            cameraArmed = true
            true
        } catch (_: Exception) { false }
    }

    private fun selectMode(cam: Boolean) {
        cancelPendingTap()
        handler.removeCallbacks(longPress)
        cancelCameraHold()
        cameraMode = cam
        getSharedPreferences(BUBBLE_PREFERENCES, MODE_PRIVATE).edit().putString(MODE_KEY, if (cam) "Cam" else "CopyText").apply()
        if (!cam) { cameraArmed = false; updateForeground(camera = false) }
        setMenuOpen(false)
        showResponseSymbol("\u2311", if (cam) "Mantén y suelta para analizar una foto" else "Toca para analizar el portapapeles")
        Log.i(TAG, "mode=${if (cam) "Cam" else "CopyText"}")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        scheduleAutoHide()
    }

    private fun beginCameraHold() {
        if (busy || AnalysisEngine.isBusy || awaitingClipboard || cameraWindow != null) return
        if (!cameraArmed) {
            Toast.makeText(this, "Selecciona Cam en las opciones para activar la cámara.", Toast.LENGTH_LONG).show()
            return
        }
        val gesture = HoldCapture()
        captureGesture = gesture
        val overlay = FloatingCamera(this,
            onReady = { if (captureGesture === gesture && touching && gesture.previewReady()) Log.i(TAG, "hold-ready") },
            onImage = { jpeg ->
                if (captureGesture === gesture && gesture.acceptImage()) {
                    cameraWindow?.close(); cameraWindow = null; captureGesture = null
                    try {
                        val preferences = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
                        val model = preferences.getString(MainActivity.MODEL_KEY, MainActivity.DEFAULT_MODEL).orEmpty()
                        if (!AnalysisEngine.submitImage(this, jpeg, model, ApiKeyStore(this).read())) {
                            showResponseSymbol("!", "Ya hay una consulta en curso")
                        } else Log.i(TAG, "photo-submitted")
                    } catch (_: Exception) { showResponseSymbol("!", "Revisa la clave guardada en QuizAI") }
                    scheduleAutoHide()
                }
            },
            onError = { message ->
                if (captureGesture === gesture) {
                    cancelCameraHold()
                    showResponseSymbol("!", message)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    scheduleAutoHide()
                }
            },
            onCancel = { if (captureGesture === gesture) { cancelCameraHold(); scheduleAutoHide() } }
        )
        cameraWindow = overlay
        Log.i(TAG, "hold-open")
        overlay.show()
    }

    private fun releaseCameraHold() {
        val gesture = captureGesture ?: return
        if (!gesture.release()) {
            Log.i(TAG, "hold-cancel-before-ready")
            cancelCameraHold()
            return
        }
        showResponseSymbol("…", "Capturando y analizando fotografía")
        Log.i(TAG, "hold-release-capture")
        cameraWindow?.captureAndHide()
    }

    private fun cancelCameraHold() {
        captureGesture?.cancel()
        captureGesture = null
        cameraWindow?.close()
        cameraWindow = null
    }

    private fun addBubble() {
        bubbleIcon = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = circle(0x386B7078.toInt())
            elevation = dp(1).toFloat()
            clipToOutline = true
            contentDescription = "Un toque: leer y analizar. Doble toque: opciones"
            isClickable = true
        }
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        menuPanel.addView(controlRow("Ocultar", "\u2039") {
            cancelCameraHold()
            setMenuOpen(false)
            hidePartially()
        })
        menuPanel.addView(controlRow("CopyText", "T") { selectMode(cam = false) })
        menuPanel.addView(controlRow("Cam", "C") {
            if (cameraArmed) selectMode(cam = true)
            else {
                setMenuOpen(false)
                startActivity(Intent(this, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        menuPanel.addView(controlRow("Portapapeles", "\u25A1") { readClipboard() }.also {
            clipboardButton = it.getChildAt(1) as TextView
        })
        menuPanel.addView(controlRow("Respuesta", "\u2311") { analyzeCollectedQuestion() }.also {
            responseButton = it.getChildAt(1) as TextView
        })
        modeBadge = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 9f
            setTextColor(Color.WHITE)
            background = circle(0xA06B7078.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bubbleFace = FrameLayout(this).apply {
            clipChildren = false
            addView(bubbleIcon, FrameLayout.LayoutParams(-1, -1))
            addView(modeBadge, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.RIGHT))
        }
        showResponseSymbol("\u2311", "Consultar respuesta")
        bubbleView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && menuOpen) {
                    setMenuOpen(false)
                    scheduleAutoHide()
                }
                false
            }
        }

        layoutParams = WindowManager.LayoutParams(
            dp(BUBBLE_SIZE_DP), dp(BUBBLE_SIZE_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = dp(180).coerceAtMost(maxBubbleY())
        }
        rebuildBubbleChildren()
        layoutParams.x = visibleBubbleLeft()
        bubbleIcon.setOnClickListener {
            if (secondTapInProgress) {
                cancelPendingTap()
                setMenuOpen(!menuOpen)
            } else {
                singleTapPending = true
                lastTapUp = android.os.SystemClock.uptimeMillis()
                lastTapX = downX
                lastTapY = downY
                handler.postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout().toLong())
            }
            scheduleAutoHide()
        }
        bubbleIcon.setOnTouchListener { view, event -> handleBubbleTouch(view, event) }
        bubbleView.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            if (focused && awaitingClipboard) handler.post { finishClipboardRead() }
        }
        try {
            windowManager.addView(bubbleView, layoutParams)
            AnalysisEngine.state(this).registerOnSharedPreferenceChangeListener(analysisListener)
            renderAnalysisState()
            scheduleAutoHide()
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Unable to show the floating QuizAI bubble.", e)
            stopSelf()
        }
    }

    private fun controlRow(label: String, symbol: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            addView(TextView(this@BubbleService).apply {
                text = label.lowercase()
                textSize = 12f
                setTextColor(0xFF36404F.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    setColor(0xE8FAFBFF.toInt())
                    cornerRadius = dp(16).toFloat()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            addView(TextView(this@BubbleService).apply {
                text = symbol
                textSize = 25f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                    circle(0x606B7078.toInt()), circle(Color.WHITE))
                elevation = dp(1).toFloat()
                contentDescription = label
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    handler.removeCallbacks(autoHide)
                    action()
                    scheduleAutoHide()
                }
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            setPadding(0, 0, if (label == "Portapapeles" || label == "Camara") dp(24) else 0, 0)
            layoutParams = LinearLayout.LayoutParams(-1, dp(58))
        }

    private fun showAnswerPopup(answer: String) {
        handler.removeCallbacks(dismissAnswerPopup)
        removeAnswerPopup()
        val popup = TextView(this).apply {
            text = answer
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(0xEF292D33.toInt())
                cornerRadius = dp(16).toFloat()
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            contentDescription = "Respuesta: $answer"
        }
        val params = WindowManager.LayoutParams(
            (screenWidth() - dp(32)).coerceAtLeast(dp(48)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        try {
            windowManager.addView(popup, params)
            answerPopup = popup
            handler.postDelayed(dismissAnswerPopup, 1800L)
        } catch (e: Exception) { Log.w(TAG, "Unable to show the brief answer message.", e) }
    }

    private fun removeAnswerPopup() {
        answerPopup?.let {
            try { windowManager.removeView(it) } catch (_: IllegalArgumentException) { }
        }
        answerPopup = null
    }

    private fun showResponseSymbol(symbol: String, description: String) {
        if (symbol == "\u2311") {
            val icon = getDrawable(R.drawable.ic_response_lock)!!
            icon.setBounds(0, 0, dp(22), dp(22))
            responseButton.text = android.text.SpannableString(" ").apply {
                setSpan(android.text.style.ImageSpan(icon, android.text.style.ImageSpan.ALIGN_BASELINE),
                    0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else responseButton.text = symbol
        responseButton.contentDescription = description
        currentSymbol = if (symbol == "\u2311") "" else symbol
        currentDescription = description
        updateBubbleAppearance()
    }

    private fun updateBubbleAppearance() {
        bubbleIcon.text = currentSymbol
        modeBadge.text = if (cameraMode) "C" else "T"
        val gesture = if (cameraMode) "Mantener 400 ms y soltar: fotografiar y analizar" else "Un toque: leer y analizar"
        bubbleIcon.contentDescription = "${if (cameraMode) "Cam" else "CopyText"}. $currentDescription. $gesture. Doble toque: opciones"
    }

    private fun setMenuOpen(open: Boolean) {
        if (!::bubbleView.isInitialized || menuOpen == open) return
        bubbleAnimator?.cancel()
        bubbleAnimator = null
        val iconX = if (open) {
            partiallyHidden = false
            if (dockedToLeft) dp(EDGE_MARGIN_DP)
            else (screenWidth() - dp(BUBBLE_SIZE_DP + EDGE_MARGIN_DP)).coerceAtLeast(0)
        } else layoutParams.x + if (menuOpen && !dockedToLeft) menuSpan() else 0
        if (open) menuIconY = layoutParams.y
        menuOpen = open
        rebuildBubbleChildren()
        layoutParams.width = if (open) menuSpan() + dp(BUBBLE_SIZE_DP) else dp(BUBBLE_SIZE_DP)
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.x = iconX - if (open && !dockedToLeft) menuSpan() else 0
        if (open) positionMenu() else {
            bubbleView.gravity = Gravity.TOP
            layoutParams.y = menuIconY.coerceIn(0, maxBubbleY())
        }
        layoutParams.flags = if (open) {
            layoutParams.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            layoutParams.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
        updateBubbleLayout()
        menuPanel.animate().cancel()
        if (open) {
            menuPanel.alpha = 0f
            menuPanel.animate().alpha(1f).setDuration(ANIMATION_DURATION_MS).start()
        }
    }

    private fun positionMenu() {
        menuPanel.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight(), View.MeasureSpec.AT_MOST))
        val height = menuPanel.measuredHeight.coerceAtLeast(dp(BUBBLE_SIZE_DP))
        bubbleView.gravity = Gravity.CENTER_VERTICAL
        layoutParams.y = (menuIconY - (height - dp(BUBBLE_SIZE_DP)) / 2)
            .coerceIn(0, maxBubbleY(height))
    }

    private fun rebuildBubbleChildren() {
        bubbleView.removeAllViews()
        menuPanel.visibility = if (menuOpen) View.VISIBLE else View.GONE
        val iconParams = LinearLayout.LayoutParams(dp(BUBBLE_SIZE_DP), dp(BUBBLE_SIZE_DP))
        val menuParams = LinearLayout.LayoutParams(menuWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { if (dockedToLeft) leftMargin = dp(MENU_GAP_DP) else rightMargin = dp(MENU_GAP_DP) }
        if (dockedToLeft) {
            bubbleView.addView(bubbleFace, iconParams)
            if (menuOpen) bubbleView.addView(menuPanel, menuParams)
        } else {
            if (menuOpen) bubbleView.addView(menuPanel, menuParams)
            bubbleView.addView(bubbleFace, iconParams)
        }
    }

    private fun cancelPendingTap() {
        handler.removeCallbacks(singleTap)
        singleTapPending = false
        secondTapInProgress = false
    }

    private fun handleBubbleTouch(view: View, event: MotionEvent): Boolean {
        if (captureGesture?.state == HoldCapture.State.CAPTURING) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val doubleTapSlop = ViewConfiguration.get(this).scaledDoubleTapSlop
                secondTapInProgress = singleTapPending &&
                    event.eventTime - lastTapUp <= ViewConfiguration.getDoubleTapTimeout() &&
                    kotlin.math.abs(event.rawX - lastTapX) <= doubleTapSlop &&
                    kotlin.math.abs(event.rawY - lastTapY) <= doubleTapSlop
                if (secondTapInProgress) handler.removeCallbacks(singleTap)
                touching = true
                longPressHandled = false
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, HOLD_DELAY_MS)
                handler.removeCallbacks(autoHide)
                bubbleAnimator?.cancel()
                bubbleAnimator = null
                touchedWhileHidden = partiallyHidden
                partiallyHidden = false
                downX = event.rawX
                downY = event.rawY
                dragging = false
                dragStartX = layoutParams.x +
                    if (!dockedToLeft && menuOpen) menuSpan() else 0
                dragStartY = if (menuOpen) menuIconY else layoutParams.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.rawX - downX) > touchSlop ||
                    kotlin.math.abs(event.rawY - downY) > touchSlop
                ) dragging = true
                if (!dragging) return true
                handler.removeCallbacks(longPress)
                cancelCameraHold()
                cancelPendingTap()
                bubbleAnimator?.cancel()
                bubbleAnimator = null
                if (menuOpen) setMenuOpen(false)
                partiallyHidden = false
                layoutParams.x = (dragStartX + event.rawX - downX).toInt()
                    .coerceIn(-(dp(BUBBLE_SIZE_DP) - dp(VISIBLE_SIZE_DP)), screenWidth() - dp(VISIBLE_SIZE_DP))
                layoutParams.y = (dragStartY + event.rawY - downY).toInt()
                    .coerceIn(0, maxBubbleY())
                updateBubbleLayout()
                return true
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                handler.removeCallbacks(longPress)
                if (dragging) {
                    cancelCameraHold()
                    snapToNearestEdge()
                } else if (longPressHandled) {
                    releaseCameraHold()
                } else if (event.eventTime - event.downTime < HOLD_DELAY_MS) {
                    view.performClick()
                } else {
                    cancelPendingTap()
                }
                scheduleAutoHide()
                dragging = false
                touchedWhileHidden = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                handler.removeCallbacks(longPress)
                cancelCameraHold()
                cancelPendingTap()
                if (dragging) snapToNearestEdge()
                else if (touchedWhileHidden) animateToX(visibleBubbleLeft())
                dragging = false
                touchedWhileHidden = false
                scheduleAutoHide()
                return true
            }
        }
        return false
    }

    private fun readClipboard(analyzeAfterRead: Boolean = false) {
        if (busy || AnalysisEngine.isBusy || awaitingClipboard) return
        analyzeAfterClipboard = analyzeAfterRead
        if (analyzeAfterRead) showResponseSymbol("...", "Leyendo portapapeles")
        handler.removeCallbacks(autoHide)
        awaitingClipboard = true
        layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        updateBubbleLayout()
        bubbleView.requestFocus()
        if (bubbleView.hasWindowFocus()) handler.post { finishClipboardRead() }
        handler.postDelayed(clipboardTimeout, CLIPBOARD_FOCUS_TIMEOUT_MS)
    }

    private fun finishClipboardRead() {
        if (!awaitingClipboard || destroyed) return
        awaitingClipboard = false
        val shouldAnalyze = analyzeAfterClipboard
        analyzeAfterClipboard = false
        handler.removeCallbacks(clipboardTimeout)
        val text = try {
            if (!bubbleView.hasWindowFocus()) "" else {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
                } else ""
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "MIUI or Android denied clipboard access.", e)
            ""
        }
        layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        updateBubbleLayout()
        if (text.isBlank()) {
            clipboardButton.text = "!"
            clipboardButton.contentDescription = "No se pudo leer el portapapeles; copia el texto y vuelve a intentarlo"
            if (shouldAnalyze) showResponseSymbol("!", "No se pudo leer el portapapeles")
        } else {
            // A quick analysis uses only the current clipboard, without older fragments.
            if (shouldAnalyze) clearQuestion()
            if (question.addClipboardText(text)) {
                if (text !in clipboardChunks) clipboardChunks.add(text)
                saveQuestion()
                AnalysisEngine.collected(this, collectedInput())
                answerIdentifier = null
                showResponseSymbol("\u2311", "Consultar respuesta")
                clipboardButton.text = "\u2713"
                clipboardButton.contentDescription = "Texto recopilado"
                if (shouldAnalyze) analyzeCollectedQuestion()
            } else {
                clipboardButton.text = "!"
                if (shouldAnalyze) showResponseSymbol("!", "No se pudo recopilar el texto")
            }
        }
        scheduleAutoHide()
    }

    private fun collectedInput() = clipboardChunks.distinct().joinToString("\n\n").trim()

    private fun analyzeCollectedQuestion() {
        if (busy || AnalysisEngine.isBusy || awaitingClipboard) return
        val input = collectedInput()
        if (input.length !in 5..10000) {
            showResponseSymbol("!", "Copia la pregunta y sus alternativas antes de consultar")
            return
        }
        handler.removeCallbacks(autoHide)
        val preferences = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
        val model = preferences.getString(MainActivity.MODEL_KEY, MainActivity.DEFAULT_MODEL).orEmpty()
        try {
            AnalysisEngine.submit(this, input, model, ApiKeyStore(this).read(), "Burbuja")
            renderAnalysisState()
        } catch (_: Exception) {
            showResponseSymbol("!", "No se pudo leer la clave guardada; revisa la conexion en QuizAI")
        }
    }

    private fun saveQuestion() {
        val preferences = getSharedPreferences(COLLECTION_PREFERENCES, MODE_PRIVATE)
        val values = org.json.JSONArray()
        clipboardChunks.forEach(values::put)
        preferences.edit().putString(COLLECTION_CLIPS_KEY, values.toString()).apply()
    }

    private fun restoreQuestion() {
        val preferences = getSharedPreferences(COLLECTION_PREFERENCES, MODE_PRIVATE)
        val values = try {
            org.json.JSONArray(preferences.getString(COLLECTION_CLIPS_KEY, "[]"))
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "Discarding unreadable saved question data.", e)
            org.json.JSONArray()
        }
        for (index in 0 until values.length()) {
            val text = values.optString(index)
            if (text.isNotBlank() && question.addClipboardText(text)) clipboardChunks.add(text)
        }
    }

    private fun clearQuestion() {
        question.clear()
        clipboardChunks.clear()
        getSharedPreferences(COLLECTION_PREFERENCES, MODE_PRIVATE).edit().remove(COLLECTION_CLIPS_KEY).apply()
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHide)
        if (!destroyed && !busy && !awaitingClipboard && !touching && cameraWindow == null) handler.postDelayed(autoHide, if (menuOpen || currentSymbol.isNotEmpty()) MENU_IDLE_DELAY_MS else AUTO_HIDE_DELAY_MS)
    }

    private fun visibleBubbleLeft(): Int {
        val width = if (menuOpen) menuSpan() + dp(BUBBLE_SIZE_DP) else dp(BUBBLE_SIZE_DP)
        return if (dockedToLeft) dp(EDGE_MARGIN_DP) else (screenWidth() - width - dp(EDGE_MARGIN_DP)).coerceAtLeast(0)
    }

    private fun hidePartially() {
        if (partiallyHidden || !::bubbleView.isInitialized) return
        setMenuOpen(false)
        partiallyHidden = true
        val target = if (dockedToLeft) {
            -(dp(BUBBLE_SIZE_DP) - dp(VISIBLE_SIZE_DP))
        } else {
            screenWidth() - dp(VISIBLE_SIZE_DP)
        }
        animateToX(target)
    }

    private fun snapToNearestEdge() {
        dockedToLeft = layoutParams.x + dp(BUBBLE_SIZE_DP) / 2 < screenWidth() / 2
        partiallyHidden = false
        animateToX(visibleBubbleLeft())
    }

    private fun animateToX(targetX: Int) {
        bubbleAnimator?.cancel()
        if (layoutParams.x == targetX) return
        bubbleAnimator = ValueAnimator.ofInt(layoutParams.x, targetX).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (!destroyed && ::bubbleView.isInitialized) {
                    layoutParams.x = it.animatedValue as Int
                    updateBubbleLayout()
                }
            }
            start()
        }
    }

    private fun updateBubbleLayout() {
        if (!::bubbleView.isInitialized) return
        try {
            windowManager.updateViewLayout(bubbleView, layoutParams)
        } catch (e: IllegalArgumentException) {
            if (!destroyed) Log.e(TAG, "Failed to update the QuizAI overlay.", e)
        }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), 0x40FFFFFF.toInt())
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QuizAI está activa")
            .setContentText(if (cameraMode) "Cam: mantén y suelta para fotografiar y analizar. Doble toque: opciones."
                else "CopyText: un toque para analizar el texto copiado. Doble toque: opciones.")
            .setSmallIcon(R.drawable.ic_analyze_clipboard)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        removeAnswerPopup()
        cancelCameraHold()
        unregisterReceiver(screenOff)
        awaitingClipboard = false
        handler.removeCallbacksAndMessages(null)
        bubbleAnimator?.cancel()
        bubbleAnimator = null
        if (::menuPanel.isInitialized) menuPanel.animate().cancel()
        AnalysisEngine.state(this).unregisterOnSharedPreferenceChangeListener(analysisListener)
        if (::bubbleView.isInitialized) {
            try {
                windowManager.removeView(bubbleView)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "The QuizAI overlay was already removed.", e)
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun menuWidth() = dp(MENU_WIDTH_DP).coerceAtMost(
        (screenWidth() - dp(BUBBLE_SIZE_DP + MENU_GAP_DP + EDGE_MARGIN_DP * 2)).coerceAtLeast(1))
    private fun menuSpan() = menuWidth() + dp(MENU_GAP_DP)
    private fun availableHeight(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return metrics.bounds.height() - insets.top - insets.bottom
        }
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getSize(size)
        return size.y
    }
    private fun maxBubbleY(height: Int = dp(BUBBLE_SIZE_DP)) =
        (availableHeight() - height).coerceAtLeast(0)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::bubbleView.isInitialized) return
        cancelPendingTap()
        handler.removeCallbacks(longPress)
        touching = false
        cancelCameraHold()
        bubbleAnimator?.cancel()
        val wasHidden = partiallyHidden
        setMenuOpen(false)
        partiallyHidden = false
        layoutParams.y = layoutParams.y.coerceIn(0, maxBubbleY())
        layoutParams.x = visibleBubbleLeft()
        updateBubbleLayout()
        if (wasHidden) hidePartially() else scheduleAutoHide()
    }

    private fun screenWidth(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return metrics.bounds.width() - insets.left - insets.right
        }
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getSize(size)
        return size.x
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var downX = 0f
    private var downY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false
    private var touchedWhileHidden = false

    companion object {
        const val ACTION_ENABLE_CAMERA = "com.quizai.mobile.ENABLE_CAMERA"
        const val BUBBLE_PREFERENCES = "bubble_settings"
        const val MODE_KEY = "mode"
        private const val HOLD_DELAY_MS = 400L
        private const val TAG = "QuizAIBubble"
        private const val BUBBLE_SIZE_DP = 46
        private const val MENU_WIDTH_DP = 148
        private const val MENU_GAP_DP = 8
        private const val VISIBLE_SIZE_DP = 22
        private const val EDGE_MARGIN_DP = 3
        private const val AUTO_HIDE_DELAY_MS = 1200L
        private const val MENU_IDLE_DELAY_MS = 4000L
        private const val ANIMATION_DURATION_MS = 100L
        private const val CLIPBOARD_FOCUS_TIMEOUT_MS = 1500L
        private const val COLLECTION_PREFERENCES = "quiz_collection"
        private const val COLLECTION_CLIPS_KEY = "clipboard_chunks"
        private const val CHANNEL_ID = "quizai_bubble_channel"
        private const val NOTIFICATION_ID = 7101
    }
}
