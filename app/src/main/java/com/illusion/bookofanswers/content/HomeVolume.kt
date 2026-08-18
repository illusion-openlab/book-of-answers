package com.illusion.bookofanswers.content

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.pico.spatial.ui.foundation.gesture.data.InteractionKind
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture

private const val TAG = "HomeVolume"

private const val ANSWER_PANEL_ID = "answer_panel"

/**
 * 答案面板在 volume 局部坐标系里的落位（不是相对书本实体的偏移 —— 面板实体是 volume 的
 * 直接子节点，`setPosition` 写的就是 volume 局部坐标）。设备定标值，见 Task 9 Step 6。
 */
// 书的视觉中心在 BookScene.BOOK_CENTER = (0, -0.2, 0.3)。文字浮在它正上方，
// 所以 x/z 与书对齐，只抬高 y。
private val PANEL_POSITION = Vector3(0f, 0.06f, 0.3f)

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
    // 文字在书animation运动期间隐去，落定后再浮现。淡出比淡入短——消失可以干脆，
    // 出现要给人「浮上来」的感觉。
    var panelVisible by remember { mutableStateOf(true) }
    val panelAlpha by animateFloatAsState(
        targetValue = if (panelVisible) 1f else 0f,
        animationSpec = tween(if (panelVisible) FADE_IN_MS else FADE_OUT_MS),
        label = "panelAlpha",
    )
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
        Modifier
            // 捏、射线、注视、控制器 —— 一切「远程」指向。
            .pointerInput(scene) {
                detectSpatialTapGesture(
                    context,
                    scene?.entity?.let { TargetEntity.hit(it) },
                ) { tap ->
                    Log.i(TAG, "tap kind=${tap.interactionKind}")
                    bookState?.onTap()
                }
            }
            // 食指指尖直接戳到书上。**必须是独立的 pointerInput 块** —— 同一个
            // pointerInput DSL 里不能出现两个 detectSpatial* 调用，它们会争抢同一条
            // 事件流。
            //
            // 为什么 tap 那条路不够：`detectSpatialTapGesture` 给的是「一次完整点击」，
            // 而指尖触碰在 SDK 里走的是 pointer 事件流，`InteractionKind.Poke`。两条路
            // 都留着是刻意的互为兜底 —— [BookState.onTap] 是同步的，返回前相位就已经
            // 推进了，所以同一次触碰即使被两条路都投递一遍，第二遍也会被相位闸吞掉。
            // 日志里带上来源，真机上一眼能看出实际是哪条路生效。
            .pointerInput(scene) {
                detectSpatialPointerEvent(
                    context,
                    scene?.entity?.let { TargetEntity.hit(it) },
                ) { events ->
                    events.forEach { info ->
                        // isDownEvent() 是 changedToDownIgnoreConsumed()，即这根手指的
                        // 按下沿。用它而不是 `pressed`：pressed 在整个接触期间每帧都为
                        // 真，会把一次触碰变成连续触发。左右手是两个不同的 pointerId，
                        // 各自独立产生按下沿，所以「两只手都可以」不需要额外代码。
                        if (info.kind == InteractionKind.Poke && info.isDownEvent()) {
                            Log.i(TAG, "poke down pointerId=${info.pointerId}")
                            bookState?.onTap()
                        }
                    }
                    // 不消费事件：让上面的 tap 检测器照旧收到它自己那份。
                    false
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
                    // 淡出/淡入包在动画两端：BookState 只在触碰被真正接受时才调这两个
                    // 回调（动画途中的触碰会被它的闸拦掉），所以这里不用自己判重。
                    // 文案替换发生在 alpha 已经归零的时刻，看不到硬切。
                    openBook = { onDone ->
                        panelVisible = false
                        if (animator != null) {
                            animator.open { onDone(); panelVisible = true }
                        } else {
                            onDone(); panelVisible = true
                        }
                    },
                    closeBook = { onDone ->
                        panelVisible = false
                        if (animator != null) {
                            animator.closeBook { onDone(); panelVisible = true }
                        } else {
                            onDone(); panelVisible = true
                        }
                    },
                    drawAnswer = {
                        panelContent = PanelContent.AnswerText(repository.next().text)
                    },
                    showPrompt = {
                        panelContent = PanelContent.Prompt
                    },
                )
            }
        },
        attachments = {
            AttachmentPanel(id = ANSWER_PANEL_ID) {
                AnswerPanel(panelContent, alpha = panelAlpha)
            }
        },
    )
}

private const val FADE_OUT_MS = 220
private const val FADE_IN_MS = 520
