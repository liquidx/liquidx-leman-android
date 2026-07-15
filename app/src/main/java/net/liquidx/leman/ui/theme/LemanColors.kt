package net.liquidx.leman.ui.theme

import androidx.compose.ui.graphics.Color

/** Exactly the handoff tokens (spec 06 / design system). */
object LemanColors {
    val base = Color(0xFF08080A)
    val surface = Color(0xFF0C0C10)
    val accent = Color(0xFF6BA0D8)
    val accentMuted = Color(0xFF5B7EA6)
    val textPrimary = Color(0xFFE6E6EA)
    val textSecondary = Color(0xFF9A9AA4)
    val textTertiary = Color(0xFF7A7A84)
    val textFaint = Color(0xFF54545E)
    val gutter = Color(0xFF3F3F48)
    val warn = Color(0xFFD0A24C)
    val danger = Color(0xFFD06A6A)
    val diffGreen = Color(0xFF6BB08A)
    val hairlineFaint = Color(0x0DFFFFFF)   // rgba(255,255,255,.05)
    val hairline = Color(0x17FFFFFF)        // .09
    val hairlineStrong = Color(0x24FFFFFF)  // .14
    val accentTint = Color(0x1A6BA0D8)      // accent @ .1 fill
    val accentBorderSoft = Color(0x806BA0D8) // accent @ .5 (avatar border)
    val accentFillSoft = Color(0x146BA0D8)   // accent @ .08 (avatar fill)
    val agentBorder = Color(0x1FFFFFFF)     // rgba(255,255,255,.12) agent turn border
    val pressedOverlay = Color(0x0AFFFFFF)  // rgba(255,255,255,.04)
    val tabInactive = Color(0xFF5C5C66)
    val pinIdle = Color(0xFF2A2A32)
    val agentNameDim = Color(0xFF8A8A94)
    val labelHeader = Color(0xFF6A6A74)
    val dangerBorder = Color(0x66D06A6A)    // rgba(208,106,106,.4)
}
