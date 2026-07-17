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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text

private val AppBackground = Color(0xFF071427)
private val Panel = Color(0xFF102842)
private val PanelRaised = Color(0xFF163A5D)
private val PanelSelected = Color(0xFF174863)
private val Accent = Color(0xFF55D8FF)
private val AccentSoft = Color(0xFFB5A2FF)
private val OnSurface = Color(0xFFF2F6FC)
private val Muted = Color(0xFFB8C7DB)
private val Good = Color(0xFF67E6AC)
private val Warning = Color(0xFFFFCB66)
private val Bad = Color(0xFFFF8492)

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MaterialTheme {
                val vm = remember { AppViewModel(application) }
                AppRoot(vm)
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshShizuku() }
    Box(Modifier.fillMaxSize().background(AppBackground)) {
        if (vm.screen == AppViewModel.Screen.Main) MainScreen(vm) else NodesScreen(vm)
        if (vm.showSaveDialog) SaveDialog(vm)
    }
}

@Composable
private fun MainScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Deliberately large: the circular display clips its physical edges.
        contentPadding = PaddingValues(horizontal = 30.dp, vertical = 38.dp)
    ) {
        item { ScreenTitle("Mihomo", if (vm.isRunning) "代理已运行" else "系统代理控制") }
        item { StatusPanel(vm) }

        if (vm.shizukuState == AppViewModel.ShizukuState.READY) {
            item { SubscriptionPanel(vm, context) }
            item { PrimaryProxyAction(vm) }
            if (vm.isRunning) item { RunningPanel(vm) }
        } else {
            item { PreparationPanel(vm) }
        }

        item { DiagnosticPanel(vm) }
        vm.error?.let { error -> item { InfoPanel("需要处理", error, Bad, vm::clearError) } }
        if (vm.log.isNotBlank()) item { InfoPanel("运行日志", vm.log, Muted) }
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 10.dp)
    ) {
        Text(title, color = OnSurface, fontSize = 21.sp)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = Accent, fontSize = 11.sp)
    }
}

@Composable
private fun StatusPanel(vm: AppViewModel) {
    val status = when (vm.shizukuState) {
        AppViewModel.ShizukuState.NOT_INSTALLED -> Status("Shizuku 未安装", "安装并启动 Shizuku 后再继续", Bad)
        AppViewModel.ShizukuState.NOT_RUNNING -> Status("Shizuku 未运行", "已安装，但服务尚未启动", Warning)
        AppViewModel.ShizukuState.NO_PERMISSION -> Status("需要 Shizuku 授权", "授权后才能管理 mihomo", Warning)
        AppViewModel.ShizukuState.READY -> Status(
            if (vm.isRunning) "代理正在运行" else "Shizuku 已就绪",
            if (vm.isBound) "UserService 已连接" else "反射通道可用",
            if (vm.isRunning) Good else Accent
        )
    }
    InfoPanel(status.title, status.detail, status.color)
    when {
        vm.shizukuState == AppViewModel.ShizukuState.NO_PERMISSION -> {
            FilledTonalButton(
                onClick = vm::requestPermission,
                modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 4.dp)
            ) { Text("授权 Shizuku", fontSize = 12.sp) }
        }
        vm.shizukuState == AppViewModel.ShizukuState.READY && !vm.isBound -> {
            OutlinedButton(
                onClick = vm::reconnect,
                modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 4.dp)
            ) { Text("连接 UserService", fontSize = 12.sp) }
        }
    }
}

private data class Status(val title: String, val detail: String, val color: Color)

@Composable
private fun PreparationPanel(vm: AppViewModel) {
    val text = if (vm.shizukuState == AppViewModel.ShizukuState.NOT_RUNNING) {
        "请在手机端启动 Shizuku 服务后，返回此处刷新状态。"
    } else {
        "安装 Shizuku、启动服务，并为本应用授予权限。"
    }
    InfoPanel("使用前准备", text, Muted)
}

@Composable
private fun SubscriptionPanel(vm: AppViewModel, context: Context) {
    SectionLabel("订阅")
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(Modifier.padding(13.dp)) {
            Text("订阅链接", color = Accent, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            UrlInput(vm.subscriptionUrl, { vm.subscriptionUrl = it }, "粘贴或输入 Clash 订阅链接")
            Spacer(Modifier.height(10.dp))
            // One action per line on a round watch: never compress three touch targets.
            FilledTonalButton(
                onClick = { vm.setSubscriptionFromClipboard(readClipboard(context)) },
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) { Text("从剪贴板粘贴", fontSize = 11.sp) }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = vm::saveCurrentSubscription,
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text("保存", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { vm.subscriptionUrl = "" },
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text("清空", fontSize = 11.sp) }
            }
        }
    }

    if (vm.savedSubscriptions.isNotEmpty()) {
        SectionLabel("已保存订阅")
        vm.savedSubscriptions.forEach { sub ->
            val selected = sub.url == vm.subscriptionUrl.trim()
            Card(
                onClick = { vm.loadSubscription(sub) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(containerColor = if (selected) PanelSelected else Panel)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(sub.name, color = if (selected) Accent else OnSurface, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(if (selected) "当前使用 · 点按可重新载入" else "点按载入此订阅", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(7.dp))
                    OutlinedButton(
                        onClick = { vm.deleteSubscription(sub.name) },
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) { Text("删除此订阅", fontSize = 10.sp) }
                }
            }
        }
    }
}

