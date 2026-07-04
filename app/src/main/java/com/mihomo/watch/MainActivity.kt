package com.mihomo.watch

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.wear.compose.material.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // 不用 viewModel()(inline 函数在某些版本组合下内联失败),
                // 直接 remember 构造 AppViewModel,Wear OS 单屏够用
                val vm = remember { AppViewModel(application) }
                AppRoot(vm)
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshShizuku() }
    when (vm.screen) {
        AppViewModel.Screen.Main -> MainScreen(vm)
        AppViewModel.Screen.Nodes -> NodesScreen(vm)
    }
}

@Composable
private fun MainScreen(vm: AppViewModel) {
    val listState = rememberScalingLazyListState()
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
                AppViewModel.ShizukuState.READY -> "Shizoku 就绪" to Color(0xFF43A047)
            }
            StatusBadge(text, color)
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

        // Shizoku 未就绪时的提示
        if (vm.shizukuState == AppViewModel.ShizukuState.NOT_INSTALLED) {
            item { HintText("请先在手表安装 Shizuku 并通过 ADB 启动") }
        }
        if (vm.shizukuState == AppViewModel.ShizukuState.NOT_RUNNING) {
            item { HintText("请打开 Shizuku App 启动服务") }
        }

        // 订阅链接输入框(只在 Shizuku 就绪后显示)
        if (vm.shizukuState == AppViewModel.ShizukuState.READY) {
            item { HintText("订阅链接") }
            item { SubInput(vm) }
            item {
                Row {
                    Chip(
                        onClick = { vm.setSubscriptionFromClipboard(getClipboard()) },
                        label = { Text("粘贴") }
                    )
                    Spacer(Modifier.width(8.dp))
                    Chip(
                        onClick = { vm.subscriptionUrl = "" },
                        label = { Text("清空") }
                    )
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

        // 错误提示
        vm.error?.let { msg ->
            item {
                StatusBadge(msg, Color(0xFFE53935))
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

@Composable
private fun getClipboard(): String? {
    val ctx = LocalContext.current
    return try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.text?.toString()
    } catch (e: Exception) {
        null
    }
}
