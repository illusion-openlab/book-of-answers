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
private val PANEL_OFFSET = Vector3(0f, 0.13f, 0.16f)

/**
 * 整个 app 的唯一一屏：一本合着的书 + 悬在它上方的答案面板。
 *
 * 装配约定，改动前务必先读：
 *
 * - [AnswerSource.load] **必须留在 `remember` 里，不能挪进协程。** 它内部是宽口径的
 *   `catch (Throwable)`，放到 `LaunchedEffect` / `initial` 里会把 `CancellationException`
 *   一起吞掉。它被刻意写成非 suspend 就是为此。
 * - `pointerInput` 的 key **必须是 `scene` 而非 `Unit`。** 书本异步加载，用 `Unit` 的话
 *   `TargetEntity` 闭包会永久停在首次组合时的 `null`，命中范围静默失效 —— 不报错，只是
 *   点哪儿都能触发。
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

    DisposableEffect(Unit) {
        onDispose {
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
            val loaded = loadBookScene(scope)
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

            attachments.entity(id = ANSWER_PANEL_ID)?.apply {
                components[TransformComponent::class.java]?.setPosition(PANEL_OFFSET)
                content.addEntity(this)
            }
        },
        attachments = {
            AttachmentPanel(id = ANSWER_PANEL_ID) {
                AnswerPanel(panelContent)
            }
        },
    )
}
