package com.ys.proxy

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * Wear Material 3 主题 —— Mihomo 手表代理。
 *
 * 设计原则(按用户反馈 v2 调整):
 *  - 背景:纯黑 #000000(OLED 省电,用户明确不要蓝灰底)
 *  - Android 12+(API 31+)用 [dynamicColorScheme] 跟随系统壁纸取色,
 *    让卡片色与手表主题协调
 *  - Android 12 以下:fallback 到自定义深色方案(仍以纯黑为底,
 *    主色用中性偏白而非饱和色,避免突兀)
 *  - 状态色(Good/Warning/Bad)保留为顶层语义色,不并入 ColorScheme,
 *    因为它们对应"成功/警告/错误"三种业务状态,跨槽位复用会混淆语义
 *  - Typography 字号沿用旧版实测值(10–21sp),适配圆形小屏
 *
 * 注意:Wear Compose Material 3 的 [ColorScheme] 与标准 [androidx.compose.material3]
 * 不同 —— 没有 `surface` / `surfaceVariant` / `surfaceContainerHighest` / `scrim`,
 * 取而代之是三档 `surfaceContainerLow/Container/ContainerHigh` + Wear 专属的
 * `*Dim`(primaryDim/secondaryDim/tertiaryDim/errorDim,用于 OLED 省电的暗化态)。
 * 也没有 `darkColorScheme()` 工厂,必须直接构造 [ColorScheme]。
 *
 * 动态颜色:Wear M3 自带 [dynamicColorScheme] —— 注意它**不是 @Composable**,
 * 需用 [remember] 包裹;且返回的是 Wear 专属 [ColorScheme](不是标准 M3 的)。
 * Android 12+ 上调用它会从系统壁纸取色;< API 31 会 fallback 到中性色。
 */

/**
 * 业务状态指示色。
 *
 * Good=运行正常(绿) / Warning=需注意(橙) / Bad=错误(红)。
 * 不并入 [MaterialTheme.colorScheme],因为它们是业务状态而非 UI 主题色,
 * 跨 surface 复用会混淆语义(例如 Bad 不一定等于 error container)。
 *
 * 这三种色为全局通用,无论主题是动态还是静态,都使用这套固定语义色,
 * 保证"成功/警告/错误"在视觉上始终清晰可辨。
 */
val StatusGood = Color(0xFF67E6AC)
val StatusWarning = Color(0xFFFFB547)
val StatusBad = Color(0xFFFF7A85)

/**
 * 静态深色 ColorScheme(用于 Android 12 以下,或动态取色失败时 fallback)。
 *
 * 设计目标:纯黑背景 + 中性偏白主色,不引入饱和的蓝灰/黄。
 * - background = 纯黑 #000000
 * - surface 三档:从纯黑略微提亮,营造层级感但不喧宾夺主
 * - primary:浅灰白 #E0E0E0(中性,不饱和)—— 用于主操作文字、标题
 * - secondary:中灰 #9E9E9E —— 选中容器、次级元素
 * - tertiary:淡蓝灰 #B0BEC5 —— 三级元素
 * - error:红 #FF7A85
 */
private val FallbackColorScheme = ColorScheme(
    primary = Color(0xFFE0E0E0),
    primaryDim = Color(0xFF707070),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimary = Color(0xFF000000),
    onPrimaryContainer = Color(0xFFE0E0E0),
    secondary = Color(0xFF9E9E9E),
    secondaryDim = Color(0xFF4A4A4A),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondary = Color(0xFF000000),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary = Color(0xFFB0BEC5),
    tertiaryDim = Color(0xFF3A4A50),
    tertiaryContainer = Color(0xFF1F282C),
    onTertiary = Color(0xFF000000),
    onTertiaryContainer = Color(0xFFE0E0E0),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF222222),
    surfaceContainerHigh = Color(0xFF2C2C2C),
    onSurface = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF6A6A6A),
    outlineVariant = Color(0xFF3A3A3A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEEEEEE),
    error = Color(0xFFFF7A85),
    errorDim = Color(0xFF5C1A24),
    errorContainer = Color(0xFF5C1A24),
    onError = Color(0xFF000000),
    onErrorContainer = Color(0xFFFFDDE0),
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
 * 取色策略(按用户要求"卡片根据系统取色,不要蓝灰+黄"):
 *  - Android 12+(API 31+):用 [dynamicColorScheme] 跟随系统壁纸取色
 *  - Android 12 以下:fallback 到 [FallbackColorScheme](纯黑 + 中性灰白)
 *
 * [isSystemInDarkTheme] 在 Wear OS 上恒为 true,这里仅作显式语义标记。
 */
@Composable
fun MihomoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val darkTheme = isSystemInDarkTheme()

    // Android 12+ 用系统动态颜色;低版本 fallback 到中性深色方案。
    // 注意:dynamicColorScheme 不是 @Composable,需 remember 包裹。
    val colorScheme = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // dynamicColorScheme 在 Android 12+ 会从系统壁纸取色,
            // < 31 时内部会 fallback 到中性色(保险起见我们额外判一次)
            dynamicColorScheme(context)
        } else {
            FallbackColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MihomoTypography,
        content = content
    )
}
