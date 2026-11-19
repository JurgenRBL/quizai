package com.quizai.mobile

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var questionInput: EditText
    private lateinit var statusText: TextView
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var resultText: TextView
    private var shouldReadClipboard = false
    private lateinit var analyzeButton: Button
    private val analysisListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AnalysisEngine.UPDATED) refreshAnalysisState()
    }

    private fun refreshAnalysisState() {
        val state = AnalysisEngine.state(this)
        val status = state.getString(AnalysisEngine.STATE, "").orEmpty()
        if (status.isEmpty()) return
        val input = state.getString(AnalysisEngine.INPUT, "").orEmpty()
        if (questionInput.text.toString() != input) questionInput.setText(input)
        resultText.text = state.getString(AnalysisEngine.RESULT, "").orEmpty()
        val source = state.getString(AnalysisEngine.SOURCE, "QuizAI").orEmpty()
        statusText.text = when (status) {
            AnalysisEngine.THINKING -> "$source: consultando GPT-5.5..."
            AnalysisEngine.COLLECTED -> "$source: texto recopilado"
            AnalysisEngine.ERROR -> "$source: consulta no completada"
            else -> "$source: analisis recibido"
        }
        analyzeButton.isEnabled = !AnalysisEngine.isBusy
    }

    override fun onStart() {
        super.onStart()
        AnalysisEngine.recover(this)
        AnalysisEngine.state(this).registerOnSharedPreferenceChangeListener(analysisListener)
        refreshAnalysisState()
    }

    override fun onStop() {
        AnalysisEngine.state(this).unregisterOnSharedPreferenceChangeListener(analysisListener)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        shouldReadClipboard = intent?.action == ACTION_READ_CLIPBOARD
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shouldReadClipboard = intent.action == ACTION_READ_CLIPBOARD
    }

    override fun onResume() {
        super.onResume()
        if (shouldReadClipboard) {
            shouldReadClipboard = false
            readClipboardIntoEditor()
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(18))
            setBackgroundColor(Color.rgb(247, 248, 251))
        }

        val title = TextView(this).apply {
            text = "QuizAI"
            textSize = 28f
            setTextColor(Color.rgb(30, 38, 55))
        }
        val subtitle = TextView(this).apply {
            text = "Asistente académico con OpenAI · POCO X3 Pro"
            textSize = 13f
            setTextColor(Color.rgb(95, 105, 122))
            setPadding(0, dp(4), 0, dp(12))
        }

        statusText = TextView(this).apply {
            text = "Burbuja desactivada"
            textSize = 13f
            setTextColor(Color.rgb(70, 85, 105))
            setPadding(0, 0, 0, dp(8))
        }

        val startButton = Button(this).apply {
            text = "Activar burbuja"
            setOnClickListener { enableBubble() }
        }
        val stopButton = Button(this).apply {
            text = "Desactivar burbuja"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BubbleService::class.java))
                statusText.text = "Burbuja desactivada"
            }
        }
        val clipboardButton = Button(this).apply {
            text = "Leer texto copiado"
            setOnClickListener { readClipboardIntoEditor() }
        }

        val serverLabel = sectionLabel("Conexión directa con OpenAI")
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val savedModel = preferences.getString(MODEL_KEY, null)
        val selectedModel = if (savedModel.isNullOrBlank() || !savedModel.startsWith("gpt-5.5")) DEFAULT_MODEL else savedModel
        if (selectedModel != savedModel) preferences.edit().putString(MODEL_KEY, selectedModel).apply()
        modelInput = EditText(this).apply {
            hint = "Modelo de OpenAI"
            textSize = 13f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(selectedModel)
        }
        modelInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
                .putString(MODEL_KEY, modelInput.text.toString().trim()).apply()
        }

        apiKeyInput = EditText(this).apply {
            hint = "Tu clave de API de OpenAI"
            textSize = 13f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(try { ApiKeyStore(this@MainActivity).read() } catch (_: Exception) { "" })
            setSaveEnabled(false)
        }
        val forgetKeyButton = Button(this).apply {
            text = "Borrar clave guardada"
            setOnClickListener {
                ApiKeyStore(this@MainActivity).clear()
                apiKeyInput.setText("")
                statusText.text = "Clave borrada del teléfono."
            }
        }

        val questionLabel = sectionLabel("Pregunta y alternativas")
        questionInput = EditText(this).apply {
            hint = "Copia la pregunta y todas sus opciones. El icono flotante puede analizarla sin abrir esta pantalla."
            gravity = Gravity.TOP or Gravity.START
            minLines = 5
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }

        analyzeButton = Button(this).apply {
            text = "Analizar con GPT-5.5"
            setOnClickListener { analyzeWithOpenAI() }
        }

        resultText = TextView(this).apply {
            text = "Aquí aparecerán la opción sugerida, la explicación y el nivel de confianza."
            textSize = 14f
            setTextColor(Color.rgb(35, 45, 62))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }

        val note = TextView(this).apply {
            text = "Conexión directa por internet, sin servidor local. Tu clave se guarda cifrada en este teléfono. Las consultas usan tu saldo de API. Revisa siempre la explicación: la IA puede equivocarse."
            textSize = 11f
            setTextColor(Color.rgb(105, 112, 126))
            setPadding(0, dp(10), 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(startButton, matchWidth())
        root.addView(stopButton, matchWidth())
        root.addView(clipboardButton, matchWidth())
        root.addView(serverLabel)
        root.addView(modelInput, matchWidth())
        root.addView(apiKeyInput, matchWidth())
        root.addView(forgetKeyButton, matchWidth())
        root.addView(questionLabel)
        root.addView(questionInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(130)
        ))
        root.addView(analyzeButton, matchWidth())
        root.addView(resultText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        root.addView(note)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun analyzeWithOpenAI() {
        val text = questionInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()

        if (text.length < 5) {
            Toast.makeText(this, "Copia primero la pregunta y sus alternativas.", Toast.LENGTH_SHORT).show()
            return
        }
        if (text.length > 10000) {
            Toast.makeText(this, "La pregunta debe tener como máximo 10000 caracteres.", Toast.LENGTH_LONG).show()
            return
        }
        if (model.isBlank() || apiKey.isBlank()) {
            Toast.makeText(this, "Introduce el modelo GPT-5.5 y tu clave de API de OpenAI.", Toast.LENGTH_LONG).show()
            return
        }
        if (!saveConnection()) return
        AnalysisEngine.submit(this, text, model, apiKey, "App")
        refreshAnalysisState()
    }

    private fun readClipboardIntoEditor() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = clipboard.primaryClip
        val copied = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else ""

        if (copied.isBlank()) {
            statusText.text = "No se encontró texto en el portapapeles."
            Toast.makeText(this, "Copia primero la pregunta y sus alternativas.", Toast.LENGTH_SHORT).show()
        } else {
            questionInput.setText(copied)
            statusText.text = "Texto copiado detectado (${copied.length} caracteres)."
        }
    }

    private fun saveConnection(): Boolean {
        val model = modelInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()
        if (model.isBlank() || apiKey.isBlank()) {
            Toast.makeText(this, "Introduce el modelo GPT-5.5 y tu clave de API de OpenAI.", Toast.LENGTH_LONG).show()
            return false
        }
        try {
            ApiKeyStore(this).save(apiKey)
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo guardar la clave de forma segura.", Toast.LENGTH_LONG).show()
            return false
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
            .remove("server_url").remove("access_token")
            .putString(MODEL_KEY, model).apply()
        return true
    }

    private fun enableBubble() {
        if (!saveConnection()) return
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Autoriza «Mostrar sobre otras aplicaciones» y vuelve a QuizAI.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }

        val serviceIntent = Intent(this, BubbleService::class.java)
        if (getSharedPreferences(BubbleService.BUBBLE_PREFERENCES, MODE_PRIVATE)
                .getString(BubbleService.MODE_KEY, "CopyText") == "Cam" &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            serviceIntent.action = BubbleService.ACTION_ENABLE_CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        statusText.text = "Burbuja activada. Vuelve a tu otra app, copia la pregunta y toca el icono de portapapeles. La opción sugerida aparecerá a su lado."
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(30, 38, 55))
        setPadding(0, dp(12), 0, dp(5))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_READ_CLIPBOARD = "com.quizai.mobile.READ_CLIPBOARD"
        const val PREFERENCES_NAME = "MainActivity_preferences"
        const val MODEL_KEY = "openai_model"
        const val DEFAULT_MODEL = "gpt-5.5"
    }
}
