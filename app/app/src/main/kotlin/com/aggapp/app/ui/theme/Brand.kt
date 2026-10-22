package com.aggapp.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 品牌设计令牌（2026-10-21 UI 重构）：靛蓝主色 + 蓝青渐变 + 琥珀点缀 */
object Brand {
    val Indigo = Color(0xFF4F5BD5)
    val Violet = Color(0xFF8A5BD5)
    val Amber = Color(0xFFFFA43D)
    val IndigoDeep = Color(0xFF2E3699)
    // 头部渐变（2026-10-21 换色）：贴合应用图标的蓝→青
    val Blue = Color(0xFF2E7CF6)
    val Cyan = Color(0xFF19C0F2)

    /** 品牌渐变（头部区/按钮/图标同源） */
    val Gradient = Brush.horizontalGradient(listOf(Blue, Cyan))
    val GradientVertical = Brush.verticalGradient(listOf(Blue, Cyan))

    /** 各平台品牌色（胶囊选中底色、登录态标识） */
    val PlatformColor = mapOf(
        com.aggapp.core.model.Platform.PDD to Color(0xFFE02E24),
        com.aggapp.core.model.Platform.TAOBAO to Color(0xFFFF6A00),
        com.aggapp.core.model.Platform.JD to Color(0xFFE1251B),
        com.aggapp.core.model.Platform.SF to Color(0xFF00396F),
        com.aggapp.core.model.Platform.RYLL to Color(0xFF00A0E9),
    )
}
