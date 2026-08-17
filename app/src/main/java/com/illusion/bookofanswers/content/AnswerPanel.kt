package com.illusion.bookofanswers.content

import androidx.compose.foundation.layout.Arrangement
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
 * 浮在书页上方的答案面板。
 *
 * 文案用词是定过的，改动前先看设计文档 4.4.1：用「触碰」不用「点击」，不用「揭晓」。
 */
@Composable
fun AnswerPanel(content: PanelContent) {
    Column(
        modifier = Modifier
            .size(PANEL_WIDTH, PANEL_HEIGHT)
            .clip(RoundedCornerShape(CORNER_RADIUS))
            .backgroundMaterial(true, Material.Regular)
            .padding(PANEL_PADDING),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (content) {
            PanelContent.Prompt -> {
                Text(
                    text = "心中默念你的问题",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "然后触碰这本书",
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = SUBTITLE_GAP)
                        .alpha(SUBTITLE_ALPHA),
                )
            }

            is PanelContent.AnswerText -> {
                Text(
                    text = content.text,
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// 面板宽度按语料实测的最长 19 字定，避免答案换行。
private val PANEL_WIDTH = 760.dp
private val PANEL_HEIGHT = 220.dp
private val PANEL_PADDING = 32.dp
private val CORNER_RADIUS = 48.dp
private val SUBTITLE_GAP = 12.dp
private const val SUBTITLE_ALPHA = 0.75f
