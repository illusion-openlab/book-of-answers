package tech.illusion.bookofanswers.content

enum class BookPhase { Closed, Opening, Open, Closing }

/**
 * 触碰 → 开 / 合 的状态机。
 *
 * ```
 * Closed ──触碰──> Opening ──动画完成──> Open ──触碰──> Closing ──动画完成──> Closed ──> …
 * ```
 *
 * 合上不是"结束"，而是"等你提问"：回到 [Closed] 时把提示文案放回去，下一次触碰再抽
 * 一条新答案。所以「开了几次」就等于「抽了几条」，合上本身不消耗语料。
 *
 * 动画与内容替换都以回调注入，因此本类不依赖 Spatial SDK / Android / Compose，
 * 可独立单测。状态由本类持有，**不通过 Compose recomposition 驱动 3D**。
 *
 * 线程契约：所有回调与 [onTap] 都在主线程。[phase] 是无同步的 `var`，`onTap` 是
 * check-then-act，加 `@Volatile` 不会让它变原子，只会制造虚假安全感 —— 正确性靠
 * 调用方保证主线程投递，不靠这里加锁。
 */
class BookState(
    private val openBook: (onDone: () -> Unit) -> Unit,
    private val closeBook: (onDone: () -> Unit) -> Unit,
    private val drawAnswer: () -> Unit,
    private val showPrompt: () -> Unit,
) {
    var phase: BookPhase = BookPhase.Closed
        private set

    fun onTap() {
        when (phase) {
            BookPhase.Closed -> {
                phase = BookPhase.Opening
                // 抽答案放在动画结束时，不放在开头：此刻面板已经淡到全透明，
                // 文案替换看不见，淡入时呈现的就是新答案。
                openBook {
                    drawAnswer()
                    phase = BookPhase.Open
                }
            }

            BookPhase.Open -> {
                phase = BookPhase.Closing
                closeBook {
                    showPrompt()
                    phase = BookPhase.Closed
                }
            }

            // 动画进行中忽略输入。不加这道闸，连续触碰会把动画打断成一团乱。
            BookPhase.Opening, BookPhase.Closing -> Unit
        }
    }
}
