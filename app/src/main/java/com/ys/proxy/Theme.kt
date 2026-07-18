package com.ys.proxy

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography

/**
 * Wear Material 3 主题 —— Mihomo 手表代理。
 *
 * 设计原则(按用户反馈 v3 最终版):
 *  - 完全移除 dynamicColorScheme(在三星手表上闪退)
 *  - 背景:纯黑 #000000(OLED 省电,用户明确不要蓝灰底)
 *  - 主色:暖琥珀金 #F5C45E(延续图标辨识度,深底上对比强烈)
 *  - 次色:暖橙 #E8945C(与主色同色系,选中态用,不引入冷色)
 *  - 三色:薄荷绿 #6FE3A8(运行成功态)
 *  - Surface 三档递进:从纯黑略微提亮,营造层级感
 *  - 状态色(Good/Warning/Bad)保留为顶层语义色,不并入 ColorScheme,
 *    因为它们对应"成功/警告/错误"三种业务状态,跨槽位复用会混淆语义
 *  - Typography 字号沿用旧版实测值(10–21sp),适配圆形小屏
 *
 * 注意:Wear Compose Material 3 的 [ColorScheme] 与标准 [androidx.compose.material3]
 * 不同 —— 没有 `surface` / `surfaceVariant` / `surfaceContainerHighest` / `scrim`,
 * 取而代之是三档 `surfaceContainerLow/Container/ContainerHigh` + Wear 专属的
 * `*Dim`(primaryDim/secondaryDim/tertiaryDim/errorDim,用于 OLED 省电的暗化态)。
 * 也没有 `darkColorScheme()` 工厂,必须直接构造 [ColorScheme]。
 */

// ─────────────────────────────────────────────────────────────────────────────
// Color tokens —— 暖色调(琥珀金 + 暖橙 + 薄荷绿)+ 纯黑背景
// ─────────────────────────────────────────────────────────────────────────────

// 背景:纯黑(OLED 省电)
private val MihomoBackground = Color(0xFF000000)

// Surface 三档:层级递进,从纯黑略微提亮
private val MihomoSurfaceLow = Color(0xFF161616)      // 默认卡片背景(略高于纯黑)
private val MihomoSurface = Color(0xFF1F1F1F)         // 中层卡片
private val MihomoSurfaceHigh = Color(0xFF2A2A2A)     // 抬高层(输入框、内嵌容器)

// 主色:暖琥珀金(延续图标辨识度,深底上对比强烈)
private val MihomoPrimary = Color(0xFFF5C45E)
private val MihomoPrimaryDim = Color(0xFF7A5A1A)       // OLED 暗化态
private val MihomoOnPrimary = Color(0xFF1A1000)       // 深棕(在琥珀金上)
private val MihomoPrimaryContainer = Color(0xFF3D2E0F) // 暗琥珀容器
private val MihomoOnPrimaryContainer = Color(0xFFFFE9B0) // 浅琥珀(在暗容器上)

// 次色:暖橙(与主色同色系,用于"选中"等次级语义)
private val MihomoSecondary = Color(0xFFE8945C)
private val MihomoSecondaryDim = Color(0xFF5C3D1A)
private val MihomoOnSecondary = Color(0xFF1A0A00)
private val MihomoSecondaryContainer = Color(0xFF3D2410)  // 选中卡片背景(深橙)
private val MihomoOnSecondaryContainer = Color(0xFFFFD9B0)

// 三色:薄荷绿(成功状态对应的色系)
private val MihomoTertiary = Color(0xFF6FE3A8)
private val MihomoTertiaryDim = Color(0xFF1A4D2C)
private val MihomoOnTertiary = Color(0xFF003822)
private val MihomoTertiaryContainer = Color(0xFF1F4D34)
private val MihomoOnTertiaryContainer = Color(0xFFB4F4D3)

// 文字色:onSurface 主文 / onSurfaceVariant 次文
private val MihomoOnSurface = Color(0xFFF2F2F2)
private val MihomoOnSurfaceVariant = Color(0xFFB0B0B0)

// 描边
private val MihomoOutline = Color(0xFF6A6A6A)
private val MihomoOutlineVariant = Color(0xFF3A3A3A)

// 错误色:红
private val MihomoError = Color(0xFFFF7A85)
private val MihomoErrorDim = Color(0xFF5C1A24)
private val MihomoOnError = Color(0xFF000000)
private val MihomoErrorContainer = Color(0xFF5C1A24)
private val MihomoOnErrorContainer = Color(0xFFFFDDE0)

