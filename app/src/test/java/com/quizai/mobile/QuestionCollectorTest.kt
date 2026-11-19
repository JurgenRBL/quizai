package com.quizai.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionCollectorTest {
    @Test fun collectsQuestionAndUnlabeledOptionsFromSeparateClipboardCopies() {
        val collector = QuestionCollector()
        collector.addClipboardText("¿Cuál es la principal ventaja de un ERP?")
        collector.addClipboardText("Garantizar un incremento inmediato de las ventas")
        collector.addClipboardText("Eliminar los silos de información entre departamentos")

        assertEquals("¿Cuál es la principal ventaja de un ERP?", collector.question)
        assertEquals(2, collector.options.size)
        assertEquals(null, collector.options[1].label)
        assertTrue(collector.canAnalyze())
        assertTrue(collector.requestText().contains("OPCIÓN 2: Eliminar los silos"))
    }

    @Test fun preservesLabeledOptionsAndTheirOriginalOrder() {
        val collector = QuestionCollector()
        collector.addClipboardText(
            "¿Cuál es la principal ventaja de utilizar un ERP?\n" +
                "A) Aumentar ventas\nB) Eliminar los silos\nC) Evitar computadoras\nD) Reemplazar directivos"
        )

        assertEquals(4, collector.options.size)
        assertEquals(listOf("A", "B", "C", "D"), collector.options.map { it.label })
        assertEquals("Eliminar los silos", collector.options[1].text)
        assertTrue(collector.canAnalyze())
    }

    @Test fun splitsAnUnlabeledQuestionAndOptionsCopiedTogether() {
        val collector = QuestionCollector()
        collector.addClipboardText(
            "¿Cuál es la principal ventaja de utilizar un ERP?\n" +
                "Aumentar las ventas\nEliminar los silos\nReducir el uso de computadoras"
        )

        assertEquals(3, collector.options.size)
        assertEquals("OPCIÓN 2: Eliminar los silos", collector.requestText().lines()[5])
    }

    @Test fun refusesToAnalyzeWhenTheQuestionOrOptionsAreIncomplete() {
        val collector = QuestionCollector()
        collector.addClipboardText("¿Cuál es la principal ventaja de un ERP?")
        collector.addClipboardText("Eliminar los silos de información")
        assertFalse(collector.canAnalyze())
    }
    @Test fun rereadingStatementOrFullQuestionDoesNotCreateExtraOptions() {
        val collector = QuestionCollector()
        val statement = "?Cu?l es la ventaja principal?"
        val full = "$statement\nPrimera alternativa\nSegunda alternativa"
        collector.addClipboardText(full)
        collector.addClipboardText(statement)
        collector.addClipboardText(full)
        assertEquals(2, collector.options.size)
        assertEquals(listOf("Primera alternativa", "Segunda alternativa"), collector.options.map { it.text })
    }
}
