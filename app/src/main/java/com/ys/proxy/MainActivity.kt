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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
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
import androidx.wear.compose.material3.*

private val Navy = Color(0xFF071427)
private val Surface = Color(0xFF10243D)
private val SurfaceAlt = Color(0xFF173656)
private val Cyan = Color(0xFF4DD8FF)
private val Purple = Color(0xFFB49CFF)
private val Good = Color(0xFF5BE7A9)
private val Warning = Color(0xFFFFC857)
private val Bad = Color(0xFFFF7B8A)
private val Muted = Color(0xFFB7C5D9)

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MaterialTheme { val vm = remember { AppViewModel(application) }; AppRoot(vm) } }
    }
}

@Composable private fun AppRoot(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshShizuku() }
    Box(Modifier.fillMaxSize().background(Navy)) {
        if (vm.screen == AppViewModel.Screen.Main) MainScreen(vm) else NodesScreen(vm)
        if (vm.showSaveDialog) SaveDialog(vm)
    }
}
@Composable private fun SaveDialog(vm: AppViewModel) {
    Dialog(onDismissRequest = vm::cancelSaveDialog) {
        Card(onClick = {}, modifier = Modifier.fillMaxWidth(.9f), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("保存订阅", color = Cyan, fontSize = 15.sp)
                Text("为订阅设置易识别的名称", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp)); Input(vm.editingSubName, vm::updateEditingName, "例如：主线路", true)
                Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = vm::confirmSaveSubscription) { Text("保存") }
                    OutlinedButton(onClick = vm::cancelSaveDialog) { Text("取消") }
                }
            }
        }
    }
}
@Composable private fun MainScreen(vm: AppViewModel) {
    val ctx = LocalContext.current; val state = rememberScalingLazyListState()
    ScalingLazyColumn(state = state, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 22.dp)) {
        item { Header("Mihomo 代理", if (vm.isRunning) "代理运行中" else "系统代理控制") }
        item { ShizukuStatus(vm) }
        if (vm.shizukuState == AppViewModel.ShizukuState.READY) {
            item { SubscriptionEditor(vm, ctx) }
            item { MainAction(vm) }
            if (vm.isRunning) item { Column(horizontalAlignment = Alignment.CenterHorizontally) { InfoCard("系统代理已启用", "HTTP 7890 · SOCKS5 7891", Good); FilledTonalButton(onClick = vm::loadGroups) { Text("选择节点") } } }
        } else item { SetupCard(vm) }
        item { Diagnostics(vm) }
        vm.error?.let { item { InfoCard("需要处理", it, Bad, vm::clearError) } }
        if (vm.log.isNotBlank()) item { InfoCard("运行日志", vm.log, Muted) }
    }
}
@Composable private fun Header(title: String, subtitle: String) = Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 8.dp)) { Text(title, color = Color.White, fontSize = 18.sp); Text(subtitle, color = Cyan, fontSize = 10.sp) }
@Composable private fun ShizukuStatus(vm: AppViewModel) {
    val (a,b,c) = when(vm.shizukuState) {
        AppViewModel.ShizukuState.NOT_INSTALLED -> Triple("Shizuku 未安装", "请安装后通过 ADB 启动服务", Bad)
        AppViewModel.ShizukuState.NOT_RUNNING -> Triple("Shizuku 未运行", "已安装，但服务尚未启动", Warning)
        AppViewModel.ShizukuState.NO_PERMISSION -> Triple("需要 Shizuku 授权", "授权后才能管理 mihomo", Warning)
        AppViewModel.ShizukuState.READY -> Triple(if(vm.isRunning) "代理正在运行" else "Shizuku 已就绪", if(vm.isBound) "UserService 已连接" else "反射通道可用", if(vm.isRunning) Good else Cyan)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) { InfoCard(a,b,c); if(vm.shizukuState == AppViewModel.ShizukuState.NO_PERMISSION) FilledTonalButton(onClick=vm::requestPermission) { Text("授权 Shizuku") }; if(vm.shizukuState == AppViewModel.ShizukuState.READY && !vm.isBound) OutlinedButton(onClick=vm::reconnect) { Text("尝试连接 UserService") } }
}
@Composable private fun SetupCard(vm: AppViewModel) { InfoCard("使用前准备", if(vm.shizukuState == AppViewModel.ShizukuState.NOT_RUNNING) "在手机 ADB 中执行：\nadb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh" else "安装 Shizuku 后，启动服务并授予本应用权限。", Muted) }
@Composable private fun SubscriptionEditor(vm: AppViewModel, ctx: Context) {
    Section("订阅")
    Card(onClick={}, modifier=Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=Surface)) { Column(Modifier.padding(10.dp)) { Text("订阅链接",color=Cyan,fontSize=11.sp); Spacer(Modifier.height(5.dp)); Input(vm.subscriptionUrl,{vm.subscriptionUrl=it},"粘贴或输入 Clash 订阅链接",false); Spacer(Modifier.height(7.dp)); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp,Alignment.CenterHorizontally)) { OutlinedButton(onClick={vm.setSubscriptionFromClipboard(clipboard(ctx))}) { Text("粘贴",fontSize=10.sp) }; FilledTonalButton(onClick=vm::saveCurrentSubscription) { Text("保存",fontSize=10.sp) }; OutlinedButton(onClick={vm.subscriptionUrl=""}) { Text("清空",fontSize=10.sp) } } } }
    if(vm.savedSubscriptions.isNotEmpty()) { Section("已保存订阅 · 点按载入") ; vm.savedSubscriptions.forEach { sub -> val active=sub.url==vm.subscriptionUrl.trim(); Card(onClick={vm.loadSubscription(sub)},modifier=Modifier.fillMaxWidth().padding(vertical=3.dp),colors=CardDefaults.cardColors(containerColor=if(active) Color(0xFF153D59) else Surface)) { Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(sub.name,color=if(active) Cyan else Color.White,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis); Text(if(active) "当前订阅" else "点击载入",color=Muted,fontSize=9.sp) }; OutlinedButton(onClick={vm.deleteSubscription(sub.name)},modifier=Modifier.height(34.dp)) { Text("删除",fontSize=9.sp) } } } } }
}
@Composable private fun MainAction(vm: AppViewModel) { Button(onClick={if(vm.isRunning) vm.stopProxy() else vm.startProxy()},enabled=!vm.loading,modifier=Modifier.fillMaxWidth().padding(top=10.dp)) { Text(if(vm.loading) "正在启动…" else if(vm.isRunning) "停止代理" else "启动代理",fontSize=15.sp) } }
@Composable private fun NodesScreen(vm: AppViewModel) {
    val state=rememberScalingLazyListState(); ScalingLazyColumn(state=state,modifier=Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,contentPadding=PaddingValues(horizontal=14.dp,vertical=20.dp)) {
        item { Header("节点选择","双列显示 · 点按切换") }; item { OutlinedButton(onClick=vm::backToMain) { Text("返回主页") } }; item { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { FilledTonalButton(onClick=vm::updateSubscription,enabled=!vm.updating) { Text(if(vm.updating) "更新中" else "更新订阅",fontSize=10.sp) }; FilledTonalButton(onClick=vm::testAllDelays,enabled=!vm.testingDelays) { Text(if(vm.testingDelays) "测速中" else "测速",fontSize=10.sp) } } }; item { OutlinedButton(onClick=vm::toggleSort) { Text(if(vm.sortByDelay) "按延迟排序" else "按原始顺序",fontSize=10.sp) } }
        if(vm.groups.isEmpty()) item { InfoCard("节点", "暂无 Selector 分组，或仍在加载。", Muted) }
        vm.groups.forEach { group -> item { Section(group.name) }; item { InfoCard("当前节点", group.now ?: "未选择", Good) }; val pairs=vm.sortedNodes(group).chunked(2); items(pairs) { pair -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) { pair.forEach { (node,delay) -> Node(node,delay,node==group.now,Modifier.weight(1f)) { vm.selectNode(group.name,node) } }; if(pair.size==1) Spacer(Modifier.weight(1f)) } } }
    }
}
@Composable private fun Node(name:String, delay:Int?, selected:Boolean, modifier:Modifier, click:()->Unit) { val(label,color)=when(delay){null->"未测速" to Muted;-1->"失败" to Bad;in 0..300->"${delay}ms" to Good;in 301..800->"${delay}ms" to Warning;else->"${delay}ms" to Bad}; Card(onClick=click,modifier=modifier.padding(vertical=3.dp),colors=CardDefaults.cardColors(containerColor=if(selected) Color(0xFF153D59) else Surface)){Column(Modifier.padding(8.dp).heightIn(min=64.dp),verticalArrangement=Arrangement.SpaceBetween){Text(name,color=if(selected) Cyan else Color.White,fontSize=10.sp,maxLines=3,overflow=TextOverflow.Ellipsis);Text(if(selected) "已选 · $label" else label,color=color,fontSize=9.sp)}} }
@Composable private fun Diagnostics(vm:AppViewModel) { Section("工具与诊断"); OutlinedButton(onClick=vm::toggleDiagnostic){Text(if(vm.showDiagnostic) "收起诊断" else "展开诊断",fontSize=10.sp)}; if(vm.showDiagnostic){InfoCard("诊断信息",vm.diagnostic.ifBlank{"点击刷新以获取状态"},Muted);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){FilledTonalButton(onClick=vm::refreshDiagnostic){Text("刷新",fontSize=10.sp)};FilledTonalButton(onClick=vm::testReflection){Text("测试反射",fontSize=10.sp)}}} }
@Composable private fun Input(value:String, change:(String)->Unit, hint:String, single:Boolean) { Box(Modifier.fillMaxWidth().background(SurfaceAlt).padding(9.dp)){if(value.isBlank()) Text(hint,color=Muted,fontSize=10.sp);BasicTextField(value=value,onValueChange=change,singleLine=single,maxLines=if(single)1 else 3,textStyle=TextStyle(color=Color.White,fontSize=11.sp),modifier=Modifier.fillMaxWidth())} }
@Composable private fun InfoCard(title:String,body:String,color:Color,action:(()->Unit)?=null) { Card(onClick={},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp),colors=CardDefaults.cardColors(containerColor=Surface)){Column(Modifier.padding(11.dp)){Text(title,color=color,fontSize=12.sp);Text(body,color=Muted,fontSize=9.sp,maxLines=8,overflow=TextOverflow.Ellipsis);action?.let{OutlinedButton(onClick=it,modifier=Modifier.padding(top=5.dp)){Text("关闭",fontSize=9.sp)}}}} }
@Composable private fun Section(text:String)=Text(text,color=Purple,fontSize=10.sp,modifier=Modifier.padding(top=10.dp,bottom=3.dp),textAlign=TextAlign.Center)
private fun clipboard(ctx:Context):String?=try{(ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()}catch(_:Exception){null}
