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

/**
 * 对模型内置的 `Demo` 骨骼动画做区间定位播放。
 *
 * 该动画的实际内容是「开书 → 驻留 → 合书」，不含翻页。时间轴分段（fps = 120，实测）：
 *
 * | 语义     | 帧        | 秒（相对片段起点）  |
 * |----------|-----------|---------------------|
 * | 合着驻留 | 5 – 100   | 0.000 – 0.792       |
 * | 开书     | 100 → 200 | 0.792 → 1.625       |
 * | 摊开驻留 | 200 – 300 | 1.625 – 2.458       |
 * | 合书     | 300 → 400 | 2.458 → 3.292       |
 *
 * **秒数是相对片段起点的，不是 `帧 / fps`。** controller 的时间轴零点落在片段的第一个
 * 关键帧（frame 5）上，而不是 frame 0，所以换算要减去 [CLIP_START_FRAME]，见 [atFrame]。
 * 佐证：`(400 - 5) / 120 = 3.29167`，与实测总时长 3.29166s 吻合到五位小数；若按 `帧 / fps`
 * 直接换算，每个端点都会晚 5 帧（42ms），而 `CLOSE_END` 会算成 3.333s —— 超出片段实际
 * 时长，`getTime()` 永远到不了，合书区间每次都会走满超时闸。
 *
 * `scope` 必须是主线程 scope：[AnimationPlaybackController] 标注了 `@MainThread`，
 * 且 `BookState.phase` 是无同步的普通 `var`。本类不做任何 dispatcher 切换，
 * 因此所有回调都在 `scope` 的线程（即主线程）上投递。
 *
 * ## 回调只投递一次，且不会在被取消后补投
 *
 * `BookState` 没有幂等闸：一次 `open` / `closeThenOpen` 的 `onSwap` / `onDone`
 * 必须恰好触发一次，被打断的区间绝不能事后再触发。为此每次启动序列都自增
 * [generation]，回调投递前用 [isLive] 校验自己那一代仍然是当前代。
 *
 * 单靠 `Job.cancel()` 挡不住的具体是这两种情况：
 *
 * 1. **重入。** 回调本身调用了 [showClosed] / [close]，取消掉自己所在的这个序列，然后
 *    返回到仍在执行的序列体里继续往下走。此时 `cancel()` 完全无效 —— 协程只在挂起点
 *    响应取消，而重入发生在两个挂起点之间。这是主线程下唯一真正可达的路径。
 * 2. **`scope` 并非主线程受限。** 那样 `cancel()` 与序列体就能真正并发，取消可能落在
 *    轮询结束之后、回调之前那段不挂起的窗口里。这一条目前只靠上面的 KDoc 约束，
 *    没有运行时断言，所以留着 generation 兜住。
 *
 * 反过来说：只要 `scope` 确实是主线程受限的，情况 2 不可达 —— `cancel()` 由主线程发起，
 * 序列体也在主线程跑，两者无法交错；挂起在 `delay` 上时收到取消会在恢复时抛出。
 * generation 校验本身是主线程上的 check-then-act，因此无需同步。
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

    /**
     * 立即定位到合着的姿态，不播放。
     *
     * **会丢弃在跑序列的未投递回调。** 若在 [open] / [closeThenOpen] 播放途中调用，
     * 该序列的 `onDone` 永远不会触发，`BookState` 会永久停在 `Opening` / `Reshuffling`,
     * 表现为「书戳不动了」。这是故意的 —— 另一种选择是投递一个过期回调，而那会提前
     * 解开状态机的 tap 闸门，是更糟的坏法。当前唯一的调用点是 Task 7 构造后的一次性
     * 初始化，那时还不可能有触碰进来。若将来要在播放途中调用，得先给状态机补一条
     * 「动画被放弃」的复位路径。
     */
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
            // onSwap 可能重入 close() / showClosed()。不复查就会对已释放的 controller
            // 调 setTime / resume。
            if (!isLive(gen)) return@launchSequence
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

        // 终点按实测时长收口，且回退一帧：`getTime() < target` 要求精确命中，若运行时
        // 把时间钳在比 duration 差一个 tick 的位置，不回退就永远退不出轮询，每次都白烧
        // 满额超时预算。提前一帧收尾落在已经完全合上的姿态里，看不出来。
        // 有了重新推导过的帧常量，CLOSE_END 已经约等于 duration，这里只是兜底，
        // 同时也吸收测量漂移。
        val duration = controller.getDuration()
        val target = if (duration > 0f) minOf(to, duration - 1f / FPS) else to

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

        /** 片段的第一个关键帧。controller 的时间轴零点在这里，不在 frame 0。 */
        const val CLIP_START_FRAME = 5f

        val CLOSED_POSE = atFrame(5f)      // 0.00000s
        val OPEN_START = atFrame(100f)     // 0.79167s
        val OPEN_END = atFrame(200f)       // 1.62500s
        val CLOSE_START = atFrame(300f)    // 2.45833s
        val CLOSE_END = atFrame(400f)      // 3.29167s ≈ 实测总时长

        const val POLL_INTERVAL_MS = 16L
        const val MIN_BUDGET_MS = 500L

        /** 帧号 → controller 时间轴上的秒。 */
        private fun atFrame(frame: Float): Float = (frame - CLIP_START_FRAME) / FPS
    }
}
