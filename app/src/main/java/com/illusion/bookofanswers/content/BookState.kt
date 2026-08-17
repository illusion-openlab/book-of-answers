package com.illusion.bookofanswers.content

enum class BookPhase { Closed, Opening, Revealed, Reshuffling }

/**
 * 触碰 → 开书 / 重抽 的状态机。
 *
 * ```
 * Closed ──触碰──> Opening ──动画完成──> Revealed ──触碰──> Reshuffling ──┐
 *                                          ▲                            │
 *                                          └────────────────────────────┘
 * ```
 *
 * 动画与抽答案都以回调注入，因此本类不依赖 Spatial SDK，可独立单测。
 * 状态由本类持有，**不通过 Compose recomposition 驱动 3D**。
 */
class BookState(
    private val openBook: (onDone: () -> Unit) -> Unit,
    private val closeThenOpen: (onSwap: () -> Unit, onDone: () -> Unit) -> Unit,
    private val drawAnswer: () -> Unit,
) {
    var phase: BookPhase = BookPhase.Closed
        private set

    fun onTap() {
        when (phase) {
            BookPhase.Closed -> {
                phase = BookPhase.Opening
                openBook {
                    drawAnswer()
                    phase = BookPhase.Revealed
                }
            }

            BookPhase.Revealed -> {
                phase = BookPhase.Reshuffling
                closeThenOpen(
                    { drawAnswer() },
                    { phase = BookPhase.Revealed },
                )
            }

            // 动画进行中忽略输入。不加这道闸，连续触碰会把动画打断成一团乱。
            BookPhase.Opening, BookPhase.Reshuffling -> Unit
        }
    }
}
