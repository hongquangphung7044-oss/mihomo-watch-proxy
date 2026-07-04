package com.mihomo.watch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * App 状态与业务编排。
 *
 * UI 只读这些 state 字段;调用方法触发业务;方法内部在 IO 线程执行。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val shizuku = ShizukuManager(app)
    private val controller = MihomoController(app)
    private val subscription = SubscriptionManager()
    private val api = MihomoApi()

    enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    var shizukuState by mutableStateOf(ShizukuState.NOT_INSTALLED)
        private set
    var service by mutableStateOf<IWatchService?>(null)
        private set
    var isRunning by mutableStateOf(false)
        private set
    var subscriptionUrl by mutableStateOf("")
    var log by mutableStateOf("")
        private set
    var groups by mutableStateOf<List<MihomoApi.Proxy>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var screen by mutableStateOf(Screen.Main)
        private set

    enum class Screen { Main, Nodes }

    /** 节点延迟表:nodeName -> ms,-1 表示失败/未测 */
    var delays by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** 是否正在批量测延迟 */
    var testingDelays by mutableStateOf(false)
        private set
    /** 是否按延迟升序排序(true)还是原始顺序(false) */
    var sortByDelay by mutableStateOf(true)
        private set
    /** 是否正在更新订阅 */
    var updating by mutableStateOf(false)
        private set

    fun refreshShizuku() {
        shizukuState = when {
            !shizuku.isInstalled -> ShizukuState.NOT_INSTALLED
            !shizuku.isRunning -> ShizukuState.NOT_RUNNING
            !shizuku.hasPermission() -> ShizukuState.NO_PERMISSION
            else -> ShizukuState.READY
        }
        // 已授权但 service 未绑定时自动 bind
        if (shizukuState == ShizukuState.READY && service == null) {
            connectShizuku()
        }
        // 已绑定时刷新 mihomo 运行状态
        service?.let { isRunning = controller.isRunning(it) }
    }

    fun requestPermission() {
        shizuku.requestPermission { granted ->
            refreshShizuku()
        }
    }

    private fun connectShizuku() {
        shizuku.bind(
            onConnected = { s ->
                service = s
                shizukuState = ShizukuState.READY
                isRunning = controller.isRunning(s)
            },
            onFailed = { msg ->
                error = msg
            }
        )
    }

    fun setSubscriptionFromClipboard(text: String?) {
        if (!text.isNullOrBlank()) subscriptionUrl = text.trim()
    }

    fun startProxy() {
        val s = service ?: run { error = "Shizuku 未连接,请先授权"; return }
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) { error = "请输入订阅链接"; return }
        loading = true
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appendLog("下载订阅: $url")
                val raw = subscription.download(url)
                appendLog("订阅下载完成,${raw.length} 字节")
                val config = subscription.injectControllerConfig(raw)
                appendLog("配置生成完成,启动 mihomo...")
                controller.start(s, config)
                isRunning = true
                appendLog("启动成功,全局代理: ${MihomoController.PROXY_ADDR}")
            } catch (e: Exception) {
                error = "启动失败: ${e.message}"
                appendLog(controller.tailLog(s))
            } finally {
                loading = false
            }
        }
    }

    fun stopProxy() {
        val s = service ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                controller.stop(s)
                isRunning = false
                groups = emptyList()
                appendLog("已停止,代理已清除")
            } catch (e: Exception) {
                error = "停止失败: ${e.message}"
            }
        }
    }

    fun loadGroups() {
        if (!isRunning) { error = "mihomo 未运行"; return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                groups = api.getSelectorGroups()
                if (groups.isNotEmpty()) screen = Screen.Nodes
            } catch (e: Exception) {
                error = "加载节点失败: ${e.message} (mihomo 可能尚未就绪)"
            }
        }
    }

    fun selectNode(group: String, node: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = api.selectNode(group, node)
            if (ok) {
                groups = api.getSelectorGroups()
            } else {
                error = "切换节点失败"
            }
        }
    }

    fun backToMain() { screen = Screen.Main }

    fun clearError() { error = null }

    /**
     * 更新订阅:重新下载订阅 → 注入控制配置 → 写 config.yaml → 调 mihomo reload。
     * 不重启 mihomo 进程,不断流。
     */
    fun updateSubscription() {
        val s = service ?: run { error = "Shizuku 未连接"; return }
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) { error = "请输入订阅链接"; return }
        updating = true
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appendLog("更新订阅: $url")
                val raw = subscription.download(url)
                val config = subscription.injectControllerConfig(raw)
                val ok = controller.updateConfigAndReload(s, config)
                if (ok) {
                    appendLog("订阅更新成功,mihomo 已热重载")
                    // 刷新节点列表
                    groups = api.getSelectorGroups()
                    delays = emptyMap()
                } else {
                    error = "reload 失败,mihomo 可能需要重启"
                    appendLog(controller.tailLog(s))
                }
            } catch (e: Exception) {
                error = "更新订阅失败: ${e.message}"
            } finally {
                updating = false
            }
        }
    }

    /**
     * 测当前分组下所有节点延迟。
     * 串行测(并发会被机场限速),结果实时更新到 [delays]。
     */
    fun testAllDelays() {
        if (!isRunning || groups.isEmpty()) { error = "无可用节点"; return }
        if (testingDelays) return
        testingDelays = true
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 收集所有分组下的节点(去重)
                val allNodes = groups.flatMap { it.all }.distinct()
                appendLog("开始测 ${allNodes.size} 个节点延迟...")
                val result = mutableMapOf<String, Int>()
                for (node in allNodes) {
                    val d = api.testDelay(node)
                    result[node] = d
                    // 实时刷新
                    delays = result.toMap()
                }
                appendLog("延迟测试完成")
            } catch (e: Exception) {
                error = "测延迟失败: ${e.message}"
            } finally {
                testingDelays = false
            }
        }
    }

    /** 测单个节点延迟(点节点项时触发) */
    fun testSingleNode(node: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val d = api.testDelay(node)
            delays = delays.toMutableMap().apply { put(node, d) }
        }
    }

    /** 切换排序方式 */
    fun toggleSort() { sortByDelay = !sortByDelay }

    /**
     * 对某分组的节点列表按当前排序方式返回。
     * - sortByDelay=true:延迟升序,未测/失败的放最后
     * - sortByDelay=false:原始顺序
     */
    fun sortedNodes(group: MihomoApi.Proxy): List<Pair<String, Int?>> {
        val nodes = group.all
        return if (sortByDelay) {
            nodes.map { it to (delays[it] ?: null) }
                .sortedWith(compareBy(
                    { if (it.second == null || it.second!! < 0) 1 else 0 },
                    { it.second ?: Int.MAX_VALUE }
                ))
        } else {
            nodes.map { it to (delays[it] ?: null) }
        }
    }

    private fun appendLog(s: String) {
        val entry = s.take(800)
        log = (entry + "\n---\n" + log).take(2000)
    }
}
