package com.illusion.bookofanswers.content

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.illusion.bookofanswers.data.AnswerSource
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture

private const val TAG = "HomeVolume"

private const val ANSWER_PANEL_ID = "answer_panel"

/**
 * 答案面板在 volume 局部坐标系里的落位（不是相对书本实体的偏移 —— 面板实体是 volume 的
 * 直接子节点，`setPosition` 写的就是 volume 局部坐标）。设备定标值，见 Task 9 Step 6。
 */
private val PANEL_POSITION = Vector3(0f, 0.13f, 0.16f)

/**
 * 「组合已销毁」的一格可变盒子。
 *
 * 用普通类而不是 `MutableState` / 原子量是刻意的：读写全在主线程，既不需要快照订阅，也不该
 * 让读者以为这里有跨线程可见性问题。用途见 [HomeVolume] 里 `initial` 的迟到加载分支。
 */
private class DisposalFlag(var disposed: Boolean = false)

/**
 * 整个 app 的唯一一屏：一本合着的书 + 悬在它上方的答案面板。
 *
 * 装配约定，改动前务必先读：
 *
 * - [AnswerSource.load] **必须留在 `remember` 里，不能挪进协程。** 它内部是宽口径的
 *   `catch (Throwable)`，放到 `LaunchedEffect` / `initial` 里会把 `CancellationException`
 *   一起吞掉。它被刻意写成非 suspend 就是为此。
 * - `pointerInput` 的 key **必须是 `scene` 而非 `Unit`。** 书本异步加载，用 `Unit` 的话
 *   `TargetEntity` 闭包会永久停在首次组合时的 `null`。**「target 为 null ⇒ 点哪儿都能触发」
 *   是设备上观察到的行为，不是文档结论** —— `detectSpatialTapGesture` 在 api-reference 里
 *   查不到，它对 null target 的语义没有可引用的出处。不过修法与机制无关：keyed on `scene`
 *   之后闭包总能读到最新的实体，无论 null 的语义是「全命中」还是「不命中」都正确。
 * - 传给 [loadBookScene] 的 scope **必须主线程受限**：[BookAnimator] 不切 dispatcher，
 *   直接在该 scope 上投递回调，而 [BookState.phase] 是无同步的普通 `var` 且做
 *   check-then-act。`rememberCoroutineScope()` 满足这一条，不要包装它或切 dispatcher。
 */
@Composable
fun HomeVolume() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AnswerSource.load(context) }

    var panelContent by remember { mutableStateOf<PanelContent>(PanelContent.Prompt) }
    var scene by remember { mutableStateOf<BookScene?>(null) }
    var bookState by remember { mutableStateOf<BookState?>(null) }

    val disposal = remember { DisposalFlag() }

    DisposableEffect(Unit) {
        onDispose {
            disposal.disposed = true
            scene?.close()
            scene = null
        }
    }

    SpatialView(
        Modifier.pointerInput(scene) {
            detectSpatialTapGesture(
                context,
                scene?.entity?.let { TargetEntity.hit(it) },
            ) { tap ->
                Log.i(TAG, "tap kind=${tap.interactionKind}")
                bookState?.onTap()
            }
        },
        initial = { content, attachments ->
            // 面板先挂，**再**去 load 那个 ~4 MB 的模型。顺序反过来的话，volume 在整个加载
            // 期间是全空的，邀请语「心中默念你的问题 / 然后触碰这本书」反而最后才到 —— 那
            // 正是 app 的第一印象。面板落位不变，仍是 PANEL_POSITION。
            //
            // 那段空窗有多长：模拟器上实测 `AnswerSource: loaded …` 到 `BookScene: book loaded`
            // 相隔 0.98 / 1.94 / 1.95 s（三次热启动），并发录屏拖慢时到过 8.12 s。
            //
            // **这条改动在本机模拟器上看不出效果，别据此判断它没用。** 该模拟器把 volumetric
            // 窗口合成出来要 ~4 s，比模型加载完还晚（t+2.2–4.0 s 的截图里整个窗口都还没出现），
            // 所以中间态在这里根本不可见。真机上窗口呈现快得多，那 1–2 s 才是用户会看到的。
            //
            // 挪到这里同时消掉了一个小隐患：此处位于 loadBookScene 之前，本 lambda 还没有
            // 任何挂起点，所以挂面板这件事不可能落到 onDispose 之后。
            attachments.entity(id = ANSWER_PANEL_ID)?.apply {
                components[TransformComponent::class.java]?.setPosition(PANEL_POSITION)
                content.addEntity(this)
            }

            val loaded = loadBookScene(scope)

            // 竞态收尾。`loadSuspend` 恢复之后本 lambda 再无挂起点，于是存在这样一个窗口：
            // onDispose 已经整段跑完（它读到的 scene 还是 null，什么都没 close），而这里
            // 照旧把 scene 赋回去 —— animator 手里的 AnimationPlaybackController 就永远
            // 没人 close 了。只在销毁与加载正好交叠时发生，一次性、不累积，但确实是泄漏。
            // 所以在 addEntity 之前再查一次销毁标记，这条迟到路径自己 close 掉整个 scene ——
            // 现在 BookScene.close() 里带上了 Entity.destroy()，所以收掉的既有 controller
            // 也有实体本身。这条路径上实体从没进过 content，容器拆除兜不到它，只能这样收。
            if (disposal.disposed) {
                loaded?.close()
                Log.i(TAG, "book scene finished loading after dispose, closed it")
                return@SpatialView
            }

            if (loaded == null) {
                panelContent = PanelContent.AnswerText("书没能翻开")
                Log.e(TAG, "book scene unavailable")
            } else {
                content.addEntity(loaded.entity)
                scene = loaded

                val animator = loaded.animator
                bookState = BookState(
                    openBook = { onDone ->
                        if (animator != null) animator.open(onDone) else onDone()
                    },
                    closeThenOpen = { onSwap, onDone ->
                        if (animator != null) {
                            animator.closeThenOpen(onSwap, onDone)
                        } else {
                            onSwap()
                            onDone()
                        }
                    },
                    drawAnswer = {
                        panelContent = PanelContent.AnswerText(repository.next().text)
                    },
                )
            }
        },
        attachments = {
            AttachmentPanel(id = ANSWER_PANEL_ID) {
                AnswerPanel(panelContent)
            }
        },
    )
}
