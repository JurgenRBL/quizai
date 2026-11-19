package com.quizai.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoAnswerSelectionTest {
    @Test fun resolvesLabelsTranscribedFromOnlyThePhoto() {
        assertEquals(SelectedAnswer("B", "Paris"), AnswerSelection.resolvePhoto(
            "12. Capital de Francia?", listOf("A) Roma", "B) Paris", "C) Lima"), "B", true))
    }

    @Test fun preservesOriginalPositionsWithoutConfusingQuestionNumber() {
        for (question in listOf("12. Capital?", "12: Capital?", "12) Capital?", "12 Capital?", "12\nCapital?")) {
            assertEquals(SelectedAnswer("2", "Paris"), AnswerSelection.resolvePhoto(
                question, listOf("Roma", "Paris", "Lima"), "2", true))
        }
        assertNull(AnswerSelection.resolvePhoto("12. Capital?", listOf("Roma", "Paris", "Lima"), "12", true))
        assertNull(AnswerSelection.resolvePhoto("12. Capital?", listOf("Roma", "Paris", "Lima"), "B", true))
    }

    @Test fun supportsFifthLetterAndNumericAlternative() {
        assertEquals(SelectedAnswer("E", "Cinco"), AnswerSelection.resolvePhoto(
            "7. Elige cinco:", listOf("A) Uno", "B) Dos", "C) Tres", "D) Cuatro", "E) Cinco"), "E", true))
        assertEquals(SelectedAnswer("5", "Cinco"), AnswerSelection.resolvePhoto(
            "7. Elige cinco:", listOf("Uno", "Dos", "Tres", "Cuatro", "Cinco"), "5", true))
    }

    @Test fun refusesUnnumberedUnreadableIncompleteOrOversizedBlocks() {
        assertNull(AnswerSelection.resolvePhoto("", listOf("Roma", "Paris", "Lima"), "2", true))
        assertNull(AnswerSelection.resolvePhoto("Capital?", listOf("Roma", "Paris", "Lima"), "2", true))
        assertNull(AnswerSelection.resolvePhoto("1. Capital?", listOf("Roma", "Paris", ""), "1", true))
        assertNull(AnswerSelection.resolvePhoto("1. Capital?", listOf("Roma", "Paris"), "1", true))
        assertNull(AnswerSelection.resolvePhoto("1. Capital?", listOf("A", "B", "C", "D", "E", "F"), "1", true))
        assertNull(AnswerSelection.resolvePhoto("1. Capital?", listOf("Roma", "Paris", "Lima"), "4", true))
        assertNull(AnswerSelection.resolvePhoto("1. Capital?", listOf("Roma", "Paris", "Lima"), "2", false))
    }
}
