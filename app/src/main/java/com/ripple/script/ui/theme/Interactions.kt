package com.ripple.script.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 响应式交互反馈（Adaptive Interactions）
 *
 * 设计灵感：
 * - 光线折射：拖动/点击时产生光线般反馈
 * - 呼吸：按钮/卡片按压缩放有呼吸感
 */

/**
 * 兼容别名：首页旧代码通过 [BounceClick] 导入时使用
 */
object BounceClick

/**
 * 响应式按压反馈（替代旧版 bounceClick）
 *
 * 按下时缩放到 0.96f，松开后弹性回弹到 1f，模拟无缝弹簧质感。
 * 保留系统 ripple。
 */
fun Modifier.bounceClick(
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motion.Springs.pressScale(),
        label = "press-scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = onClick
        )
}

/**
 * 响应式按压 + 长按/双击 组合
 *
 * 比 bounceClick 缩放更深（0.95f），用于需要更强反馈的交互
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.bounceCombinedClick(
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = Motion.Springs.cardPop(),
        label = "combined-scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onLongClick = onLongClick,
            onDoubleClick = onDoubleClick,
            onClick = onClick
        )
}

/**
 * 呼吸效果
 *
 * 非交互态也有轻微呼吸缩放，模拟物体的自然"微动"
 * 用于 Hero 卡、环形进度等装饰性元素
 */
@Composable
fun Modifier.breathing(enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motion.Springs.seamless(),
        label = "breathing"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}
