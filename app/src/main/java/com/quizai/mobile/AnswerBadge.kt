package com.quizai.mobile

internal object AnswerBadge {
    fun selectedOption(identifier: String?, options: List<AnswerOption>): AnswerOption? {
        if (identifier == null) return null
        return if (identifier.all(Char::isDigit)) {
            identifier.toIntOrNull()?.let { options.getOrNull(it - 1) }?.takeIf { it.label == null }
        } else options.firstOrNull { it.label == identifier }
    }

    fun label(option: String, recognized: Boolean, optionCount: Int): String? {
        if (!recognized) return null
        Regex("""^\s*([A-Ea-e])(?:\s*$|\s*[).:\-].*)""", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(option)?.groupValues?.get(1)?.uppercase()?.let { return it }
        val number = Regex("""(?iu)^\s*(?:opci[óo]n\s*)?(\d+)\s*(?:$|[).:\-].*)""", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(option)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return number.takeIf { it in 1..optionCount }?.toString()
    }
}
