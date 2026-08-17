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
        val first = AnswerRepository(source, Random(99))
        val second = AnswerRepository(source, Random(99))
        val a = (1..30).map { first.next() }
        val b = (1..30).map { second.next() }
        assertEquals(a, b)
    }

    @Test
    fun `window is capped at half the corpus so small lists keep more than one candidate`() {
        // Guards the load-bearing cap. Under min(capacity, size - 1) a 3-answer
        // corpus degenerates into a fixed A-B-C cycle, where a value can never
        // recur two draws later. Under min(capacity, size / 2) it can, and does.
        val repo = AnswerRepository(answers(3), Random(5), recentCapacity = 32)
        val drawn = (1..200).map { repo.next() }
        val recursAfterTwo = drawn.indices.drop(2).any { drawn[it] == drawn[it - 2] }
        assertTrue(
            "a 3-answer corpus should allow a value to recur two draws later; " +
                "if it never does, the recent window is capping at size - 1 instead of size / 2",
            recursAfterTwo,
        )
    }
}
