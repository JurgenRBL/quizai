package com.quizai.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.*
import android.media.ExifInterface
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Non-focusable overlay: opening it never transfers the held pointer to an Activity. */
internal class FloatingCamera(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onImage: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onCancel: () -> Unit
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("QuizAIPhoto").apply { start() }
    private val io = Handler(worker.looper)
    private val settings = context.getSharedPreferences("camera_window", Context.MODE_PRIVATE)
    private lateinit var root: LinearLayout
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var attached = false
    private var closed = false
    private var opening = false
    private var ready = false
    private var capturing = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var previewSize = Size(1280, 960)
    private var sensorDegrees = 90
    private var focusMode = CaptureRequest.CONTROL_AF_MODE_OFF
    private var minZoom = 1f
    private var captureRotation = 0
    private val timeout = Runnable { fail(if (capturing) "No se pudo capturar. Repite la foto." else "La cámara no está lista. Vuelve a mantener presionado.") }

    fun show() {
        val metrics = context.resources.displayMetrics
        params = WindowManager.LayoutParams(
            settings.getInt("width", dp(180)).coerceIn(dp(140), metrics.widthPixels - dp(24)),
            settings.getInt("height", dp(256)).coerceIn(dp(190), metrics.heightPixels - dp(100)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = settings.getInt("x", (metrics.widthPixels - width) / 2).coerceIn(0, metrics.widthPixels - width)
            y = settings.getInt("y", dp(65)).coerceIn(0, (metrics.heightPixels - height - dp(60)).coerceAtLeast(0))
        }
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF024272B.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x667C838C)
            }
            clipToOutline = true
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        preview = TextureView(context)
        val viewfinder = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1))
        }
        val rail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun control(symbol: String, description: String) = TextView(context).apply {
            text = symbol; contentDescription = description; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
        }
        rail.addView(control("×", "Cancelar captura y cerrar cámara").apply { setOnClickListener { onCancel() } })
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        rail.addView(control("✥", "Mover cámara").apply {
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX + event.rawX - downX).toInt().coerceIn(0, metrics.widthPixels - params.width)
                        params.y = (startY + event.rawY - downY).toInt().coerceIn(0, (metrics.heightPixels - params.height - dp(60)).coerceAtLeast(0))
                        update()
                    }
                    MotionEvent.ACTION_UP -> { saveBounds(); view.performClick() }
                }
                true
            }
        })
        rail.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        var startWidth = 0; var startHeight = 0
        rail.addView(control("↘", "Arrastrar para ajustar tamaño").apply {
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startWidth = params.width; startHeight = params.height }
                    MotionEvent.ACTION_MOVE -> {
                        params.width = (startWidth + event.rawX - downX).toInt().coerceIn(dp(140), metrics.widthPixels - dp(24))
                        params.height = (startHeight + event.rawY - downY).toInt().coerceIn(maxOf(dp(190), params.width), metrics.heightPixels - dp(100))
                        params.x = params.x.coerceIn(0, metrics.widthPixels - params.width)
                        params.y = params.y.coerceIn(0, (metrics.heightPixels - params.height - dp(60)).coerceAtLeast(0))
                        update()
                    }
                    MotionEvent.ACTION_UP -> { saveBounds(); view.performClick() }
                }
                true
            }
        })
        root.addView(LinearLayout(context).apply {
            addView(viewfinder, LinearLayout.LayoutParams(0, -1, 1f))
            addView(rail, LinearLayout.LayoutParams(dp(36), -1))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(context).apply {
            text = "Preparando cámara…"; textSize = 10f; gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE); setPadding(dp(5), 0, dp(3), 0)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(28)))
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transform()
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (!closed && !capturing) onCancel()
                return true
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
        try {
            windows.addView(root, params); attached = true
            main.postDelayed(timeout, 8000)
        } catch (_: Exception) { fail("No se pudo abrir la ventana de cámara.") }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun openCamera() {
        if (closed || opening || camera != null || !preview.isAvailable) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("Activa Cam y concede el permiso de cámara."); return
        }
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.filter {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }.maxByOrNull {
                val info = manager.getCameraCharacteristics(it)
                val sensor = info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focal = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 1f
                (sensor?.width ?: 0f) / focal
            } ?: error("Sin cámara trasera")
            val info = manager.getCameraCharacteristics(id)
            sensorDegrees = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            minZoom = if (Build.VERSION.SDK_INT >= 30) info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f else 1f
            focusMode = if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in
                (info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf())) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF
            val sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("Sin formatos")
            val jpegSize = sizes.getOutputSizes(ImageFormat.JPEG).filter { it.width <= 2560 }
                .maxByOrNull { it.width.toLong() * it.height } ?: sizes.getOutputSizes(ImageFormat.JPEG).last()
            val ratio = jpegSize.width.toFloat() / jpegSize.height
            previewSize = sizes.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1920 }
                .minByOrNull { kotlin.math.abs(it.width.toFloat() / it.height - ratio) * 10 + kotlin.math.abs(it.width - 1280) / 10000f }
                ?: sizes.getOutputSizes(SurfaceTexture::class.java).first()
            reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ source ->
                    try {
                        val bytes = source.acquireLatestImage()?.use { image ->
                            val buffer = image.planes[0].buffer
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        } ?: return@setOnImageAvailableListener
                        if (closed || !capturing) return@setOnImageAvailableListener
                        main.removeCallbacks(timeout)
                        closeHardware()
                        removeWindow()
                        io.post {
                            try {
                                val upright = uprightJpeg(bytes)
                                val directory = File(context.filesDir, "captures").apply { mkdirs() }
                                File(directory, "pending.jpg").apply {
                                    writeBytes(upright)
                                    check(renameTo(File(directory, "latest.jpg")))
                                }
                                main.post { if (!closed) { Log.i(TAG, "photo-ready bytes=${upright.size}"); onImage(upright) } }
                            } catch (_: Exception) { main.post { if (!closed) fail("No se pudo preparar la foto. Repite la captura.") } }
                        }
                    } catch (_: Exception) { fail("No se pudo recibir la foto. Repite la captura.") }
                }, main)
            }
            opening = true
            Log.i(TAG, "opening camera=$id sensor=$sensorDegrees display=${displayDegrees()} zoom=$minZoom flash=off")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed) { device.close(); return }
                    opening = false; camera = device
                    try {
                        val texture = preview.surfaceTexture ?: return
                        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                        transform()
                        val target = Surface(texture).also { surface = it }
                        device.createCaptureSession(listOf(target, reader!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) {
                                if (closed || camera !== device) { value.close(); return }
                                try {
                                    session = value
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(target); configure(this) }
                                    value.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                            if (!closed && !ready && !capturing) {
                                                ready = true; main.removeCallbacks(timeout)
                                                status.text = "Suelta para capturar y analizar"
                                                Log.i(TAG, "preview-ready")
                                                onReady()
                                            }
                                        }
                                    }, main)
                                } catch (_: Exception) { fail("No se pudo iniciar la vista previa.") }
                            }
                            override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); if (!closed) fail("Vista previa no disponible.") }
                        }, main)
                    } catch (_: Exception) { fail("No se pudo iniciar la cámara.") }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (!closed) fail("Cámara desconectada.") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (!closed) fail("Cámara no disponible. Vuelve a seleccionar Cam.") }
            }, main)
        } catch (_: Exception) { fail("No se pudo abrir la cámara. Vuelve a seleccionar Cam.") }
    }

    /** Hide immediately, but retain the camera surface until the JPEG has been delivered. */
    fun captureAndHide() {
        val device = camera; val current = session; val output = reader
        if (closed || !ready || capturing || device == null || current == null || output == null) {
            fail("La cámara aún no estaba lista. Mantén presionado más tiempo."); return
        }
        capturing = true
        params.alpha = 0f
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        update()
        captureRotation = PreviewGeometry.jpegRotation(sensorDegrees, displayDegrees())
        Log.i(TAG, "release capture rotation=$captureRotation preview-hidden")
        main.postDelayed(timeout, 8000)
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(output.surface); configure(this); set(CaptureRequest.JPEG_ORIENTATION, captureRotation)
            }
            current.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (!closed) fail("No se pudo capturar. Repite la foto.")
                }
            }, main)
        } catch (_: Exception) { fail("No se pudo capturar. Repite la foto.") }
    }

    private fun configure(request: CaptureRequest.Builder) {
        request.set(CaptureRequest.CONTROL_AF_MODE, focusMode)
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        if (Build.VERSION.SDK_INT >= 30) request.set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoom)
    }

    @Suppress("DEPRECATION")
    private fun displayDegrees() = windows.defaultDisplay.rotation * 90

    private fun transform() {
        if (preview.width == 0 || preview.height == 0) return
        val fit = PreviewGeometry.fit(preview.width, preview.height, previewSize.width, previewSize.height, sensorDegrees, displayDegrees())
        val cx = preview.width / 2f; val cy = preview.height / 2f
        preview.setTransform(Matrix().apply { setScale(fit.scaleX, fit.scaleY, cx, cy); postRotate(fit.rotation, cx, cy) })
    }

    private fun uprightJpeg(bytes: ByteArray): ByteArray {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val degrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bytes // Some cameras already rotate the JPEG pixels.
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Foto no legible")
        val upright = Bitmap.createBitmap(original, 0, 0, original.width, original.height, Matrix().apply { postRotate(degrees) }, true)
        return try {
            ByteArrayOutputStream().use { output -> check(upright.compress(Bitmap.CompressFormat.JPEG, 94, output)); output.toByteArray() }
        } finally { if (upright !== original) upright.recycle(); original.recycle() }
    }

    private fun update() { if (attached && !closed) windows.updateViewLayout(root, params) }
    private fun saveBounds() { settings.edit().putInt("width", params.width).putInt("height", params.height).putInt("x", params.x).putInt("y", params.y).apply() }
    private fun fail(message: String) { if (!closed) { Log.w(TAG, message); onError(message) } }
    private fun closeHardware() {
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        surface?.release(); surface = null
    }
    private fun removeWindow() { if (attached) { attached = false; windows.removeView(root) } }
    fun close() {
        if (closed) return
        closed = true; main.removeCallbacks(timeout)
        closeHardware(); removeWindow(); worker.quitSafely()
        Log.i(TAG, "camera-closed")
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    companion object { private const val TAG = "QuizAICamera" }
}
