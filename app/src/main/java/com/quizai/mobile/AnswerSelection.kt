package com.quizai.mobile

internal data class SelectedAnswer(val identifier: String, val text: String)

/** Resolves a model selection against the original text, without fabricating labels. */
internal object AnswerSelection {
    fun resolvePhoto(question: String, options: List<String>, recommendation: String, recognized: Boolean): SelectedAnswer? {
        if (!Regex("""^\s*\d+(?:\s*[).:\-]|\s+)\s*\S""").containsMatchIn(question) ||
            options.size !in 3..5 || options.any { it.isBlank() }) return null
        return resolve((listOf(question) + options).joinToString("\n"), options, recommendation, recognized)
    }

    private fun normalize(text: String) = text.replace(Regex("\\s+"), " ").trim()

    fun resolve(input: String, options: List<String>, recommendation: String, recognized: Boolean): SelectedAnswer? {
        if (!recognized || options.size < 2) return null
        val source = normalize(input)
        var nextPosition = 0
        val originalOptions = mutableListOf<AnswerOption>()
        for (option in options) {
            val text = normalize(option).replace(
                Regex("^(?:[A-Ea-e]\\s*[).:\\-]|(?:OPCI[ÓO]N\\s*)?\\d+\\s*[).:]|[-•])\\s*"), "")
            if (text.isBlank()) return null
            val position = source.indexOf(text, nextPosition, ignoreCase = true)
            if (position < 0) return null
            val prefix = source.substring(0, position)
            val label = Regex("(?:^|\\s)([A-Ea-e])\\s*[).:\\-]\\s*$")
                .find(prefix)?.groupValues?.get(1)?.uppercase()
                ?: Regex("(?:^|\\s)([A-Ea-e])\\s*$").find(prefix)?.groupValues?.get(1)
                    ?.takeIf { Regex("(?m)^\\s*$it\\s*$").containsMatchIn(input) }?.uppercase()
            originalOptions.add(AnswerOption(label, source.substring(position, position + text.length)))
            nextPosition = position + text.length
        }
        val identifier = AnswerBadge.label(recommendation, true, options.size) ?: return null
        val selected = AnswerBadge.selectedOption(identifier, originalOptions) ?: return null
        return SelectedAnswer(identifier, selected.text)
    }
}
