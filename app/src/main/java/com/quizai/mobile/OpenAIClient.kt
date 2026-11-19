package com.quizai.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object OpenAIClient {
    fun analyze(text: String, model: String, apiKey: String): JSONObject {
        require(text.length in 5..10000) { "Copia una pregunta de entre 5 y 10000 caracteres." }
        return execute(OpenAIProtocol.request(model, text), model, apiKey)
    }

    fun analyzeImage(jpeg: ByteArray, model: String, apiKey: String): JSONObject {
        require(jpeg.size in 4..15_000_000 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) {
            "La foto no es válida. Captura otra imagen."
        }
        return execute(OpenAIProtocol.imageRequest(model, jpeg), model, apiKey)
    }

    private fun execute(request: JSONObject, model: String, apiKey: String): JSONObject {
        require(model.matches(Regex("[A-Za-z0-9._-]+")) && apiKey.isNotBlank()) {
            "Introduce un modelo OpenAI y tu clave de API."
        }
        val connection = URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection
        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 120000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.outputStream.use {
                it.write(request.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "Error HTTP $code"
                } catch (_: Exception) { "Error HTTP $code" }
                throw IllegalStateException(detail.replace(apiKey, "[clave oculta]"))
            }
            return OpenAIProtocol.result(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }
}
