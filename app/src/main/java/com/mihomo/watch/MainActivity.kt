package com.mihomo.watch

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*

class MainActivity : ComponentActivity() {

    // 通知权限申请(Android 13+ 必需,用于表盘"运行中"指示器)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* 不管授权与否都不阻塞,指示器尽力而为 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        setContent {
            MaterialTheme {
                // 不用 viewModel()(inline 函数在某些版本组合下内联失败),
                // 直接 remember 构造 AppViewModel,Wear OS 单屏够用
                val vm = remember { AppViewModel(application) }
                AppRoot(vm)
            }
        }
    }

    /** Android 13+ 必须运行时申请 POST_NOTIFICATIONS,否则 ongoing notification 不显示 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshShizuku() }
    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            AppViewModel.Screen.Main -> MainScreen(vm)
            AppViewModel.Screen.Nodes -> NodesScreen(vm)
        }
        if (vm.showSaveDialog) SaveSubscriptionDialog(vm)
    }
}

@Composable
private fun SaveSubscriptionDialog(vm: AppViewModel) {
    Dialog(onDismissRequest = { vm.cancelSaveDialog() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(Color(0xFF222222))
                .padding(10.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("保存订阅", color = Color(0xFFFFC107), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("名字:", color = Color(0xFFCCCCCC), fontSize = 10.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF333333))
                        .padding(6.dp)
                ) {
                    BasicTextField(
                        value = vm.editingSubName,
                        onValueChange = { vm.updateEditingName(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Chip(
                        onClick = { vm.confirmSaveSubscription() },
                        label = { Text("确认", fontSize = 11.sp) },
                        colors = ChipDefaults.primaryChipColors()
                    )
                    Spacer(Modifier.width(8.dp))
                    Chip(
                        onClick = { vm.cancelSaveDialog() },
                        label = { Text("取消", fontSize = 11.sp) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(vm: AppViewModel) {
    val listState = rememberScalingLazyListState()
    val ctx = LocalContext.current  // 在 composable 作用域取,onClick 里用
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 20.dp)
    ) {
        item { TitleChip("Mihomo 代理") }

        // Shizuku 状态
        item {
            val (text, color) = when (vm.shizukuState) {
                AppViewModel.ShizukuState.NOT_INSTALLED -> "Shizuku 未安装" to Color(0xFFE53935)
                AppViewModel.ShizukuState.NOT_RUNNING -> "Shizuku 未运行" to Color(0xFFFB8C00)
                AppViewModel.ShizukuState.NO_PERMISSION -> "Shizuku 未授权" to Color(0xFFFB8C00)
                AppViewModel.ShizukuState.READY -> if (vm.isBound) "Shizuku 已就绪" to Color(0xFF43A047)
                    else "Shizuku 已授权/未绑定" to Color(0xFFFB8C00)
            }
            StatusBadge(text, color)
        }

        // 已授权但 UserService 未绑定时,显示重新连接按钮(可选,反射模式不需要)
        if (vm.shizukuState == AppViewModel.ShizukuState.READY && !vm.isBound) {
            item { HintText("UserService 未绑定(32 位 Wear OS 常见)") }
            item { HintText("→ 反射模式可用,无需绑定即可启动代理") }
            item {
                Chip(
                    onClick = { vm.reconnect() },
                    label = { Text("尝试绑定 UserService(可选)") },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }

        // 诊断按钮(始终显示,排错用)
        item {
            Chip(
                onClick = { vm.toggleDiagnostic() },
                label = { Text(if (vm.showDiagnostic) "隐藏诊断" else "诊断") },
                colors = ChipDefaults.secondaryChipColors()
            )
        }
        if (vm.showDiagnostic) {
            item {
                Text(
                    text = vm.diagnostic,
                    fontSize = 9.sp,
                    color = Color(0xFFCCCCCC),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            item {
                Row {
                    Chip(
                        onClick = { vm.refreshDiagnostic() },
                        label = { Text("刷新诊断") }
                    )
                    Spacer(Modifier.width(6.dp))
                    Chip(
                        onClick = { vm.testReflection() },
                        label = { Text("测试反射") },
                        colors = ChipDefaults.primaryChipColors()
                    )
                }
                item {
                    Chip(
                        onClick = { vm.testNotification() },
                        label = { Text("测试通知") },
                        colors = ChipDefaults.primaryChipColors()
                    )
                }
            }
            item { HintText("反射成功→直接点'启动'即可开代理") }
        }

        // 未授权时显示授权按钮
        if (vm.shizukuState == AppViewModel.ShizukuState.NO_PERMISSION) {
            item {
                Chip(
                    onClick = { vm.requestPermission() },
                    label = { Text("授权 Shizuku") },
                    colors = ChipDefaults.primaryChipColors()
                )
            }
        }

        // Shizuku 未就绪时的提示
        if (vm.shizukuState == AppViewModel.ShizukuState.NOT_INSTALLED) {
            item { HintText("请先在手表安装 Shizuku 并通过 ADB 启动") }
        }
        if (vm.shizukuState == AppViewModel.ShizukuState.NOT_RUNNING) {
            item { HintText("Shizuku 服务未运行,需用 ADB 启动(不是只授权)") }
            item { HintText("电脑连手表 ADB 后执行:") }
            item { HintText("adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh") }
        }

        // 订阅链接输入框(只在 Shizuku 就绪后显示)
        if (vm.shizukuState == AppViewModel.ShizukuState.READY) {
            // 开机自启开关
            item {
                Chip(
                    onClick = { vm.toggleAutoStart() },
                    label = {
                        Text(
                            if (vm.autoStart) "✓ 开机自启: 开" else "开机自启: 关",
                            fontSize = 10.sp
                        )
                    },
                    colors = if (vm.autoStart) ChipDefaults.primaryChipColors()
                    else ChipDefaults.secondaryChipColors()
                )
            }
            if (vm.autoStart) {
                item { HintText("App 启动时若 Shizuku 就绪+有订阅,自动开代理") }
            }

            item { HintText("订阅链接") }
            item { SubInput(vm) }
            item {
                Row {
                    Chip(
                        onClick = { vm.setSubscriptionFromClipboard(getClipboard(ctx)) },
                        label = { Text("粘贴") }
                    )
                    Spacer(Modifier.width(6.dp))
                    Chip(
                        onClick = { vm.saveCurrentSubscription() },
                        label = { Text("保存") },
                        colors = ChipDefaults.primaryChipColors()
                    )
                    Spacer(Modifier.width(6.dp))
                    Chip(
                        onClick = { vm.subscriptionUrl = "" },
                        label = { Text("清空") }
                    )
                }
            }

            // 已保存订阅列表(点击载入,× 删除)
            if (vm.savedSubscriptions.isNotEmpty()) {
                item { HintText("已保存订阅 (点选载入)") }
                vm.savedSubscriptions.forEach { sub ->
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Chip(
                                onClick = { vm.loadSubscription(sub) },
                                label = {
                                    Text(
                                        text = sub.name,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        color = if (sub.url == vm.subscriptionUrl.trim())
                                            Color.Black else Color.White
                                    )
                                },
                                colors = if (sub.url == vm.subscriptionUrl.trim())
                                    ChipDefaults.primaryChipColors()
                                else ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(4.dp))
                            Chip(
                                onClick = { vm.deleteSubscription(sub.name) },
                                label = { Text("×", color = Color(0xFFE53935), fontSize = 14.sp) }
                            )
                        }
                    }
                }
            }

            // 启动/停止按钮
            item {
                if (vm.isRunning) {
                    Button(
                        onClick = { vm.stopProxy() },
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("停止", fontSize = 14.sp)
                    }
                } else {
                    Button(
                        onClick = { vm.startProxy() },
                        colors = ButtonDefaults.primaryButtonColors()
                    ) {
                        if (vm.loading) {
                            CircularProgressIndicator(
                                indicatorColor = Color.Black,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text("启动", fontSize = 14.sp)
                        }
                    }
                }
            }

            // 运行中显示节点选择入口
            if (vm.isRunning) {
                item {
                    Chip(
                        onClick = { vm.loadGroups() },
                        label = { Text("选择节点 →") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
                item { StatusBadge("代理运行中 127.0.0.1:7890", Color(0xFF43A047)) }
            }
        }

        // 错误提示(可能多行,用 Text 块完整显示)
        vm.error?.let { msg ->
            item {
                Text(
                    text = "⚠ $msg",
                    color = Color(0xFFFFCDD2),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth()
                )
            }
            item {
                Chip(
                    onClick = { vm.clearError() },
                    label = { Text("关闭错误") }
                )
            }
        }

        // 日志
        if (vm.log.isNotBlank()) {
            item { HintText("日志") }
            item {
                Text(
                    text = vm.log,
                    fontSize = 9.sp,
                    color = Color(0xFFBBBBBB),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun NodesScreen(vm: AppViewModel) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 20.dp)
    ) {
        item {
            Chip(
                onClick = { vm.backToMain() },
                label = { Text("← 返回") }
            )
        }

        // 操作按钮:更新订阅 / 测延迟 / 切换排序
        item {
            Row {
                Chip(
                    onClick = { vm.updateSubscription() },
                    label = {
                        if (vm.updating) Text("...") else Text("更新订阅")
                    }
                )
                Spacer(Modifier.width(6.dp))
                Chip(
                    onClick = { vm.testAllDelays() },
                    label = {
                        if (vm.testingDelays) Text("测速中") else Text("测延迟")
                    }
                )
            }
        }
        item {
            Chip(
                onClick = { vm.toggleSort() },
                label = {
                    Text(if (vm.sortByDelay) "排序: 延迟↑" else "排序: 原序")
                },
                colors = ChipDefaults.secondaryChipColors()
            )
        }

        if (vm.groups.isEmpty()) {
            item { HintText("无 Selector 分组或加载中") }
        }

        vm.groups.forEach { group ->
            item {
                Text(
                    text = "【${group.name}】",
                    fontSize = 12.sp,
                    color = Color(0xFFFFC107),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            item {
                Text(
                    text = "当前: ${group.now ?: "无"}",
                    fontSize = 10.sp,
                    color = Color(0xFF43A047),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            // 按排序方式列出节点
            vm.sortedNodes(group).forEach { (node, delay) ->
                item {
                    val isCurrent = node == group.now
                    val delayText = when (delay) {
                        null -> ""
                        -1 -> " ✗"
                        else -> " ${delay}ms"
                    }
                    val nodeColor = when (delay) {
                        null -> Color.White
                        -1 -> Color(0xFFE53935)
                        in 0..300 -> Color(0xFF43A047)
                        in 301..800 -> Color(0xFFFB8C00)
                        else -> Color(0xFFE53935)
                    }
                    Chip(
                        onClick = { vm.selectNode(group.name, node) },
                        label = {
                            Text(
                                text = node + delayText,
                                fontSize = 10.sp,
                                maxLines = 1,
                                color = if (isCurrent) Color.Black else nodeColor
                            )
                        },
                        colors = if (isCurrent) ChipDefaults.primaryChipColors()
                        else ChipDefaults.secondaryChipColors()
                    )
                }
            }
        }
    }
}

@Composable
private fun SubInput(vm: AppViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .background(Color(0xFF222222))
            .padding(8.dp)
    ) {
        if (vm.subscriptionUrl.isEmpty()) {
            Text("输入订阅链接...", color = Color(0xFF888888), fontSize = 11.sp)
        }
        BasicTextField(
            value = vm.subscriptionUrl,
            onValueChange = { vm.subscriptionUrl = it },
            singleLine = false,
            maxLines = 3,
            textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TitleChip(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        color = Color(0xFFFFC107),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .background(color.copy(alpha = 0.2f))
            .padding(8.dp)
    ) {
        Text(text = text, color = color, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = Color(0xFFAAAAAA),
        modifier = Modifier.padding(vertical = 4.dp),
        textAlign = TextAlign.Center
    )
}

private fun getClipboard(ctx: Context): String? {
    return try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.text?.toString()
    } catch (e: Exception) {
        null
    }
}
