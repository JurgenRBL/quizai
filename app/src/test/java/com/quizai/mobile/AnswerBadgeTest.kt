package com.quizai.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerBadgeTest {
    @Test fun acceptsExplicitOptionLabels() {
        for (letter in listOf("A", "B", "C", "D")) {
            assertEquals(letter, AnswerBadge.label(letter, true, 4))
            assertEquals(letter, AnswerBadge.label(" ${letter.lowercase()}) Una alternativa", true, 4))
        }
        assertEquals("B", AnswerBadge.label("B. Texto", true, 4))
        assertEquals("C", AnswerBadge.label("C: Texto", true, 4))
        assertEquals("E", AnswerBadge.label("E) Quinta alternativa", true, 5))
    }

    @Test fun doesNotInventALetterFromProseOrMissingOptions() {
        for (option in listOf("", "F", "Respuesta B", "Ambas", "B y C", "A/B", "Duda")) {
            assertNull(option, AnswerBadge.label(option, true, 4))
        }
        assertEquals("2", AnswerBadge.label("2", true, 4))
        assertEquals("4", AnswerBadge.label("4", true, 4))
        assertNull(AnswerBadge.label("0", true, 4))
        assertNull(AnswerBadge.label("5", true, 4))
        assertNull(AnswerBadge.label("2", true, 0))
        assertNull(AnswerBadge.label("A", false, 4))
    }
    @Test fun resolvesOnlyOriginalIdentifiersAndReturnsFullText() {
        val numbered = listOf(AnswerOption(null, "Primera alternativa"), AnswerOption(null, "Segunda alternativa completa"))
        assertEquals("Segunda alternativa completa", AnswerBadge.selectedOption("2", numbered)?.text)
        assertNull(AnswerBadge.selectedOption("B", numbered))
        assertNull(AnswerBadge.selectedOption("3", numbered))
        val labeled = listOf(AnswerOption("D", "Original D"), AnswerOption("B", "Original B"))
        assertEquals("Original B", AnswerBadge.selectedOption("B", labeled)?.text)
        assertNull(AnswerBadge.selectedOption("A", labeled))
        assertNull(AnswerBadge.selectedOption("2", labeled))
    }
}
