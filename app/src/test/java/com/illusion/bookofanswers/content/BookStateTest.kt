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
        // 下面这两条各挡一种把 Reshuffling 归错组的写法，且必须两条都在：
        //
        // - 归到 Revealed 一侧 → closeThenOpenCalls 会变成 3，上面那条就够。
        // - 归到 **Closed** 一侧 → 第一次多余的触碰就会走开书分支，openCalls 1→2、phase 变成
        //   Opening（之后两次触碰被 Opening 那道闸挡住，所以是 2 而不是 3），而
        //   closeThenOpenCalls 仍是 1、draws 也没变 —— 本分支上原有的每一条断言都照样通过。
        //   实测过：把 Reshuffling 并进 Closed 分支后，20 个测试里只有本条 openCalls 断言失败
        //   （expected:<1> but was:<2>），所以它不是冗余。
        assertEquals("重抽途中不该再走开书分支", 1, h.animator.openCalls)
        assertEquals(BookPhase.Reshuffling, h.state.phase)
    }
}
