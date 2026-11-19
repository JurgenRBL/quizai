package com.quizai.mobile

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One analysis path and one durable state for the activity and the overlay. */
internal object AnalysisEngine {
    const val STATE = "state"
    const val INPUT = "input"
    const val RESULT = "result"
    const val LABEL = "label"
    const val ANSWER_TEXT = "answer_text"
    const val SOURCE = "source"
    const val UPDATED = "updated"
    const val COLLECTED = "collected"
    const val THINKING = "thinking"
    const val DONE = "done"
    const val ERROR = "error"
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    val isBusy: Boolean get() = running.get()

    fun state(context: Context): SharedPreferences =
        context.getSharedPreferences("quizai_analysis", Context.MODE_PRIVATE)

    fun collected(context: Context, input: String) {
        if (!isBusy) publish(context, COLLECTED, input, "Texto recopilado. Listo para analizar.", "", "Burbuja")
    }

    @Synchronized fun recover(context: Context) {
        val saved = state(context)
        if (!isBusy && saved.getString(STATE, "") == THINKING) {
            publish(context, ERROR, saved.getString(INPUT, "").orEmpty(),
                "La consulta se interrumpió. Toca de nuevo para reintentar.", "", saved.getString(SOURCE, "").orEmpty())
        }
    }

    fun submit(context: Context, input: String, model: String, apiKey: String, source: String): Boolean =
        submitRequest(context, input, model, apiKey, source, null)

    fun submitImage(context: Context, jpeg: ByteArray, model: String, apiKey: String): Boolean =
        submitRequest(context, "Fotografía capturada", model, apiKey, "Cámara", jpeg)

    @Synchronized private fun submitRequest(context: Context, input: String, model: String, apiKey: String, source: String, jpeg: ByteArray?): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val app = context.applicationContext
        publish(app, THINKING, input, "Analizando la pregunta y sus alternativas…", "", source)
        executor.execute {
            try {
                val response = if (jpeg == null) OpenAIClient.analyze(input, model, apiKey)
                    else OpenAIClient.analyzeImage(jpeg, model, apiKey)
                val values = response.optJSONArray("options")
                val options = (0 until (values?.length() ?: 0)).map { values!!.optString(it) }
                val photoQuestion = response.optString("question")
                val resolvedInput = if (jpeg == null) input else (listOf(photoQuestion) + options).joinToString("\n")
                val selected = if (jpeg == null) AnswerSelection.resolve(input, options,
                    response.optString("recommended_option"), response.optBoolean("recognized", false))
                else AnswerSelection.resolvePhoto(photoQuestion, options,
                    response.optString("recommended_option"), response.optBoolean("recognized", false))
                val detail = if (selected != null) {
                    val identifier = if (selected.identifier.all(Char::isDigit)) "OPCIÓN ${selected.identifier}" else "${selected.identifier})"
                    "$identifier\n\n${selected.text}"
                } else {
                    response.optString("uncertainty_reason").ifBlank {
                        if (jpeg == null) "No se pudo identificar una alternativa del texto copiado. Copia el enunciado y todas las opciones."
                        else "No se pudo leer una pregunta completa. Toma otra foto con el enunciado y todas sus opciones."
                    }
                }
                // Listeners must see the completed busy state when they receive the result.
                complete(app, DONE, resolvedInput, detail, selected?.identifier.orEmpty(), source, selected?.text.orEmpty())
            } catch (e: Exception) {
                complete(app, ERROR, input, "No se pudo completar la consulta: ${e.message ?: "error de conexión"}", "", source)
            }
        }
        return true
    }

    @Synchronized private fun complete(context: Context, status: String, input: String, result: String, label: String, source: String, answerText: String = "") {
        running.set(false)
        publish(context, status, input, result, label, source, answerText)
    }

    private fun publish(context: Context, status: String, input: String, result: String, label: String, source: String, answerText: String = "") {
        state(context).edit().putString(STATE, status).putString(INPUT, input)
            .putString(RESULT, result).putString(LABEL, label).putString(SOURCE, source).putString(ANSWER_TEXT, answerText)
            .putLong(UPDATED, System.currentTimeMillis()).apply()
    }
}
