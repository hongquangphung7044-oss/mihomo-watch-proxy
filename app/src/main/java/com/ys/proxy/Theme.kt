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
 * Wear Material 3 主题。
 *
 * 设计原则:
 *  - 单一深色 ColorScheme(Wear OS 屏幕小 + OLED 省电,几乎只用暗色)
 *  - 颜色按语义映射到 M3 ColorScheme 槽位,UI 全部走 [MaterialTheme.colorScheme]
 *  - Typography 复用现有字号(10–21sp,适配圆形小屏),不做激进放大
 *  - 状态指示色(Good/Warning/Bad)保留为本地语义色,不并入 ColorScheme,
 *    因为它们对应"成功/警告/错误"三种业务状态,跨槽位复用会混淆语义
 *
 * 视觉标识延续前一版本的蓝青色调(背景深蓝 + 主色青 + 紫调次色),
 * 只是从硬编码颜色彻底迁移到 M3 主题系统,组件默认样式即可统一。
 *
 * 注意:Wear Compose Material 3 的 [ColorScheme] 与标准 [androidx.compose.material3]
 * 不同 —— 没有 `surface` / `surfaceVariant` / `surfaceContainerHighest` / `scrim`,
 * 取而代之是三档 `surfaceContainerLow/Container/ContainerHigh` + Wear 专属的
 * `*Dim`(primaryDim/secondaryDim/tertiaryDim/errorDim,用于 OLED 省电的暗化态)。
 * 也没有 `darkColorScheme()` 工厂,必须直接构造 [ColorScheme]。
 */

// ─────────────────────────────────────────────────────────────────────────────
// Color tokens — 直接对应旧版硬编码色板,语义化重排到 M3 槽位
// ─────────────────────────────────────────────────────────────────────────────
private val MihomoBackground = Color(0xFF071427)        // 旧 AppBackground
private val MihomoSurface = Color(0xFF102842)            // 旧 Panel(默认 surface)
private val MihomoSurfaceHigh = Color(0xFF163A5D)       // 旧 PanelRaised(抬高态)
private val MihomoSecondaryContainer = Color(0xFF174863) // 旧 PanelSelected(选中态)
private val MihomoPrimary = Color(0xFF55D8FF)           // 旧 Accent(青)
private val MihomoPrimaryDim = Color(0xFF1A6B85)        // OLED 暗化态(青)
private val MihomoOnPrimary = Color(0xFF002B3B)
private val MihomoPrimaryContainer = Color(0xFF1E5C82)
private val MihomoOnPrimaryContainer = Color(0xFFCDEBFF)
private val MihomoSecondary = Color(0xFFB5A2FF)         // 旧 AccentSoft(紫)
private val MihomoSecondaryDim = Color(0xFF3D2F66)     // OLED 暗化态(紫)
private val MihomoOnSecondary = Color(0xFF1B1240)
private val MihomoOnSecondaryContainer = Color(0xFFE6DEFF)
private val MihomoTertiary = Color(0xFF67E6AC)          // 旧 Good(运行状态)
private val MihomoTertiaryDim = Color(0xFF1A4D2C)      // OLED 暗化态(绿)
private val MihomoOnTertiary = Color(0xFF003822)
private val MihomoTertiaryContainer = Color(0xFF1F4D34)
private val MihomoOnTertiaryContainer = Color(0xFFB4F4D3)
private val MihomoOnSurface = Color(0xFFF2F6FC)         // 旧 OnSurface
private val MihomoOnSurfaceVariant = Color(0xFFB8C7DB)  // 旧 Muted
private val MihomoOutline = Color(0xFF5A6F88)
private val MihomoOutlineVariant = Color(0xFF2C3E55)
private val MihomoError = Color(0xFFFF8492)             // 旧 Bad
private val MihomoErrorDim = Color(0xFF5C1A24)         // OLED 暗化态(红)
private val MihomoOnError = Color(0xFF5C0014)
private val MihomoErrorContainer = Color(0xFF5C1A24)
private val MihomoOnErrorContainer = Color(0xFFFFDDE0)

/**
 * 业务状态指示色。
 *
 * Good=运行正常 / Warning=需注意 / Bad=错误。
 * 不并入 [MaterialTheme.colorScheme],因为它们是业务状态而非 UI 主题色,
 * 跨 surface 复用会混淆语义(例如 Bad 不一定等于 error container)。
 */
val StatusGood = Color(0xFF67E6AC)
val StatusWarning = Color(0xFFFFCB66)
val StatusBad = Color(0xFFFF8492)

/**
 * 单一深色 ColorScheme。
 *
 * Wear OS 默认就是深色场景,不需要 lightColorScheme 分支。
 * Wear M3 没有 `darkColorScheme()` 工厂,直接构造 [ColorScheme],
 * 必须传齐 29 个颜色槽位(primaryDim/secondaryDim/tertiaryDim/errorDim 是 Wear 专属)。
 *
 * 调用方拿 [MaterialTheme.colorScheme] 即可,无需关心具体色值。
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
    surfaceContainerLow = MihomoSurface,
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
// Typography — 保留旧版字号(适配圆形小屏),只做语义化映射
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 主题级 Typography。
 *
 * Wear M3 [Typography] 没有 `headlineSmall`(那是标准 M3 的),
 * 这里的可用槽位:displayLarge/Medium/Small、titleLarge/Medium/Small、
 * bodyLarge/Medium/Small/ExtraSmall、labelLarge/Medium/Small、
 * 以及 Wear 专属的 numeralXxx + arcXxx(此处保留默认)。
 *
 * 直接覆盖 Wear M3 默认字号(默认偏大,圆形表盘上会溢出),
 * 用旧版实测过的尺寸:
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
 * 强制走 [MihomoColorScheme](忽略系统 dark/light,Wear OS 上只支持暗色),
 * 这样所有 [androidx.wear.compose.material3] 组件的默认 colors 都从主题取,
 * 不需要在每个组件上手动传 [androidx.wear.compose.material3.CardDefaults.cardColors] 之类的参数。
 */
@Composable
fun MihomoTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme() 在 Wear OS 上恒为 true,这里仅作显式语义标记。
    // 即便未来想加 lightColorScheme 分支,也只需在此处扩展。
    @Suppress("UNUSED_VARIABLE")
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = MihomoColorScheme,
        typography = MihomoTypography,
        content = content
    )
}
