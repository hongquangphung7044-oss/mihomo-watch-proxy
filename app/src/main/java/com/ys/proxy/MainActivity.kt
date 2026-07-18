package com.ys.proxy

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            // M3 主题注入:ColorScheme(琥珀金 + 近黑蓝)+ Typography 一次性注入,
            // 之后所有 wear.compose.material3 组件的默认 colors/typography 都从这里取。
            MihomoTheme {
                val vm = remember { AppViewModel(application) }
                AppRoot(vm)
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshShizuku() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (vm.screen == AppViewModel.Screen.Main) MainScreen(vm) else NodesScreen(vm)
        if (vm.showSaveDialog) SaveDialog(vm)
        if (vm.actionSub != null) SubActionDialog(vm)
        if (vm.renamingSub != null) RenameDialog(vm)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 主界面
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun MainScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        // 圆形屏边缘裁切,留出安全区;item 间距统一管理,不再依赖每个 item 自带 padding
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { HeaderCard(vm) }
        item { StatusCard(vm) }

        if (vm.shizukuState == AppViewModel.ShizukuState.READY) {
            // Shizuku 就绪:展示主操作 + 订阅 + 诊断
            item { PrimaryActionCard(vm) }
            if (vm.isRunning) item { RunningInfoCard(vm) }
            item { SubscriptionCard(vm, context) }
            if (vm.savedSubscriptions.isNotEmpty()) {
                item { SectionLabel("已保存订阅") }
                items(vm.savedSubscriptions) { sub ->
                    SavedSubscriptionCard(vm, sub)
                }
            }
            item { DiagnosticCard(vm) }
        } else {
            // Shizuku 未就绪:只展示准备引导
            item { PreparationCard(vm) }
        }

        // 错误 / 日志作为独立卡片,绝不与诊断面板混在一起
        vm.error?.let { err -> item { ErrorCard(err, vm::clearError) } }
        if (vm.log.isNotBlank()) item { LogCard(vm.log) }
    }
}

/**
 * 顶部标题卡:应用名 + 运行状态概览。
 * 运行中用 tertiaryContainer(绿系)背景,显著区分。
 */
@Composable
private fun HeaderCard(vm: AppViewModel) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (vm.isRunning) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Mihomo",
                color = if (vm.isRunning) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.displaySmall
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (vm.isRunning) "● 代理运行中" else "系统代理控制",
                color = if (vm.isRunning) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private data class StatusInfo(
    val title: String,
    val detail: String,
    val color: Color,
    val action: ActionInfo?
)

private data class ActionInfo(val label: String, val onClick: () -> Unit)

/**
 * 状态卡片:按 4 种 ShizukuState + isRunning 区分场景。
 * - 未安装 / 未运行 / 未授权 → 警告色(无 action 或带授权按钮)
 * - 已就绪 → 主色(无 action 或带连接 UserService 按钮)
 * - 运行中 → 成功色(本卡由 HeaderCard 体现,这里仍标"代理正在运行")
 *
 * 关键修复:本卡内部所有元素一律 Column 包裹,绝不再裸 emit。
 */
