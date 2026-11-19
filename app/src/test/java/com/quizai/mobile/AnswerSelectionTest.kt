package com.quizai.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerSelectionTest {
    @Test fun resolvesInlineOptionsWithoutDependingOnClipboardLineBreaks() {
        val input = "Capital de Francia: A) Madrid B) París C) Roma"
        assertEquals(SelectedAnswer("B", "París"),
            AnswerSelection.resolve(input, listOf("Madrid", "París", "Roma"), "B)", true))
    }

    @Test fun preservesNumbersAndFullOptionText() {
        val input = "Capital de Francia:\nMadrid\nParís, capital francesa\nRoma"
        assertEquals(SelectedAnswer("2", "París, capital francesa"),
            AnswerSelection.resolve(input, listOf("Madrid", "París, capital francesa", "Roma"), "OPCIÓN 2", true))
        assertNull(AnswerSelection.resolve(input, listOf("Madrid", "París, capital francesa", "Roma"), "B", true))
    }

    @Test fun rejectsInventedOrReorderedOptionsAndOutOfRangeAnswers() {
        val input = "¿Capital de Francia?\nMadrid\nParís\nRoma"
        assertNull(AnswerSelection.resolve(input, listOf("Roma", "Madrid", "París"), "1", true))
        assertNull(AnswerSelection.resolve(input, listOf("Madrid", "Londres", "Roma"), "2", true))
        assertNull(AnswerSelection.resolve(input, listOf("Madrid", "París", "Roma"), "4", true))
        assertNull(AnswerSelection.resolve(input, listOf("Madrid", "París", "Roma"), "2", false))
    }

    @Test fun supportsWrappedAnswersAndStandaloneLabels() {
        val input = "Elige una alternativa:\nA\nUn texto\nlargo\nB\nOtro texto"
        assertEquals(SelectedAnswer("A", "Un texto largo"),
            AnswerSelection.resolve(input, listOf("A) Un texto largo", "B) Otro texto"), "A", true))
    }
}
