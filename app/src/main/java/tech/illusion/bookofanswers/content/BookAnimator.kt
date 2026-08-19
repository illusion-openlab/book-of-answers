package tech.illusion.bookofanswers.content

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
  * 该动画的实际内容是「合上驻留 → 开书 → 摊开驻留 → 合书」，不含翻页。分段常量见伴生
 * 对象，都由离线逐帧解算蒙皮包围盒定出，不是猜的。
 * `scope` 必须是主线程 scope：[AnimationPlaybackController] 标注了 `@MainThread`，
 * 且 `BookState.phase` 是无同步的普通 `var`。本类不做任何 dispatcher 切换，
 * 因此所有回调都在 `scope` 的线程（即主线程）上投递。
 *
 * ## 回调只投递一次，且不会在被取消后补投
 *
 * `BookState` 没有幂等闸：一次 `open` / `closeBook` 的 `onDone`
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
    /**
     * 每个蒙皮网格一个控制器，**必须非空**。
     *
     * 为什么是列表而不是单个：这本书由三个独立的蒙皮网格组成（上封面、下封面、书页），
     * 各自绑着同一条 `Take_001`，`playAnimation` 也各自返回一个控制器。只驱动其中一个
     * 的话，画面上就只有那一层翻出去、其余仍是合着的厚块 —— 那正是「书没打开、只是往
     * 右挪了一点」的真身。
     *
     * 同步靠的是「同一条片段、同一起始时间、同一速率」：定位、恢复、暂停都对全部控制器
     * 施加，因此它们逐帧一致。进度只从 [clock] 读，避免多路采样互相打架。
     */
    private val controllers: List<AnimationPlaybackController>,
    private val scope: CoroutineScope,
    /**
     * 开合进度回调，0 = 完全合上，1 = 完全摊开。每个轮询 tick 调一次，并在区间收尾时
     * 用端点值再调一次（避免停在 0.98 这种中间值）。
     *
     * 存在的原因：模型动画自己带了约 +90° 的 roll，所以单一静态姿态无法同时让「合上」
     * 和「摊开」都平放 —— 合上要 roll=-90，摊开要 roll=0。调用方据此把实体姿态跟着
     * 播放进度插值。本类不碰实体，只报进度，姿态换算归 [loadBookScene] 所有。
     *
     * 与回调一起的线程契约同 [scope]：主线程，不切 dispatcher。
     */
    private val onOpenness: (Float) -> Unit = {},
) : Closeable {

    init {
        require(controllers.isNotEmpty()) { "BookAnimator needs at least one controller" }
    }

    /** 读进度的基准控制器。所有控制器同步播放，取哪个都一样，固定取第一个以免多路采样打架。 */
    private val clock: AnimationPlaybackController = controllers.first()

    /** 当前序列的协程。整个类同一时刻只有一个 job。 */
    private var job: Job? = null

    /** 每次启动/取消序列自增。回调据此判断自己是否仍属于当前序列。 */
    private var generation: Long = 0L

    private var closed = false

    /**
     * 立即定位到合着的姿态，不播放。
     *
     * **会丢弃在跑序列的未投递回调。** 若在 [open] / [closeBook] 播放途中调用，
     * 该序列的 `onDone` 永远不会触发，`BookState` 会永久停在 `Opening` / `Reshuffling`,
     * 表现为「书戳不动了」。这是故意的 —— 另一种选择是投递一个过期回调，而那会提前
     * 解开状态机的 tap 闸门，是更糟的坏法。当前唯一的调用点是 Task 7 构造后的一次性
     * 初始化，那时还不可能有触碰进来。若将来要在播放途中调用，得先给状态机补一条
     * 「动画被放弃」的复位路径。
     */
    fun showClosed() {
        if (closed) return
        cancelRunning()
        controllers.forEach {
            it.setTime(CLOSED_POSE)
            it.pause()
        }
        onOpenness(0f)
    }

    /** 翻开 → [onDone]。 */
    fun open(onDone: () -> Unit) {
        if (closed) return
        launchSequence { gen ->
            playSegment(OPEN_START, OPEN_END, fromOpenness = 0f, toOpenness = 1f)
            if (isLive(gen)) onDone()
        }
    }

    /**
     * 合上 → [onDone]。
     *
     * 刻意不叫 `close`：本类实现了 [Closeable]，`close()` 是释放 controller 的。
     * 两者同名会让「合上这本书」和「销毁这个动画器」在调用点上长得一模一样。
     */
    fun closeBook(onDone: () -> Unit) {
        if (closed) return
        launchSequence { gen ->
            playSegment(CLOSE_START, CLOSE_END, fromOpenness = 1f, toOpenness = 0f)
            if (isLive(gen)) onDone()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelRunning()
        controllers.forEach { it.close() }
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
    private suspend fun playSegment(
        from: Float,
        to: Float,
        fromOpenness: Float,
        toOpenness: Float,
    ) {
        controllers.forEach {
            it.setTime(from)
            it.resume()
        }

        // 终点按实测时长收口，且回退一帧：`getTime() < target` 要求精确命中，若运行时
        // 把时间钳在比 duration 差一个 tick 的位置，不回退就永远退不出轮询，每次都白烧
        // 满额超时预算。提前一帧收尾落在已经完全合上的姿态里，看不出来。
        // 有了重新推导过的帧常量，CLOSE_END 已经约等于 duration，这里只是兜底，
        // 同时也吸收测量漂移。
        val duration = clock.getDuration()
        val target = if (duration > 0f) minOf(to, duration - 1f / FPS) else to

        // 宽限上限取区间时长的 2 倍，最少 500ms。超时即强制收尾，
        // 避免状态机永久卡在动画中而彻底失去响应。
        val budgetMs = (((to - from) * 2f) * 1000f).toLong().coerceAtLeast(MIN_BUDGET_MS)

        val span = (target - from).takeIf { it > 0f }
        val reached = withTimeoutOrNull(budgetMs) {
            while (clock.getTime() < target) {
                // 进度按「已播时长 / 区间时长」算，而不是按墙钟 —— 播放速率若被改动
                // 或运行时掉帧，姿态仍然跟得住实际画面。
                val p = span?.let { ((clock.getTime() - from) / it).coerceIn(0f, 1f) } ?: 1f
                onOpenness(fromOpenness + (toOpenness - fromOpenness) * p)
                delay(POLL_INTERVAL_MS)
            }
            true
        }
        if (reached == null) {
            Log.w(TAG, "segment $from -> $to timed out at ${clock.getTime()}, forcing completion")
        }
        // 姿态收口：**先把片段时间钉死，再暂停。**
        //
        // 不能把最终姿态交给轮询落点。实测这台模拟器上 `delay(16)` 实际间隔到过 90–125ms，
        // 于是最后一跳会冲过 target 一大截：合书段量到 tEnd=4.540 而片段只到 4.458
        // （duration），网格就停在片段之外的姿态上 —— 画面上是一本停在半开的书。开书段
        // 同样冲到 1.000（目标 0.875），只因为 161–490 帧都是摊开驻留才看不出来。
        //
        // 钉到 target 而不是 to：target 已经按 duration 回退过一帧，落在片段内。对合书
        // 而言 f599 与 f600 的姿态一致（都已完全合上），视觉上没有差别。
        controllers.forEach {
            it.setTime(target)
            it.pause()
        }
        // 实体变换也收口到端点。轮询最后一次采样通常停在 0.97~0.99，不收口书会差一点没摆平。
        onOpenness(toOpenness)
    }

    private companion object {
        const val TAG = "BookAnimator"
        const val FPS = 120f

        /**
         * 片段的首帧。controller 的时间轴以此为零点，不是 frame 0 —— 本模型时间轴是
         * 5 → 400，`getDuration()` 实测 3.29166s = (400−5)/120。
         */
        const val CLIP_START_FRAME = 5f

        private fun atFrame(frame: Float) = (frame - CLIP_START_FRAME) / FPS

        // 离线用 UsdSkel 解算蒙皮后顶点、逐帧量包围盒定出的分段
        // （模型 Simple_animated_book，metersPerUnit = 0.01）：
        //
        //   frame   5 → 100  合上驻留，尺寸恒为 0.0279 × 0.2052 × 0.2893
        //   frame 100 → 190  张开
        //   frame 190 → 302  摊开驻留，尺寸恒为 0.4409 × 0.0291 × 0.2893
        //   frame 302 → 400  合拢，400 与 5 完全一致
        //
        // **合上态取 frame 100 而不是 5**：5–100 这 0.79s 完全静止，从 5 起播的话触碰之后
        // 会先卡将近一秒才见动静。从 100 起播，动作立刻开始，而 100 与 5 的姿态一模一样。
        val CLOSED_POSE = atFrame(100f)     // 0.792s
        val OPEN_START = atFrame(100f)      // 0.792s
        val OPEN_END = atFrame(195f)        // 1.583s，比驻留起点 190 多留一点余量
        val CLOSE_START = atFrame(302f)     // 2.475s，合拢刚起步
        val CLOSE_END = atFrame(400f)       // 3.292s ≈ duration，会被回退一帧钳到 3.283

        const val POLL_INTERVAL_MS = 16L
        const val MIN_BUDGET_MS = 500L
    }
}
