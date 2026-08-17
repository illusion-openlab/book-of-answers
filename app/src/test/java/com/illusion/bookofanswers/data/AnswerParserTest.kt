package com.illusion.bookofanswers.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerParserTest {

    @Test
    fun `parses one answer per line`() {
        val result = AnswerParser.parse("去做吧\n再等等\n不要回头")
        assertEquals(
            listOf(Answer("去做吧"), Answer("再等等"), Answer("不要回头")),
            result,
        )
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(listOf(Answer("去做吧")), AnswerParser.parse("  去做吧  "))
    }

    @Test
    fun `skips blank lines`() {
        val result = AnswerParser.parse("去做吧\n\n   \n再等等\n")
        assertEquals(listOf(Answer("去做吧"), Answer("再等等")), result)
    }

    @Test
    fun `handles windows line endings`() {
        val result = AnswerParser.parse("去做吧\r\n再等等")
        assertEquals(listOf(Answer("去做吧"), Answer("再等等")), result)
    }

    @Test
    fun `empty input yields empty list`() {
        assertEquals(emptyList<Answer>(), AnswerParser.parse(""))
        assertEquals(emptyList<Answer>(), AnswerParser.parse("   \n  \n"))
    }
}
