package com.illusion.bookofanswers.content

import android.util.Log
import com.pico.spatial.core.ecs.animation.AnimationPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import kotlin.math.min

/**
 * 对模型内置的 `Demo` 骨骼动画做区间定位播放。
 *
 * 该动画的实际内容是「开书 → 驻留 → 合书」，不含翻页。时间轴分段（fps = 120，实测）：
 *
 * | 语义     | 帧        | 秒          |
 * |----------|-----------|-------------|
 * | 合着驻留 | 5 – 100   | 0.04 – 0.83 |
 * | 开书     | 100 → 200 | 0.83 → 1.67 |
 * | 摊开驻留 | 200 – 300 | 1.67 – 2.50 |
 * | 合书     | 300 → 400 | 2.50 → 3.33 |
 *
 * `scope` 必须是主线程 scope：[AnimationPlaybackController] 标注了 `@MainThread`，
 * 且 `BookState.phase` 是无同步的普通 `var`。本类不做任何 dispatcher 切换，
 * 因此所有回调都在 `scope` 的线程（即主线程）上投递。
 *
 * ## 回调只投递一次，且不会在被取消后补投
 *
 * `BookState` 没有幂等闸：一次 `open` / `closeThenOpen` 的 `onSwap` / `onDone`
 * 必须恰好触发一次，被打断的区间绝不能事后再触发。为此每次启动序列都自增
 * [generation]，回调投递前校验自己那一代仍然是当前代。仅靠 `Job.cancel()`
 * 是不够的：协程只在挂起点响应取消，若取消恰好落在轮询结束之后、回调之前
 * 这段不挂起的窗口里，回调仍会照常执行 —— 那就是一次会提前解开状态机 tap
 * 闸门的过期回调。generation 校验堵住的正是这个窗口（主线程单线程执行，
 * 这里的 check-then-act 是安全的）。
 */
class BookAnimator(
    private val controller: AnimationPlaybackController,
    private val scope: CoroutineScope,
) : Closeable {

    /** 当前序列的协程。整个类同一时刻只有一个 job。 */
    private var job: Job? = null

    /** 每次启动/取消序列自增。回调据此判断自己是否仍属于当前序列。 */
    private var generation: Long = 0L

    private var closed = false

    /** 立即定位到合着的姿态，不播放。 */
    fun showClosed() {
        if (closed) return
        cancelRunning()
        controller.setTime(CLOSED_POSE)
        controller.pause()
    }

    /** 翻开 → [onDone]。 */
    fun open(onDone: () -> Unit) {
        if (closed) return
        launchSequence { gen ->
            playSegment(OPEN_START, OPEN_END)
            if (isLive(gen)) onDone()
        }
    }

    /** 合上 → 在完全合上的瞬间执行 [onSwap] → 重新翻开 → [onDone]。 */
    fun closeThenOpen(onSwap: () -> Unit, onDone: () -> Unit) {
        if (closed) return
        // 两段放在同一个协程里顺序执行。若像「合书区间的完成回调里再起一个新区间」
        // 那样写，新区间的第一件事 `job?.cancel()` 取消的正是它自己所在的那个协程，
        // 语义要靠「新 job 挂在 scope 而非父协程下」这种细节才勉强成立。这里只留
        // 一个 job，不存在自取消。
        launchSequence { gen ->
            playSegment(CLOSE_START, CLOSE_END)
            if (!isLive(gen)) return@launchSequence
            onSwap()
            playSegment(OPEN_START, OPEN_END)
            if (isLive(gen)) onDone()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelRunning()
        controller.close()
    }

    /** 取消在跑的序列，并让它的回调作废。 */
    private fun cancelRunning() {
        generation++
        job?.cancel()
        job = null
    }

    private fun launchSequence(block: suspend CoroutineScope.(gen: Long) -> Unit) {
        cancelRunning()
        val gen = generation
        job = scope.launch { block(gen) }
    }

    /** 本代仍是当前代、协程未被取消、本类未被 close，才允许投递回调。 */
    private fun CoroutineScope.isLive(gen: Long): Boolean =
        isActive && !closed && gen == generation

    /**
     * 播到 [to] 为止后暂停。被取消时直接抛出，不在 `finally` 里碰 controller ——
     * 取消方（[showClosed] / [cancelRunning] 后新序列 / [close]）都会立刻自行
     * 重置或释放 controller，此处再补一次 `pause()` 有可能落在 `close()` 之后。
     */
    private suspend fun playSegment(from: Float, to: Float) {
        controller.setTime(from)
        controller.resume()

        // 实测整段时长 3.29166s，短于 CLOSE_END(3.333s)，`getTime()` 永远到不了终点。
        // 按实际时长收口，让超时闸回归兜底角色，而不是每次合书都走一遍。
        val duration = controller.getDuration()
        val target = if (duration > 0f) min(to, duration) else to

        // 宽限上限取区间时长的 2 倍，最少 500ms。超时即强制收尾，
        // 避免状态机永久卡在动画中而彻底失去响应。
        val budgetMs = (((to - from) * 2f) * 1000f).toLong().coerceAtLeast(MIN_BUDGET_MS)

        val reached = withTimeoutOrNull(budgetMs) {
            while (controller.getTime() < target) delay(POLL_INTERVAL_MS)
            true
        }
        if (reached == null) {
            Log.w(TAG, "segment $from -> $to timed out at ${controller.getTime()}, forcing completion")
        }
        controller.pause()
    }

    private companion object {
        const val TAG = "BookAnimator"
        const val FPS = 120f

        const val CLOSED_POSE = 5f / FPS      // 0.042s
        const val OPEN_START = 100f / FPS     // 0.833s
        const val OPEN_END = 200f / FPS       // 1.667s
        const val CLOSE_START = 300f / FPS    // 2.500s
        const val CLOSE_END = 400f / FPS      // 3.333s

        const val POLL_INTERVAL_MS = 16L
        const val MIN_BUDGET_MS = 500L
    }
}
