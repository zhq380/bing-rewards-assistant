package com.ripple.script.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ================ 自适应响应式配色 ================
// 设计理念：
//   · 现代、通透、温暖的视觉体验
//   · 渐变模拟光线折射
//   · 高对比度确保可读性

// —— 基础色（浅色，暖灰基底）——
val Primary = Color(0xFF2C5BE5)          // 主蓝（通透）
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFE1EBFF)
val OnPrimaryContainer = Color(0xFF0D2870)

val Background = Color(0xFFF5F6FA)        // 原生背景（微暖灰，带光感）
val OnBackground = Color(0xFF111418)
val Surface = Color(0xFFFFFFFF)
val OnSurface = Color(0xFF111418)
val SurfaceVariant = Color(0xFFEEF0F5)
val OnSurfaceVariant = Color(0xFF545B6A)
val Outline = Color(0xFFD6D9E2)
val OutlineVariant = Color(0xFFE5E8EF)
val ErrorRed = Color(0xFFF0574B)
val OnError = Color.White

// —— 功能色（高饱和但不刺眼）——
val SuccessGreen = Color(0xFF22B468)
val SuccessContainer = Color(0xFFD6F4E2)
val WarningOrange = Color(0xFFF5871C)
val WarningContainer = Color(0xFFFFE5C9)
val DangerRed = ErrorRed
val InfoCyan = Color(0xFF0BA2B2)

// —— 深色（深邃，微光面）——
val PrimaryDark = Color(0xFF91B1FF)
val OnPrimaryDark = Color(0xFF0A1E50)
val PrimaryContainerDark = Color(0xFF1E3A88)
val OnPrimaryContainerDark = Color(0xFFD5E2FF)

val BackgroundDark = Color(0xFF090A0E)
val OnBackgroundDark = Color(0xFFE5E7EE)
val SurfaceDark = Color(0xFF141519)
val OnSurfaceDark = Color(0xFFE5E7EE)
val SurfaceVariantDark = Color(0xFF202228)
val OnSurfaceVariantDark = Color(0xFFA3ACBD)
val OutlineDark = Color(0xFF3B4150)

// ================ 渐变组 ================
// 柔和过渡，模拟光线折射

/** 蓝渐变：上浅下深，模拟天光 */
val GradientHeroBlue = Brush.linearGradient(
    0.0f to Color(0xFF7EA0FF),
    0.45f to Color(0xFF4474F2),
    1.0f to Color(0xFF1E4BC4)
)
/** 紫渐变：更通透 */
val GradientHeroPurple = Brush.linearGradient(
    0.0f to Color(0xFFB596FF),
    0.5f to Color(0xFF8A6BFE),
    1.0f to Color(0xFF6A46D6)
)
/** 绿渐变：清新自然 */
val GradientHeroGreen = Brush.linearGradient(
    0.0f to Color(0xFF6FD8A6),
    0.5f to Color(0xFF2DBF74),
    1.0f to Color(0xFF18A05C)
)
/** 橙渐变：温暖阳光感 */
val GradientHeroOrange = Brush.linearGradient(
    0.0f to Color(0xFFFFC285),
    0.5f to Color(0xFFFF8E22),
    1.0f to Color(0xFFEE7508)
)

/** 浅色按钮主渐变（纵向） */
val GradientButtonPrimary = Brush.verticalGradient(
    0.0f to Color(0xFF5E8EFF),
    1.0f to Color(0xFF2C5BE5)
)

/** 表面微高光渐变（用于卡片顶部，模拟照射） */
val SurfaceHighlight = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.6f),
    0.4f to Color.White.copy(alpha = 0.0f)
)

// ================ 步骤徽章色 ================
val BadgeBlue = Color(0xFF2C5BE5)
val BadgeGreen = SuccessGreen
val BadgeOrange = WarningOrange
val BadgePurple = Color(0xFF8A6BFE)
val BadgePink = Color(0xFFFF5D8F)
val BadgeCyan = InfoCyan
val BadgeGray = Color(0xFF7E8698)
val BadgeRed = ErrorRed

// ================ 投影阴影（用于 elevation） ================
/** 卡片投影：柔和、大面积 */
val CardShadowLight = Brush.radialGradient(
    colors = listOf(Color(0x14000000), Color(0x00000000)),
    radius = 60f
)

/** 按钮投影：紧凑，点击反馈 */
val ButtonShadow = Brush.radialGradient(
    colors = listOf(Color(0x1A000000), Color(0x00000000)),
    radius = 40f
)

/** 顶部高光条（模拟光线照射在卡片上边缘） */
val TopHighlight = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.35f),
    1.0f to Color.White.copy(alpha = 0.0f)
)
