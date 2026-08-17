package com.illusion.bookofanswers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AnswerRepositoryTest {

    private fun answers(n: Int) = (1..n).map { Answer("a$it") }

    @Test
    fun `always returns an answer from the source list`() {
        val source = answers(50)
        val repo = AnswerRepository(source, Random(1))
        repeat(200) { assertTrue(repo.next() in source) }
    }

    @Test
    fun `does not repeat within the recent window`() {
        // windowSize = min(10, 100 / 2) = 10，故任意 11 连抽应互不相同
        val repo = AnswerRepository(answers(100), Random(42), recentCapacity = 10)
        val drawn = (1..500).map { repo.next() }
        drawn.windowed(11).forEach { window ->
            assertEquals("窗口内出现重复: $window", 11, window.distinct().size)
        }
    }

    @Test
    fun `single answer list does not hang`() {
        val repo = AnswerRepository(listOf(Answer("only")), Random(1))
        repeat(20) { assertEquals(Answer("only"), repo.next()) }
    }

    @Test
    fun `tiny list does not hang even with large capacity`() {
        // 兜底路径就是 3 条答案配默认 capacity=32，必须不能死循环
        val repo = AnswerRepository(answers(3), Random(7), recentCapacity = 32)
        val drawn = (1..100).map { repo.next() }
        assertEquals(3, drawn.distinct().size)
    }

    @Test
    fun `empty list is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnswerRepository(emptyList(), Random(1))
        }
    }

    @Test
    fun `same seed yields the same sequence`() {
        val source = answers(60)
        val a = (1..30).map { AnswerRepository(source, Random(99)).next() }
        val b = (1..30).map { AnswerRepository(source, Random(99)).next() }
        assertEquals(a, b)
    }
}
