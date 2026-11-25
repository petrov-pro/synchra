package com.petroff.synchra.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarySectionsTest {

    private val sections = SummarySections(
        topic = "Сроки релиза",
        overview = "Обсудили, успеет ли команда к пятнице.",
        decisions = listOf("Релиз в пятницу", "Тесты до четверга"),
    )

    @Test
    fun `sections survive being stored and read back`() {
        assertEquals(sections, SummarySections.parse(sections.encode()))
    }

    @Test
    fun `a summary saved before sections existed is not taken for sections`() {
        assertNull(SummarySections.parse("Обсудили сроки."))
        assertNull(SummarySections.parse("{not json"))
    }

    @Test
    fun `stored text stays searchable in its own language`() {
        assertTrue(sections.encode().contains("Релиз в пятницу"))
    }

    @Test
    fun `plain text for copying has every block under its heading`() {
        assertEquals(
            "Встреча\n\nTopic\nСроки релиза\n\nOverview\nОбсудили, успеет ли команда к пятнице.\n\n" +
                "Decisions and results\n• Релиз в пятницу\n• Тесты до четверга",
            sections.toPlainText("Встреча"),
        )
    }

    @Test
    fun `an empty block is left out, heading and all`() {
        val noDecisions = sections.copy(decisions = emptyList())
        assertEquals(listOf("Topic", "Overview"), noDecisions.blocks().map { it.heading })
        assertEquals(
            "T\n\nTopic\nСроки релиза\n\nOverview\nОбсудили, успеет ли команда к пятнице.",
            noDecisions.toPlainText("T"),
        )
    }

    @Test
    fun `blank text counts as empty`() {
        val blank = SummarySections(topic = " ", overview = "Обсудили.", decisions = listOf("", "  "))
        assertEquals(listOf("Overview"), blank.blocks().map { it.heading })
    }
}