@Composable
private fun StatusCard(vm: AppViewModel) {
    val info = when (vm.shizukuState) {
        AppViewModel.ShizukuState.NOT_INSTALLED -> StatusInfo(
            "Shizuku 未安装", "请在手机端安装并启动 Shizuku", StatusWarning, null
        )
        AppViewModel.ShizukuState.NOT_RUNNING -> StatusInfo(
            "Shizuku 未运行", "已安装,请在手机端启动 Shizuku 服务", StatusWarning, null
        )
        AppViewModel.ShizukuState.NO_PERMISSION -> StatusInfo(
            "需要 Shizuku 授权", "授权后才能管理 mihomo", StatusWarning,
            ActionInfo("授权 Shizuku", vm::requestPermission)
        )
        AppViewModel.ShizukuState.READY -> StatusInfo(
            if (vm.isRunning) "代理正在运行" else "Shizuku 已就绪",
            if (vm.isBound) "UserService 已连接" else "反射通道可用",
            if (vm.isRunning) StatusGood else MaterialTheme.colorScheme.primary,
            if (vm.shizukuState == AppViewModel.ShizukuState.READY && !vm.isBound)
                ActionInfo("连接 UserService", vm::reconnect) else null
        )
    }
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(info.title, color = info.color, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                info.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            info.action?.let { action ->
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = action.onClick,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(action.label, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * 主操作按钮:启动 / 停止代理。
 * 60dp 大尺寸 + titleLarge 字号,显眼防误触。
 */
@Composable
private fun PrimaryActionCard(vm: AppViewModel) {
    Button(
        onClick = { if (vm.isRunning) vm.stopProxy() else vm.startProxy() },
        enabled = !vm.loading,
        modifier = Modifier.fillMaxWidth().height(60.dp)
    ) {
        Text(
            if (vm.loading) "正在处理…" else if (vm.isRunning) "停止代理" else "启动代理",
            style = MaterialTheme.typography.titleLarge
        )
    }
}

/**
 * 运行中信息卡:显示代理端口 + 选择节点按钮。
 * 用 tertiaryContainer 背景(绿系)与主操作区分。
 */
@Composable
private fun RunningInfoCard(vm: AppViewModel) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                "代理已启用",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "HTTP 7890 · SOCKS5 7891",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = vm::loadGroups,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("选择节点", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * 订阅输入卡:输入框 + 粘贴 / 保存 / 清空。
 * 关键修复:每个按钮 48dp + Column 间距统一管理,绝不重叠。
 * 粘贴独占一行(高频操作),保存/清空 2 列(对等操作)。
 *
 * 卡片背景用 surfaceContainerLow(比 background 略亮一档),
 * 让 FilledTonalButton(secondaryContainer)/OutlinedButton 与卡片有对比,
 * 避免动态颜色下按钮容器色与卡片背景过于接近导致"看不到按钮框"。
 */
@Composable
private fun SubscriptionCard(vm: AppViewModel, context: Context) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                "订阅链接",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            UrlInput(vm.subscriptionUrl, { vm.subscriptionUrl = it }, "粘贴或输入 Clash 订阅链接")
            Spacer(Modifier.height(10.dp))
            // 粘贴独占一行(高频操作,圆形小屏不挤压)
            // 显式 filledTonalButtonColors:用 primary 容器色,确保与卡片背景对比
            FilledTonalButton(
                onClick = { vm.setSubscriptionFromClipboard(readClipboard(context)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    "从剪贴板粘贴",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(6.dp))
            // 保存 / 清空 对等操作 → 2 列
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = vm::saveCurrentSubscription,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text(
                        "保存",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedButton(
                    onClick = { vm.subscriptionUrl = "" },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        "清空",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 已保存订阅卡片:当前选中明显高亮(secondaryContainer + primary 文字)。
 * 长按弹出操作菜单(更新 / 重命名);短按载入/热切换。
 *
 * 关键 UX 改善:
 *  - 代理运行中时,短按 = 热切换(通过当前代理下载新订阅 + 热重载,不停 mihomo)
 *  - 代理未运行时,短按 = 仅载入 URL(等用户点启动时再下载)
 *  - 文案明确说明"热切换不停代理",避免用户误以为必须先停代理
 *
 * 用 Box + combinedClickable 替代 Card(因为 Wear M3 Card 不支持 onLongClick)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedSubscriptionCard(vm: AppViewModel, sub: SavedSubscription) {
    val selected = sub.url == vm.subscriptionUrl.trim()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .combinedClickable(
                onClick = { vm.loadSubscription(sub) },
                onLongClick = { vm.startSubAction(sub) }
            )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                sub.name,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            // 热切换提示:运行中 → "点按热切换(不停代理)";未运行 → "点按载入"
            val hint = if (selected) {
                if (vm.isRunning) "● 当前使用 · 点按重新热切换" else "● 当前使用 · 点按重新载入"
            } else {
                if (vm.isRunning) "点按热切换(不停代理)· 长按更多" else "点按载入 · 长按更多"
            }
            Text(
                hint,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.deleteSubscription(sub.name) },
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("删除此订阅", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/**
 * 诊断卡:默认收起,展开后含诊断信息 + 刷新 + 测试反射按钮。
 *
 * 关键 bug 修复(原 #63 重叠 bug 根因):
 * 原版 DiagnosticPanel 直接在 @Composable 函数顶层 emit 多个元素
 * (SectionLabel + OutlinedButton + Spacer + InfoPanel + FilledTonalButton + OutlinedButton),
 * 在 ScalingLazyColumn 单 item 内会层叠渲染导致文字重叠。
 * 现版用 Column 包裹全部子元素,通过 Arrangement.spacedBy / Spacer 垂直堆叠,
 * 绝不再出现重叠。
 */
@Composable
private fun DiagnosticCard(vm: AppViewModel) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                "诊断与工具",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = vm::toggleDiagnostic,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(if (vm.showDiagnostic) "收起诊断" else "展开诊断", style = MaterialTheme.typography.bodySmall) }

            if (vm.showDiagnostic) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "诊断信息",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    vm.diagnostic.ifBlank { "点击下方按钮刷新以获取状态" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = vm::refreshDiagnostic,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("刷新诊断状态", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = vm::testReflection,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("测试反射通道", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * 准备引导卡:Shizuku 未就绪时显示。
 * 含"刷新状态"按钮(让用户在手机端启动 Shizuku 后回来刷新)。
 */
@Composable
private fun PreparationCard(vm: AppViewModel) {
    val text = if (vm.shizukuState == AppViewModel.ShizukuState.NOT_RUNNING) {
        "请在手机端启动 Shizuku 服务后,返回此处刷新状态。"
    } else {
        "安装 Shizuku、启动服务,并为本应用授予权限。"
    }
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(13.dp)) {
            Text("使用前准备", color = StatusWarning, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = vm::refreshShizuku,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("刷新状态", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * 错误卡:独立 item,用 errorContainer 背景与正常状态区分。
 * 含关闭按钮。
 */
@Composable
private fun ErrorCard(message: String, onClose: () -> Unit) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(13.dp)) {
            Text("错误", color = StatusBad, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("关闭提示", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/**
 * 日志卡:独立 item,与诊断面板完全分离(避免主页面拥挤)。
 * 截断 8 行,避免长日志占用过多滚动空间。
 */
@Composable
private fun LogCard(log: String) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(13.dp)) {
            Text("运行日志", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                log,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 节点选择页
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun NodesScreen(vm: AppViewModel) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { NodeHeaderCard(vm) }
        item {
            OutlinedButton(
                onClick = vm::backToMain,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("返回主页", style = MaterialTheme.typography.bodySmall) }
        }
        // 操作区:更新订阅 / 测速 / 排序,统一在一张卡片内(Column 包裹,绝不重叠)
        item { NodesActionCard(vm) }

        if (vm.groups.isEmpty()) {
            item { EmptyNodesCard() }
        }
        // 显示所有 Selector 分组(不再过滤引用型分组):
        // 用户反馈"有的分组下的节点显示不可用但实际可用",根因是 filteredGroups
        // 过滤太严格,把含有引用的分组也过滤掉了。直接显示所有分组,让用户看到完整节点。
        items(vm.groups) { group ->
            SelectorGroupCard(vm, group)
        }
    }
}

@Composable
private fun NodeHeaderCard(vm: AppViewModel) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "节点选择",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (vm.testingDelays) "正在测速中…" else "点按节点即可切换",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * 节点页操作卡:更新订阅 / 测速 / 排序。
 * 关键 bug 修复:原版同样存在 DiagnosticPanel 的"裸 emit 多元素"重叠问题,
 * 现版用 Column 包裹,统一间距管理。
 */
@Composable
private fun NodesActionCard(vm: AppViewModel) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(13.dp)) {
            FilledTonalButton(
                onClick = vm::updateSubscription,
                enabled = !vm.updating,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(if (vm.updating) "正在更新订阅…" else "更新订阅", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(
                onClick = vm::testAllDelays,
                enabled = !vm.testingDelays,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(if (vm.testingDelays) "正在测速…" else "测试全部延迟", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = vm::toggleSort,
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text(if (vm.sortByDelay) "当前:按延迟排序" else "当前:原始顺序", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun EmptyNodesCard() {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(13.dp)) {
            Text("暂无节点", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                "订阅未加载或仍在解析中。点上方「更新订阅」重试。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Selector 分组卡:分组名 + 当前节点(高亮)+ 节点列表(单行跑道形胶囊)。
 *
 * 设计(v2,按用户反馈):
 *  - 去除外层 Card 灰色包围(改为透明背景,直接铺在 ScalingLazyColumn 上)
 *  - 分组名 + 当前节点 用标题行表达,不再包裹卡片
 *  - 每个节点改为单行跑道形胶囊(用 Button + RoundedCornerShape(50))
 *  - 节点名过长时用 basicMarquee 横向滚动,不截断不省略
 *  - 延迟用颜色区分(绿/橙/红),作为节点名后缀文字
 *
 * 注意:Wear M3 1.6.2 没有 Chip/FilterChip 组件(已从 M2 退役),
 * 故用 Button + 自定义 shape 拼装单行跑道形胶囊。
 */
@Composable
private fun SelectorGroupCard(vm: AppViewModel, group: MihomoApi.Proxy) {
    // 不再用 Card 包裹,直接 Column 铺在 ScalingLazyColumn 上,无灰色包围
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        // 分组名(琥珀金标题色)+ 当前节点 用一行表达
        Text(
            group.name,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            // 分组名过长时 marquee 滚动,不省略
            modifier = Modifier.basicMarquee(),
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        // 当前节点(成功色 + ● 标记,更醒目),过长时 marquee 滚动
        Text(
            "● 当前:${group.now ?: "未选择"}",
            color = StatusGood,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.basicMarquee(),
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        // 节点列表:每个节点单行跑道形胶囊,垂直堆叠
        val nodes = vm.sortedNodes(group)
        nodes.forEachIndexed { index, (node, delay) ->
            NodeCapsule(
                name = node,
                delay = delay,
                selected = node == group.now,
                onClick = { vm.selectNode(group.name, node) }
            )
            if (index < nodes.size - 1) Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * 单行跑道形节点胶囊(替代旧 NodeTile 的 2 列网格)。
 *
 * 用 Button + RoundedCornerShape(50) 实现跑道形(两端半圆)。
 * 选中态用 secondaryContainer 背景 + primary 文字,未选中用 surfaceContainerHigh。
 * 节点名过长用 basicMarquee 横向滚动,延迟作为后缀文字。
 *
 * 延迟颜色(同时表达):
 *  - 0–300ms 绿(快)
 *  - 301–800ms 橙(中)
 *  - 其他 红(慢/失败/未测)
 */
@Composable
private fun NodeCapsule(
    name: String,
    delay: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val (delayLabel, delayColor) = when (delay) {
        null -> "未测速" to MaterialTheme.colorScheme.onSurfaceVariant
        // 测速失败返回 -1:不显示"不可用"(误导,节点可能仍可用访问其他网站),
        // 改为"测速失败"+ 灰色,让用户知道是测速环节失败而非节点本身不可用
        -1 -> "测速失败" to MaterialTheme.colorScheme.onSurfaceVariant
        in 0..300 -> "${delay}ms" to StatusGood
        in 301..800 -> "${delay}ms" to StatusWarning
        else -> "${delay}ms" to StatusBad
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(50),  // 跑道形(两端半圆)
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                             else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                           else MaterialTheme.colorScheme.onSurface
        )
    ) {
        // 节点名 + 延迟 横向布局
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 节点名:过长时 marquee 滚动(不截断),选中态加 ● 前缀
            Text(
                if (selected) "● $name" else name,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(),
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            // 延迟后缀:固定宽度,颜色表达状态
            Text(
                delayLabel,
                color = delayColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 保存订阅对话框
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SaveDialog(vm: AppViewModel) {
    // 关键修复:彻底弃用 Wear M3 Dialog(其 Scrim 在圆形屏上有上下黑色遮罩),
    // 改用 Box + Card fillMaxSize 自己实现对话框:
    //  - Box 黑色背景遮挡下层 UI
    //  - Card fillMaxSize 占满整个圆形屏,无"上下黑色遮罩"
    //  - 内容垂直居中 + padding 留出圆形屏安全区
    // 取消仅通过"取消"按钮(防误触,无点击外部取消)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Card(
            onClick = {},
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("保存订阅", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "给这条订阅起一个容易识别的名称",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                UrlInput(vm.editingSubName, vm::updateEditingName, "例如:主线路", true)
                Spacer(Modifier.height(12.dp))
                // 主操作按钮用 Button(primary 琥珀金背景),在深色 Card 背景上有明显圆框
                Button(
                    onClick = vm::confirmSaveSubscription,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("保存", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::cancelSaveDialog,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) { Text("取消", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 长按操作菜单对话框
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SubActionDialog(vm: AppViewModel) {
    val sub = vm.actionSub ?: return
    // 弃用 Wear M3 Dialog,改用 Box + Card fillMaxSize(无上下黑色遮罩)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Card(
            onClick = {},
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    sub.name,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                // 主操作按钮用 Button(primary 琥珀金背景),有明显圆框
                Button(
                    onClick = { vm.updateSavedSubscription(sub) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        if (vm.isRunning) "更新订阅(热重载)" else "更新订阅(直连下载)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.startRename(sub) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("重命名", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::cancelSubAction,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) { Text("取消", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 重命名对话框
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun RenameDialog(vm: AppViewModel) {
    // 弃用 Wear M3 Dialog,改用 Box + Card fillMaxSize(无上下黑色遮罩)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Card(
            onClick = {},
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("重命名订阅", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "修改后保存即可,URL 保持不变",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                UrlInput(vm.renameText, vm::updateRenameText, "输入新名称", true)
                Spacer(Modifier.height(12.dp))
                // 主操作按钮用 Button(primary 琥珀金背景),有明显圆框
                Button(
                    onClick = vm::confirmRename,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("保存", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::cancelRename,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) { Text("取消", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 共用组件
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun UrlInput(value: String, onValueChange: (String) -> Unit, hint: String, singleLine: Boolean = false) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 11.dp, vertical = 10.dp)
    ) {
        if (value.isBlank()) Text(
            hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (singleLine) 1 else 3,
            overflow = TextOverflow.Ellipsis
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.secondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        textAlign = TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

private fun readClipboard(context: Context): String? = try {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip?.getItemAt(0)?.text?.toString()
} catch (_: Exception) {
    null
}
