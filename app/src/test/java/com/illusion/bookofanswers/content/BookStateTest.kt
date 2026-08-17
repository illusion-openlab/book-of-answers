package com.illusion.bookofanswers.content

import org.junit.Assert.assertEquals
import org.junit.Test

class BookStateTest {

    /** 手写的假动画：记录调用次数，并把完成回调留给测试手动触发。 */
    private class FakeAnimator {
        var openCalls = 0
        var closeThenOpenCalls = 0
        private var openDone: (() -> Unit)? = null
        private var swap: (() -> Unit)? = null
        private var reshuffleDone: (() -> Unit)? = null

        fun open(onDone: () -> Unit) {
            openCalls++
            openDone = onDone
        }

        fun closeThenOpen(onSwap: () -> Unit, onDone: () -> Unit) {
            closeThenOpenCalls++
            swap = onSwap
            reshuffleDone = onDone
        }

        fun finishOpen() = openDone!!.invoke()
        fun triggerSwap() = swap!!.invoke()
        fun finishReshuffle() = reshuffleDone!!.invoke()
    }

    private class Harness {
        val animator = FakeAnimator()
        var draws = 0
        val state = BookState(
            openBook = animator::open,
            closeThenOpen = animator::closeThenOpen,
            drawAnswer = { draws++ },
        )
    }

    @Test
    fun `starts closed`() {
        assertEquals(BookPhase.Closed, Harness().state.phase)
    }

    @Test
    fun `first tap opens the book`() {
        val h = Harness()
        h.state.onTap()
        assertEquals(BookPhase.Opening, h.state.phase)
        assertEquals(1, h.animator.openCalls)
        assertEquals("答案应在动画结束后才抽", 0, h.draws)
    }

    @Test
    fun `finishing open draws an answer and reveals`() {
        val h = Harness()
        h.state.onTap()
        h.animator.finishOpen()
        assertEquals(BookPhase.Revealed, h.state.phase)
        assertEquals(1, h.draws)
    }

    @Test
    fun `taps during opening are ignored`() {
        val h = Harness()
        h.state.onTap()
        h.state.onTap()
        h.state.onTap()
        assertEquals(1, h.animator.openCalls)
        assertEquals(BookPhase.Opening, h.state.phase)
    }

    @Test
    fun `tap while revealed starts a reshuffle`() {
        val h = Harness()
        h.state.onTap(); h.animator.finishOpen()
        h.state.onTap()
        assertEquals(BookPhase.Reshuffling, h.state.phase)
        assertEquals(1, h.animator.closeThenOpenCalls)
    }

    @Test
    fun `reshuffle swaps the answer while the book is shut`() {
        val h = Harness()
        h.state.onTap(); h.animator.finishOpen()
        assertEquals(1, h.draws)
        h.state.onTap()
        h.animator.triggerSwap()
        assertEquals("合上瞬间应换答案", 2, h.draws)
        h.animator.finishReshuffle()
        assertEquals(BookPhase.Revealed, h.state.phase)
        assertEquals("重开阶段不应再抽一次", 2, h.draws)
    }

    @Test
    fun `taps during reshuffling are ignored`() {
        val h = Harness()
        h.state.onTap(); h.animator.finishOpen()
        h.state.onTap()
        h.state.onTap()
        h.state.onTap()
        assertEquals(1, h.animator.closeThenOpenCalls)
    }
}
