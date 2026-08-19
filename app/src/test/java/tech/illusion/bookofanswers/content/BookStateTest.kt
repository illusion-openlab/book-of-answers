package tech.illusion.bookofanswers.content

import org.junit.Assert.assertEquals
import org.junit.Test

class BookStateTest {

    /** 手写的假动画：记录调用次数，完成回调留给测试手动触发。 */
    private class FakeAnimator {
        var openCalls = 0
        var closeCalls = 0
        private var openDone: (() -> Unit)? = null
        private var closeDone: (() -> Unit)? = null

        fun open(onDone: () -> Unit) {
            openCalls++
            openDone = onDone
        }

        fun close(onDone: () -> Unit) {
            closeCalls++
            closeDone = onDone
        }

        fun finishOpen() = openDone!!.invoke()
        fun finishClose() = closeDone!!.invoke()
    }

    private class Harness {
        val animator = FakeAnimator()
        var draws = 0
        var prompts = 0
        val state = BookState(
            openBook = animator::open,
            closeBook = animator::close,
            drawAnswer = { draws++ },
            showPrompt = { prompts++ },
        )

        /** 走完一次完整的「开 → 合」。 */
        fun cycle() {
            state.onTap(); animator.finishOpen()
            state.onTap(); animator.finishClose()
        }
    }

    @Test
    fun `starts closed`() {
        assertEquals(BookPhase.Closed, Harness().state.phase)
    }

    @Test
    fun `first tap opens the book without drawing yet`() {
        val h = Harness()
        h.state.onTap()
        assertEquals(BookPhase.Opening, h.state.phase)
        assertEquals(1, h.animator.openCalls)
        assertEquals("答案应在动画结束后才抽", 0, h.draws)
    }

    @Test
    fun `finishing open draws an answer and settles open`() {
        val h = Harness()
        h.state.onTap()
        h.animator.finishOpen()
        assertEquals(BookPhase.Open, h.state.phase)
        assertEquals(1, h.draws)
    }

    @Test
    fun `taps during opening are ignored`() {
        val h = Harness()
        h.state.onTap()
        h.state.onTap()
        h.state.onTap()
        assertEquals(1, h.animator.openCalls)
        assertEquals(0, h.animator.closeCalls)
        assertEquals(BookPhase.Opening, h.state.phase)
    }

    @Test
    fun `tap while open closes the book`() {
        val h = Harness()
        h.state.onTap(); h.animator.finishOpen()
        h.state.onTap()
        assertEquals(BookPhase.Closing, h.state.phase)
        assertEquals(1, h.animator.closeCalls)
        assertEquals("合上过程中不该换文案", 0, h.prompts)
    }

    @Test
    fun `finishing close restores the prompt and returns to closed`() {
        val h = Harness()
        h.state.onTap(); h.animator.finishOpen()
        h.state.onTap(); h.animator.finishClose()
        assertEquals(BookPhase.Closed, h.state.phase)
        assertEquals(1, h.prompts)
        assertEquals("合上不该再抽答案", 1, h.draws)
    }

    @Test
    fun `taps during closing are ignored`() {
        val h = Harness()
        h.state.onTap(); h.animator.finishOpen()
        h.state.onTap()
        h.state.onTap()
        h.state.onTap()
        assertEquals(1, h.animator.closeCalls)
        // 若把 Closing 误归到 Closed 分支，这里会再走一次开书；没有这条断言，
        // 上面的 closeCalls 仍然是 1，回归会溜过去。
        assertEquals(1, h.animator.openCalls)
        assertEquals(BookPhase.Closing, h.state.phase)
    }

    @Test
    fun `each opening draws a fresh answer`() {
        val h = Harness()
        h.cycle()
        h.cycle()
        h.cycle()
        assertEquals("开了三次就该抽三次", 3, h.draws)
        assertEquals(3, h.animator.openCalls)
        assertEquals(3, h.animator.closeCalls)
        assertEquals(BookPhase.Closed, h.state.phase)
    }
}
