package com.ripple.script.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 无缝动效系统（Adaptive Motion）
 *
 * 设计原则：
 * - 无缝过渡：跨模块一体化绘制，元素无缝展开
 * - "从哪来、到哪去"：页面过渡连贯，无黑屏/跳变
 * - 呼吸反馈：按钮/卡片按压有呼吸感缩放
 * - 高阻尼弹簧：跟手动效更跟手，物理感更强
 */
object Motion {

    /** 时长（ms）—— 紧凑跟手 */
    object Duration {
        const val INSTANT = 0
        const val SHORT = 120        // 涟漪、按压反馈（高阻尼跟手）
        const val MEDIUM = 240       // 卡片展开、状态切换
        const val LONG = 360         // 页面过渡（无缝）
        const val EXTRA_LONG = 500   // 复杂过渡
    }

    /**
     * 无缝缓动曲线
     *
     * - EmphasizedDecelerate：进入动画，先快后慢，自然减速
     * - EmphasizedAccelerate：退出动画，先慢后快，快速收起
     * - Seamless：无缝动画核心曲线（几乎无拐点，平滑流动）
     */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.15f, 0.7f, 0.2f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.4f, 0f, 0.8f, 0.15f)
    val Seamless: Easing = CubicBezierEasing(0.22f, 0.02f, 0f, 1f)
    val Smooth: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val Enter: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val Exit: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    /**
     * 响应式弹簧配置
     *
     * 比传统 Spring 更"弹"，物理感更强
     */
    object Springs {
        /** 按压缩放：跟手，轻微呼吸回弹 */
        fun <T> pressScale() = spring<T>(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessHigh
        )
        /** 卡片弹出：强弹，强调物理感 */
        fun <T> cardPop() = spring<T>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
        /** 列表项重排：无缝 */
        fun <T> listItemReorder() = spring<T>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
        /** 颜色/数值过渡：平滑 */
        fun <T> seamless() = spring<T>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
        /** 呼吸：慢弹，呼吸感 */
        fun <T> breathing() = spring<T>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessVeryLow
        )
    }
}