/**
 * 业务状态指示色。
 *
 * Good=运行正常(绿) / Warning=需注意(橙) / Bad=错误(红)。
 * 不并入 [MaterialTheme.colorScheme],因为它们是业务状态而非 UI 主题色,
 * 跨 surface 复用会混淆语义(例如 Bad 不一定等于 error container)。
 *
 * 这三种色为全局通用,无论主题如何变化,都使用这套固定语义色,
 * 保证"成功/警告/错误"在视觉上始终清晰可辨。
 */
val StatusGood = Color(0xFF6FE3A8)
val StatusWarning = Color(0xFFFFB547)
val StatusBad = Color(0xFFFF7A85)

/**
 * 单一深色 ColorScheme。
 *
 * Wear OS 默认就是深色场景,不需要 lightColorScheme 分支。
 * Wear M3 没有 `darkColorScheme()` 工厂,直接构造 [ColorScheme],
 * 必须传齐 29 个颜色槽位(primaryDim/secondaryDim/tertiaryDim/errorDim 是 Wear 专属)。
 */
private val MihomoColorScheme = ColorScheme(
    primary = MihomoPrimary,
    primaryDim = MihomoPrimaryDim,
    primaryContainer = MihomoPrimaryContainer,
    onPrimary = MihomoOnPrimary,
    onPrimaryContainer = MihomoOnPrimaryContainer,
    secondary = MihomoSecondary,
    secondaryDim = MihomoSecondaryDim,
    secondaryContainer = MihomoSecondaryContainer,
    onSecondary = MihomoOnSecondary,
    onSecondaryContainer = MihomoOnSecondaryContainer,
    tertiary = MihomoTertiary,
    tertiaryDim = MihomoTertiaryDim,
    tertiaryContainer = MihomoTertiaryContainer,
    onTertiary = MihomoOnTertiary,
    onTertiaryContainer = MihomoOnTertiaryContainer,
    surfaceContainerLow = MihomoSurfaceLow,
    surfaceContainer = MihomoSurface,
    surfaceContainerHigh = MihomoSurfaceHigh,
    onSurface = MihomoOnSurface,
    onSurfaceVariant = MihomoOnSurfaceVariant,
    outline = MihomoOutline,
    outlineVariant = MihomoOutlineVariant,
    background = MihomoBackground,
    onBackground = MihomoOnSurface,
    error = MihomoError,
    errorDim = MihomoErrorDim,
    errorContainer = MihomoErrorContainer,
    onError = MihomoOnError,
    onErrorContainer = MihomoOnErrorContainer,
)

// ─────────────────────────────────────────────────────────────────────────────
// Typography —— 保留旧版字号(适配圆形小屏),只做语义化映射
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 主题级 Typography。
 *
 * Wear M3 [Typography] 没有 `headlineSmall`(那是标准 M3 的),
 * 这里的可用槽位:displayLarge/Medium/Small、titleLarge/Medium/Small、
 * bodyLarge/Medium/Small/ExtraSmall、labelLarge/Medium/Small、
 * 以及 Wear 专属的 numeralXxx + arcXxx(此处保留默认)。
 *
 * 字号沿用旧版实测值(10–21sp),适配圆形小屏:
 *  - displaySmall = 21sp  — 大标题(如 "Mihomo")
 *  - titleLarge   = 17sp  — 对话框标题
 *  - titleMedium  = 15sp  — 主操作按钮文字
 *  - titleSmall   = 13sp  — 面板标题、节点名
 *  - bodyLarge    = 13sp  — 正文
 *  - bodyMedium   = 12sp  — 订阅链接标签、二级按钮
 *  - bodySmall    = 11sp  — 副标题、章节标签、辅助说明
 *  - labelLarge   = 15sp  — 主操作按钮(= titleMedium)
 *  - labelMedium  = 11sp  — 操作按钮
 *  - labelSmall   = 10sp  — 节点延迟、删除按钮
 *  - bodyExtraSmall = 10sp — 极小辅助文字(节点延迟小标)
 */
private val MihomoTypography = Typography(
    displayLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    displayMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    displaySmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    bodyExtraSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 13.sp),
)

/**
 * 应用根主题。
 *
 * 完全静态:用 [MihomoColorScheme](暖琥珀金 + 暖橙 + 薄荷绿,纯黑背景)。
 * 不使用 dynamicColorScheme —— 在三星 Galaxy Watch 等设备上会闪退。
 *
 * [isSystemInDarkTheme] 在 Wear OS 上恒为 true,这里仅作显式语义标记。
 */
@Composable
fun MihomoTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = MihomoColorScheme,
        typography = MihomoTypography,
        content = content
    )
}
