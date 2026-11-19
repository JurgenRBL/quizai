package com.quizai.mobile

internal data class AnswerOption(val label: String?, val text: String)

internal class QuestionCollector {
    var question: String = ""
        private set
    private val collectedOptions = mutableListOf<AnswerOption>()
    val options: List<AnswerOption> get() = collectedOptions.toList()

    fun addClipboardText(text: String): Boolean {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n')
            .lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.isEmpty()) return false

        // Reading the same statement again must not turn it into another alternative.
        val normalizedQuestion = question.replace(Regex("\\s+"), " ").trim()
        if (normalizedQuestion.isNotEmpty() &&
            lines.joinToString(" ") == normalizedQuestion) return true
        if (question.isNotEmpty() && lines.size > 1 && lines.first() == question &&
            lines.drop(1).none(::isLabeledOption)) {
            lines.drop(1).map(::unlabeledOption).forEach(::appendOption)
            return true
        }

        val parsedLabels = parseLabeledOptions(lines)
        if (parsedLabels.isNotEmpty()) {
            val questionLines = lines.takeWhile { !isLabeledOption(it) }
            val parsedQuestion = questionLines.joinToString("\n").trim()
            if (parsedQuestion.isNotEmpty() &&
                (question.isEmpty() || looksLikeQuestion(parsedQuestion) &&
                    !parsedQuestion.equals(question, ignoreCase = true))
            ) {
                question = parsedQuestion
                collectedOptions.clear()
            }
            parsedLabels.forEach(::appendOption)
            return true
        }

        if (question.isEmpty()) {
            question = lines.first()
            lines.drop(1).map(::unlabeledOption).forEach(::appendOption)
        } else if (collectedOptions.isNotEmpty() && lines.size == 1 && looksLikeQuestion(lines.single())) {
            question = lines.single()
            collectedOptions.clear()
        } else {
            lines.map(::unlabeledOption).forEach(::appendOption)
        }
        return true
    }

    fun canAnalyze(): Boolean = question.trim().length >= MIN_QUESTION_LENGTH &&
        collectedOptions.size >= MIN_OPTIONS &&
        question.length + collectedOptions.sumOf { it.text.length } <= MAX_QUESTION_LENGTH

    fun requestText(): String = buildString {
        appendLine("ENUNCIADO:")
        appendLine(question)
        appendLine()
        appendLine("ALTERNATIVAS EN SU ORDEN ORIGINAL:")
        collectedOptions.forEachIndexed { index, option ->
            val identifier = option.label ?: "OPCIÓN ${index + 1}"
            appendLine("$identifier: ${option.text}")
        }
    }.trim()

    fun preview(): String = buildString {
        if (question.isNotBlank()) {
            appendLine("Pregunta:")
            appendLine(question)
        } else {
            appendLine("Aún falta copiar el enunciado.")
        }
        appendLine()
        appendLine("Alternativas (${collectedOptions.size}):")
        if (collectedOptions.isEmpty()) {
            append("Aún no se copiaron alternativas.")
        } else {
            collectedOptions.forEachIndexed { index, option ->
                val identifier = option.label ?: "OPCIÓN ${index + 1}"
                appendLine("$identifier: ${option.text}")
            }
        }
    }.trim()

    fun clear() {
        question = ""
        collectedOptions.clear()
    }

    private fun appendOption(option: AnswerOption) {
        val cleanText = option.text.trim()
        if (cleanText.isNotEmpty() && collectedOptions.none {
                it.label == option.label && it.text.equals(cleanText, ignoreCase = true)
            }
        ) {
            collectedOptions.add(option.copy(text = cleanText))
        }
    }

    private fun parseLabeledOptions(lines: List<String>): List<AnswerOption> {
        val options = mutableListOf<AnswerOption>()
        var pendingLabel: String? = null
        for (line in lines) {
            val match = labeledOption.matchEntire(line)
            if (match != null) {
                pendingLabel?.let { options.add(AnswerOption(it, "")) }
                pendingLabel = null
                options.add(AnswerOption(match.groupValues[1].uppercase(), match.groupValues[2].trim()))
                continue
            }
            val labelOnly = labelOnlyOption.matchEntire(line)
            if (labelOnly != null) {
                pendingLabel?.let { options.add(AnswerOption(it, "")) }
                pendingLabel = labelOnly.groupValues[1].uppercase()
                continue
            }
            if (pendingLabel != null) {
                options.add(AnswerOption(pendingLabel, line))
                pendingLabel = null
            }
        }
        pendingLabel?.let { options.add(AnswerOption(it, "")) }
        return options.filter { it.text.isNotBlank() }
    }

    private fun isLabeledOption(line: String): Boolean =
        labeledOption.matches(line) || labelOnlyOption.matches(line)

    private fun unlabeledOption(line: String): AnswerOption {
        val text = listMarker.replace(line, "").trim()
        return AnswerOption(null, text)
    }

    private fun looksLikeQuestion(text: String): Boolean {
        val value = text.trim()
        return value.endsWith("?") || value.startsWith("¿") || questionLead.containsMatchIn(value)
    }

    companion object {
        private const val MIN_QUESTION_LENGTH = 5
        private const val MIN_OPTIONS = 2
        private const val MAX_QUESTION_LENGTH = 10000
        private val labeledOption = Regex("""^\s*([A-Ea-e])\s*[).:\-]\s*(.+?)\s*$""")
        private val labelOnlyOption = Regex("""^\s*([A-Ea-e])\s*[).:\-]?\s*$""")
        private val listMarker = Regex("""^\s*(?:[-*•▪]\s*|\d+\s*[).]\s*)""")
        private val questionLead = Regex(
            """(?i)^(?:qué|que|cuál|cual|quién|quien|cómo|como|cuándo|cuando|dónde|donde|por qué|por que|para qué|para que)\b"""
        )
    }
}
