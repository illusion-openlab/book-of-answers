package tech.illusion.bookofanswers.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

/** 面板要显示什么。面板本身只有一个，内容随 [BookPhase] 切换。 */
sealed interface PanelContent {
    /** 书还没翻开过时的引导。 */
    data object Prompt : PanelContent

    /** 当前抽到的答案。 */
    data class AnswerText(val text: String) : PanelContent
}

/**
 * 面板底衬用**最厚**的一档毛玻璃，且不叠 alpha。
 *
 * `Material` 的四档（Thin / Regular / Thick / Thickest）控制的是背后内容的模糊程度，
 * Thickest 是能拿到的最实的一档 —— 背景被糊到基本看不出内容，文字因此清楚。
 *
 * 边缘仍然是一条清晰轮廓，没有羽化。试过叠多层做软边，不行：`backgroundMaterial`
 * 每层都会对「背后已经模糊过的结果」再采样一次，层与层的边界会显形成可见接缝，
 * 而且逐帧不稳定。真正的软边需要 alpha 渐变遮罩，那要 `Brush` + `Modifier.background`，
 * 而它们所在的 `compose.ui-graphics` / `compose.foundation` 正是本项目排除掉的。
 *
 * 所以柔和感只靠胶囊形 —— 矩形的四个直角是「窗口感」最主要的来源。
 */
private val GLASS_STYLE = Material.Thickest

/**
 * 浮在书本上方的答案面板。
 *
 * 视觉上不是一个「窗口」，而是一团边缘化开的毛玻璃，文字浮在它中间。
 *
 * 文案用词是定过的，改动前先看设计文档 4.4.1：用「触碰」不用「点击」，不用「揭晓」。
 */
@Composable
fun AnswerPanel(content: PanelContent, alpha: Float = 1f) {
    Box(
        // 整块淡入淡出。玻璃和文字一起进出，所以 alpha 挂在最外层。
        modifier = Modifier
            .size(PANEL_WIDTH, PANEL_HEIGHT)
            // 顺序要紧：alpha 会新建图层，夹在 clip 与 backgroundMaterial 之间会把
            // 裁剪链断开、圆角失效。alpha 必须在 clip 之前。
            .alpha(alpha)
            // 底衬必须是**本 Box 的修饰符**，不能做成同级的兄弟 Box —— 在空间容器里
            // 兄弟会渲染到文字前面，于是文字变成「透过玻璃看」，材质一调实就被完全挡住。
            .clip(RoundedCornerShape(PANEL_HEIGHT / 2))
            .backgroundMaterial(true, GLASS_STYLE),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(PANEL_PADDING),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (content) {
                PanelContent.Prompt -> {
                    Text(
                        text = "心中默念你的问题",
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "然后触碰这本书",
                        color = PicoTheme.colorScheme.labelSecondary,
                        style = PicoTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        // 不再叠 alpha：labelSecondary 本身就是次级角色，
                        // 再乘一个 0.75 会淡到看不清。
                        modifier = Modifier.padding(top = SUBTITLE_GAP),
                    )
                }

                is PanelContent.AnswerText -> {
                    Text(
                        text = content.text,
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// 面板宽度按语料实测的最长 19 字定，避免答案换行。
private val PANEL_WIDTH = 700.dp
private val PANEL_HEIGHT = 190.dp
private val PANEL_PADDING = 32.dp
private val SUBTITLE_GAP = 12.dp