@Composable
private fun PrimaryProxyAction(vm: AppViewModel) {
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { if (vm.isRunning) vm.stopProxy() else vm.startProxy() },
        enabled = !vm.loading,
        modifier = Modifier.fillMaxWidth().height(54.dp)
    ) {
        Text(
            if (vm.loading) "正在处理…" else if (vm.isRunning) "停止代理" else "启动代理",
            fontSize = 15.sp
        )
    }
}

@Composable
private fun RunningPanel(vm: AppViewModel) {
    InfoPanel("系统代理已启用", "HTTP 7890 · SOCKS5 7891", Good)
    FilledTonalButton(
        onClick = vm::loadGroups,
        modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 4.dp)
    ) { Text("选择节点", fontSize = 12.sp) }
}

@Composable
private fun DiagnosticPanel(vm: AppViewModel) {
    SectionLabel("工具与诊断")
    OutlinedButton(
        onClick = vm::toggleDiagnostic,
        modifier = Modifier.fillMaxWidth().height(44.dp)
    ) { Text(if (vm.showDiagnostic) "收起诊断" else "展开诊断", fontSize = 11.sp) }

    if (vm.showDiagnostic) {
        Spacer(Modifier.height(6.dp))
        InfoPanel("诊断信息", vm.diagnostic.ifBlank { "点击刷新以获取状态" }, Muted)
        FilledTonalButton(
            onClick = vm::refreshDiagnostic,
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) { Text("刷新诊断状态", fontSize = 11.sp) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = vm::testReflection,
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) { Text("测试反射通道", fontSize = 11.sp) }
    }
}

@Composable
private fun NodesScreen(vm: AppViewModel) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 38.dp)
    ) {
        item { ScreenTitle("节点选择", "点按节点即可切换") }
        item {
            OutlinedButton(onClick = vm::backToMain, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text("返回主页", fontSize = 11.sp)
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = vm::updateSubscription, enabled = !vm.updating, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text(if (vm.updating) "正在更新订阅…" else "更新订阅", fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(onClick = vm::testAllDelays, enabled = !vm.testingDelays, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text(if (vm.testingDelays) "正在测速…" else "测试全部延迟", fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = vm::toggleSort, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                Text(if (vm.sortByDelay) "当前：按延迟排序" else "当前：原始顺序", fontSize = 10.sp)
            }
        }
        if (vm.groups.isEmpty()) item { InfoPanel("节点", "暂无 Selector 分组，或仍在加载。", Muted) }
        vm.groups.forEach { group ->
            item { SectionLabel(group.name) }
            item { InfoPanel("当前节点", group.now ?: "未选择", Good) }
            val pairs = vm.sortedNodes(group).chunked(2)
            items(pairs) { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (node, delay) ->
                        NodeTile(node, delay, node == group.now, Modifier.weight(1f)) { vm.selectNode(group.name, node) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NodeTile(name: String, delay: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (label, color) = when (delay) {
        null -> "未测速" to Muted
        -1 -> "不可用" to Bad
        in 0..300 -> "${delay}ms" to Good
        in 301..800 -> "${delay}ms" to Warning
        else -> "${delay}ms" to Bad
    }
    Card(
        onClick = onClick,
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) PanelSelected else Panel)
    ) {
        Column(
            modifier = Modifier.padding(9.dp).heightIn(min = 74.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(name, color = if (selected) Accent else OnSurface, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(if (selected) "已选 · $label" else label, color = color, fontSize = 9.sp)
        }
    }
}

@Composable
private fun SaveDialog(vm: AppViewModel) {
    Dialog(onDismissRequest = vm::cancelSaveDialog) {
        Card(onClick = {}, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("保存订阅", color = OnSurface, fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                Text("给这条订阅起一个容易识别的名称", color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                UrlInput(vm.editingSubName, vm::updateEditingName, "例如：主线路", true)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = vm::confirmSaveSubscription, modifier = Modifier.fillMaxWidth().height(43.dp)) { Text("保存", fontSize = 11.sp) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = vm::cancelSaveDialog, modifier = Modifier.fillMaxWidth().height(40.dp)) { Text("取消", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun UrlInput(value: String, onValueChange: (String) -> Unit, hint: String, singleLine: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().background(PanelRaised).padding(horizontal = 11.dp, vertical = 10.dp)
    ) {
        if (value.isBlank()) Text(hint, color = Muted, fontSize = 11.sp, maxLines = if (singleLine) 1 else 3, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            textStyle = TextStyle(color = OnSurface, fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun InfoPanel(title: String, body: String, color: Color, action: (() -> Unit)? = null) {
    Card(onClick = {}, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(13.dp)) {
            Text(title, color = color, fontSize = 13.sp)
            Spacer(Modifier.height(3.dp))
            Text(body, color = Muted, fontSize = 10.sp, maxLines = 7, overflow = TextOverflow.Ellipsis)
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().height(38.dp)) { Text("关闭提示", fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = AccentSoft,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
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
