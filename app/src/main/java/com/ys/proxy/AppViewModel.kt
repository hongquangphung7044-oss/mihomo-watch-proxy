package com.ys.proxy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * App 状态与业务编排。
 *
 * UI 只读这些 state 字段;调用方法触发业务;方法内部在 IO 线程执行。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val shizuku = ShizukuManager(app)
    private val controller = MihomoController(app)
    private val subscription = SubscriptionManager()
    private val subscriptionCache = SubscriptionCache(app)
    private val subStore = SubscriptionStore(app)
    private val prefs = app.getSharedPreferences("app_state", android.content.Context.MODE_PRIVATE)
    private val api = MihomoApi()
    private val indicator = ProxyIndicator(app)  // 诊断用,实际通知由 ForegroundService 管理

    companion object {
        private const val KEY_LAST_URL = "last_subscription_url"
        private const val KEY_SORT_BY_DELAY = "sort_by_delay"
    }

    /** 已保存的订阅列表(多订阅管理) */
    var savedSubscriptions by mutableStateOf<List<SavedSubscription>>(emptyList())
        private set

    /** 是否按延迟升序排序(true)还是原始顺序(false) */
    var sortByDelay by mutableStateOf(prefs.getBoolean(KEY_SORT_BY_DELAY, true))
        private set

    /** 当前订阅链接(输入框内容,持久化最后用过的) */
    var subscriptionUrl by mutableStateOf("")

    init {
        // 注册 Shizuku binder 监听:Shizuku 启动/死亡时自动刷新状态。
        // 关键修复:addBinderDeadListener 回调在 Shizuku 的 binder 线程触发,
        // 不是主线程!直接调 refreshShizuku() 会修改 Compose mutableStateOf,
        // 非主线程修改 state 会抛异常 → App 闪退。
        // 必须用 Handler(Looper.getMainLooper()).post 切回主线程。
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        shizuku.onStateChanged = { mainHandler.post { refreshShizuku() } }
        shizuku.registerBinderListeners()
        // 加载已保存的订阅列表
        savedSubscriptions = subStore.list()
        // 恢复上次使用的订阅链接(删后台重启不用重输)
        subscriptionUrl = prefs.getString(KEY_LAST_URL, "") ?: ""
        // 关键修复:App 启动时检测残留全局代理(不依赖 Shizuku)。
        // 场景:手表重启后 Shizuku/mihomo 都不自启,但 http_proxy 是持久化的,
        // 残留 127.0.0.1:7890 会导致整机网络瘫痪。这里提前警告用户。
        checkResidualProxyOnStartup()
    }

    /**
     * App 启动时检测残留全局代理(不依赖 Shizuku,普通 App 可读 Settings.Global)。
     * 如果检测到残留代理:
     *   - 有 WRITE_SECURE_SETTINGS 权限 → 直接自动清除(不依赖 Shizuku)
     *   - 无权限 → 警告用户,给 ADB 自救命令
     *
     * 异步执行:ContentResolver 读写不能在主线程做,会 ANR。
     */
    private fun checkResidualProxyOnStartup() {
        viewModelScope.launch(Dispatchers.IO) {
            val residual = getResidualHttpProxy() ?: return@launch
            if (hasSecureSettingsPermission()) {
                // 有权限,直接清!
                if (clearProxyViaSecureSettings()) {
                    appendLog("启动时检测到残留代理 $residual,已用 WRITE_SECURE_SETTINGS 自动清除")
                    return@launch  // 静默清除,不设 error
                }
            }
            // 无权限或清除失败,警告用户
            error = "检测到残留全局代理: $residual\n" +
                "如果网络无法使用,可能是代理残留导致。\n" +
                "若 Shizuku 已就绪,App 会自动清理;否则请用 ADB:\n" +
                "adb shell settings delete global http_proxy\n" +
                "永久免疫: adb shell pm grant com.ys.proxy android.permission.WRITE_SECURE_SETTINGS"
            appendLog("警告:检测到残留全局代理 $residual")
        }
    }

    enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    var shizukuState by mutableStateOf(ShizukuState.NOT_INSTALLED)
        private set
    var service by mutableStateOf<IWatchService?>(null)
        private set
    /** UserService 是否已绑定(独立于 shizukuState,精确反映可操作性) */
    val isBound: Boolean get() = service != null
    var isRunning by mutableStateOf(false)
        private set
    /**
     * 代理状态是否"未知"(Shizuku 不可用时无法检测 mihomo 是否在跑)。
     * true 时 UI 应显示警告,不能让用户误以为能正常停止。
     * 场景:Shizuku 被省电杀掉,但 mihomo 进程(shell 启动)可能还在跑,
     * http_proxy 也可能残留,App 没 shell 权限无法检测也无法清理。
     */
    var proxyStateUnknown by mutableStateOf(false)
        private set
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
    /** 诊断信息(展开后显示) */
    var diagnostic by mutableStateOf("")
        private set
    var showDiagnostic by mutableStateOf(false)
        private set

    enum class Screen { Main, Nodes }

    /** 节点延迟表:nodeName -> ms,-1 表示失败/未测 */
    var delays by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** 是否正在批量测延迟 */
    var testingDelays by mutableStateOf(false)
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
        // 检测 mihomo 是否在跑,只更新 isRunning 状态文字。
        // 不自动补启动 ForegroundService:避免每次打开 App 都弹通知让用户觉得"自动开启了"。
        // 用户主动点"启动"时才弹通知。
        // 必须异步:pgrep 走反射通道(Shizuku.newProcess)会起 shell 进程并 waitFor,
        // 同步执行会阻塞主线程导致 App 卡顿。
        if (shizukuState == ShizukuState.READY) {
            viewModelScope.launch(Dispatchers.IO) {
                val runner = getRunner() ?: return@launch
                val running = controller.isRunning(runner)
                isRunning = running
                proxyStateUnknown = false  // Shizuku 可用,状态明确
                // 清理残留:http_proxy 设置了但 mihomo 没在跑(旧 App 卸载/崩溃残留),
                // 必须清理,否则 OkHttp 直连下载订阅会走死代理 → "无法连接"
                if (!running) {
                    val proxy = try { runner("settings get global http_proxy") } catch (_: Exception) { "" }
                    if (proxy.isNotBlank() && proxy.contains(":") && !proxy.contains(":0")) {
                        try {
                            runner("settings delete global http_proxy")
                            runner("settings put global http_proxy :0")
                            appendLog("清理了残留的全局代理(旧 mihomo 未运行)")
                            // 清除启动时的残留代理警告
                            error = null
                        } catch (_: Exception) {}
                    }
                }
            }
        } else {
            // Shizuku 不可用(NOT_RUNNING/NO_PERMISSION/NOT_INSTALLED)
            // 关键修复(Bug 1):旧版不更新 isRunning,UI 仍显示"停止"按钮误导用户。
            // 现在标记代理状态未知,UI 显示警告。
            // 注意:不能直接把 isRunning 设为 false,因为 mihomo 可能还在跑(shell 进程独立于 Shizuku)。
            proxyStateUnknown = true
            // 检测残留 http_proxy 并尝试清理(异步,避免主线程 ANR)
            viewModelScope.launch(Dispatchers.IO) {
                val residual = getResidualHttpProxy() ?: return@launch
                if (hasSecureSettingsPermission()) {
                    // 有权限,直接清(不依赖 Shizuku)
                    if (clearProxyViaSecureSettings()) {
                        appendLog("Shizuku 不可用,用 WRITE_SECURE_SETTINGS 清除了残留代理: $residual")
                        error = null
                        proxyStateUnknown = false
                        isRunning = false  // 代理清了,mihomo 是否在跑不重要
                    } else {
                        // 清不动,警告用户
                        error = "Shizuku 不可用,检测到残留代理: $residual\n" +
                            "已授权 WRITE_SECURE_SETTINGS 但清除失败,请用 ADB:\n" +
                            "adb shell settings delete global http_proxy"
                    }
                } else {
                    // 无权限,警告用户
                    error = "⚠️ Shizuku 已断开,代理状态未知!\n" +
                        "检测到残留全局代理: $residual\n" +
                        "点'停止'可能无法清除代理。自救方法:\n" +
                        "1. 启动 Shizuku 后重新操作\n" +
                        "2. ADB: adb shell settings delete global http_proxy\n" +
                        "3. 永久免疫: adb shell pm grant com.ys.proxy android.permission.WRITE_SECURE_SETTINGS"
                }
            }
        }
    }

    /** 是否已尝试 bind(避免反复自动 bind) */
    private var bindAttempted = false

    fun requestPermission() {
        shizuku.requestPermission { granted ->
            bindAttempted = false  // 授权后允许重新尝试 bind
            refreshShizuku()
        }
    }

    private fun connectShizuku() {
        shizuku.bind(
            onConnected = { s ->
                service = s
                shizukuState = ShizukuState.READY
                isRunning = controller.isRunning { cmd -> s.exec(cmd) }
                appendLog("UserService 绑定成功")
            },
            onFailed = { msg ->
                // 自动 bind 失败不弹错误:32 位 Wear OS 上 UserService 本就难绑,
                // 反射通道(Shizuku.newProcess)仍可用,用户直接点'启动'即可。
                appendLog("UserService 自动绑定失败(不影响使用,反射模式仍可用): $msg")
                refreshDiagnostic()
            }
        )
    }

    /**
     * 获取可用的命令执行器。
     * 优先 UserService(已绑定);否则用反射 Shizuku.newProcess(备用通道,绕过 UserService)。
     * 只要 Shizuku 运行中+已授权,备用通道一定可用。
     */
    private fun getRunner(): CommandRunner? {
        service?.let { s -> return { cmd -> s.exec(cmd) } }
        if (shizuku.canExecDirectly) {
            return { cmd ->
                try {
                    shizuku.execViaReflection(cmd)
                } catch (e: Exception) {
                    "ERROR: ${e.message}"
                }
            }
        }
        return null
    }

    fun setSubscriptionFromClipboard(text: String?) {
        if (!text.isNullOrBlank()) subscriptionUrl = text.trim()
    }

    /** 保存订阅对话框:是否显示 + 编辑中的名字 */
    var showSaveDialog by mutableStateOf(false)
        private set
    var editingSubName by mutableStateOf("")
        private set

    /** 触发保存对话框(预填 URL 域名作默认名字) */
    fun saveCurrentSubscription() {
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) {
            error = "订阅链接为空,无法保存"
            return
        }
        editingSubName = try {
            java.net.URL(url).host
        } catch (e: Exception) {
            "订阅${savedSubscriptions.size + 1}"
        }
        showSaveDialog = true
    }

    /** 确认保存(用对话框里的名字) */
    fun confirmSaveSubscription() {
        val name = editingSubName.trim().ifEmpty { "未命名" }
        val url = subscriptionUrl.trim()
        if (url.isNotEmpty()) {
            subStore.save(name, url)
            savedSubscriptions = subStore.list()
            appendLog("已保存订阅: $name")
        }
        showSaveDialog = false
        editingSubName = ""
    }

    fun cancelSaveDialog() {
        showSaveDialog = false
        editingSubName = ""
    }

    fun updateEditingName(s: String) { editingSubName = s }

    /**
     * 载入已保存的订阅。
     * - 代理未运行:只更新输入框,用户再点启动
     * - 代理运行中:自动用新订阅重启 mihomo(热重载可能不生效,直接重启最稳)
     */
    fun loadSubscription(sub: SavedSubscription) {
        subscriptionUrl = sub.url
        appendLog("已载入订阅: ${sub.name}")
        if (isRunning) {
            appendLog("代理运行中,自动切换到新订阅...")
            // 直接调 startProxy(会先 stop 旧的再 start 新的)
            switchSubscription()
        }
    }

    /**
     * 切换订阅:不停止旧 mihomo,通过当前运行的代理下载新订阅,然后热重载。
     *
     * 关键:绝不能先停旧 mihomo!否则需要代理才能访问的订阅会下载失败
     * (典型场景:用直连订阅 A 开代理,切换到需代理的订阅 B,B 下载不了)。
     *
     * 流程:
     *   1. 通过当前 mihomo 代理(127.0.0.1:7890)下载新订阅
     *   2. 写新 config.yaml 到磁盘
     *   3. 调 mihomo API 热重载(PUT /configs?force=true),不停进程,不断流
     *   4. 清空延迟/节点缓存,用户重新加载即可看到新节点
     *
     * 失败处理:下载或重载失败时旧 mihomo 仍运行,旧代理仍可用,不影响当前网络。
     */
    private fun switchSubscription() {
        val runner = getRunner() ?: run {
            error = "无可用执行通道,请手动点启动"
            return
        }
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) { error = "订阅链接为空"; return }
        loading = true
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 通过当前运行的 mihomo 代理下载新订阅(关键:不先停旧代理!)
                appendLog("通过当前代理下载新订阅: $url")
                val raw = subscription.download(url, viaProxy = true)
                appendLog("新订阅下载完成,${raw.length} 字节,热重载 mihomo...")
                val config = subscription.injectControllerConfig(raw)
                // 热重载:不停 mihomo 进程,原子替换配置
                val ok = controller.updateConfigAndReload(runner, config)
                if (ok) {
                    appendLog("订阅切换成功,mihomo 已热重载")
                    delays = emptyMap()
                    groups = emptyList()  // 清空节点列表,用户重新点"选择节点"加载新订阅的节点
                    isRunning = true
                } else {
                    error = "热重载失败,mihomo 可能需要手动停止后重启"
                    appendLog(controller.tailLog(runner))
                }
            } catch (e: Exception) {
                // 下载或重载失败:旧 mihomo 仍在运行,旧代理仍可用,不影响当前网络
                error = "切换订阅失败: ${e.message}\n(旧订阅仍可用)"
                appendLog("切换失败,旧代理保持运行: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    /** 删除已保存的订阅 */
    fun deleteSubscription(name: String) {
        subStore.delete(name)
        savedSubscriptions = subStore.list()
        appendLog("已删除订阅: $name")
    }

    fun startProxy() {
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) { error = "请输入订阅链接"; return }
        // 持久化订阅链接,删后台重启不用重输
        prefs.edit().putString(KEY_LAST_URL, url).apply()
        loading = true
        error = null

        // 已绑定直接用;否则尝试 bind,成功后继续
        val existingRunner = getRunner()
        if (existingRunner != null) {
            doStartProxy(existingRunner, url)
            return
        }
        appendLog("Shizuku 反射通道不可用,尝试 UserService...")
        shizuku.bind(
            onConnected = { s ->
                service = s
                shizukuState = ShizukuState.READY
                appendLog("UserService 绑定成功")
                doStartProxy({ cmd -> s.exec(cmd) }, url)
            },
            onFailed = { msg ->
                loading = false
                error = "Shizuku 通道均不可用: $msg\n请确认 Shizuku 服务正在运行(不只是已安装)"
                refreshDiagnostic()
            }
        )
    }

    private fun doStartProxy(runner: CommandRunner, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 优先用本地缓存的订阅(避免每次启动都重新下载)
                var raw = subscriptionCache.load(url)
                if (raw != null) {
                    appendLog("使用缓存的订阅,${raw.length} 字节(如需更新点'更新订阅')")
                } else {
                    appendLog("首次下载订阅: $url")
                    raw = subscription.download(url)
                    appendLog("订阅下载完成,${raw.length} 字节")
                    subscriptionCache.save(url, raw)
                }
                val config = subscription.injectControllerConfig(raw)
                appendLog("配置生成完成,启动 mihomo...")
                controller.start(runner, config)
                // controller.start 内部已验证 9090 端口就绪,这里再确认 API 可调
                appendLog("验证 mihomo API...")
                var ready = false
                var lastErr: String? = null
                for (i in 1..6) {  // 最多再等 3 秒
                    try {
                        api.getProxies()
                        ready = true
                        break
                    } catch (e: Exception) {
                        lastErr = e.message
                        Thread.sleep(500)
                    }
                }
                if (!ready) {
                    // API 不通说明 mihomo 配置有问题,读日志给用户看
                    val log = controller.tailLog(runner, 40)
                    throw RuntimeException("mihomo API 无法连接: ${lastErr}\nmihomo 日志:\n$log")
                }
                isRunning = true
                appendLog("启动成功,API 就绪,全局代理: ${MihomoController.PROXY_ADDR}")
                // 显示表盘"运行中"指示器(三星手表底部圆圈) + 持久通知
                MihomoForegroundService.start(getApplication())
            } catch (e: Exception) {
                error = "启动失败: ${e.message}"
                appendLog(controller.tailLog(runner))
            } finally {
                loading = false
            }
        }
    }

    /** 手动重新连接 Shizuku UserService */
    fun reconnect() {
        error = null
        appendLog("手动重新连接 Shizuku...")
        shizuku.unbind()
        service = null
        bindAttempted = false
        shizuku.bind(
            onConnected = { s ->
                service = s
                shizukuState = ShizukuState.READY
                isRunning = controller.isRunning { cmd -> s.exec(cmd) }
                appendLog("重新连接成功")
            },
            onFailed = { msg ->
                error = "重连失败:\n$msg"
                appendLog("重新连接失败: $msg")
                refreshDiagnostic()
            }
        )
        refreshShizuku()
    }

    fun toggleDiagnostic() {
        showDiagnostic = !showDiagnostic
        if (showDiagnostic) refreshDiagnostic()
    }

    fun refreshDiagnostic() {
        diagnostic = shizuku.getDiagnostic()
    }

    /** 测试反射通道(Shizuku.newProcess),验证能否绕过 UserService 启动代理 */
    fun testReflection() {
        viewModelScope.launch(Dispatchers.IO) {
            shizuku.testReflection()
            diagnostic = shizuku.getDiagnostic()
        }
    }

    fun stopProxy() {
        val runner = getRunner()
        if (runner == null) {
            // runner 不可用(Shizuku 被杀/未启动)
            // 优先用 WRITE_SECURE_SETTINGS 权限清 http_proxy(避免整机网络瘫痪)
            if (hasSecureSettingsPermission()) {
                val cleared = clearProxyViaSecureSettings()
                if (cleared) {
                    appendLog("Shizuku 不可用,已用 WRITE_SECURE_SETTINGS 权限清除 http_proxy")
                    // mihomo 进程杀不掉(没 shell 权限),但 http_proxy 清了网络就恢复
                    // 下次启动 mihomo 时 pkill -9 会顺带清掉旧进程
                    MihomoForegroundService.stop(getApplication())
                    isRunning = false
                    groups = emptyList()
                    error = "代理已清除(http_proxy 已移除)。\n" +
                        "注意:mihomo 进程可能仍在后台(shell 权限才能杀),但不影响网络。\n" +
                        "Shizuku 恢复后可正常重启代理。"
                    return
                }
            }
            // 无 WRITE_SECURE_SETTINGS 权限,给用户自救指引
            val residualProxy = getResidualHttpProxy()
            error = if (residualProxy != null) {
                "Shizuku 通道不可用,无法停止代理!\n" +
                "检测到残留全局代理: $residualProxy\n" +
                "这会导致整机网络瘫痪(WiFi/蓝牙网络共享全失效)。\n\n" +
                "自救方法(任选其一):\n" +
                "1. 启动 Shizuku 服务后重新点'停止'\n" +
                "2. ADB 执行: adb shell settings delete global http_proxy\n" +
                "3. 一次性授权后永久免疫: adb shell pm grant com.ys.proxy android.permission.WRITE_SECURE_SETTINGS"
            } else {
                "Shizuku 通道不可用,无法停止代理。\n" +
                "请先启动 Shizuku 服务(打开 Shizuku App 按提示操作),再点'停止'。"
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 先停前台服务(通知消失)
                MihomoForegroundService.stop(getApplication())
                // 停 mihomo 进程 + 清代理(controller.stop 内部会验证清理结果)
                controller.stop(runner)
                // 等待 500ms 让进程真正退出,避免 refreshShizuku 误判还在跑
                Thread.sleep(500)
                isRunning = false
                groups = emptyList()
                appendLog("已停止,代理已清除")
            } catch (e: Exception) {
                // controller.stop 验证失败会抛异常,这里显示给用户
                // 即使停止失败,也更新一下状态(可能 mihomo 杀了但代理没清,或反之)
                isRunning = controller.isRunning(runner)
                // 如果 Shizuku 通道清代理失败,尝试用 WRITE_SECURE_SETTINGS 兜底
                if (!isRunning && hasSecureSettingsPermission()) {
                    if (clearProxyViaSecureSettings()) {
                        appendLog("Shizuku 通道清代理失败,已用 WRITE_SECURE_SETTINGS 兜底清除")
                        error = null
                        groups = emptyList()
                        return@launch
                    }
                }
                error = e.message ?: "停止失败"
                appendLog("停止异常: ${e.message}")
            }
        }
    }

    /**
     * 检测系统残留的全局 http 代理(不依赖 Shizuku)。
     * 用 Settings.Global API 读取,普通 App 权限即可读(写才需要 WRITE_SECURE_SETTINGS)。
     *
     * 用于:Shizuku 不可用时,仍能告知用户是否有残留代理导致网络瘫痪。
     *
     * @return 残留的代理地址(如 "127.0.0.1:7890"),无残留返回 null
     */
    private fun getResidualHttpProxy(): String? {
        return try {
            val proxy = android.provider.Settings.Global.getString(
                getApplication<Application>().contentResolver,
                android.provider.Settings.Global.HTTP_PROXY
            )
            // :0 或空 表示无代理
            if (proxy.isNullOrBlank() || proxy == ":0") null else proxy
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 检查 App 是否被授予 WRITE_SECURE_SETTINGS 权限。
     * 需通过 ADB 一次性授权:adb shell pm grant com.ys.proxy android.permission.WRITE_SECURE_SETTINGS
     * 授权后持久有效(重启不丢失),App 可直接清 http_proxy 不依赖 Shizuku。
     */
    private fun hasSecureSettingsPermission(): Boolean {
        return try {
            getApplication<Application>().checkSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 用 App 自己的 WRITE_SECURE_SETTINGS 权限清全局 http_proxy(不依赖 Shizuku)。
     *
     * 适用场景:
     *   - Shizuku 被系统省电杀掉,http_proxy 残留导致整机网络瘫痪
     *   - 手表重启后 Shizuku 不自启,但 http_proxy 持久化残留
     *   - stopProxy() 时 Shizuku runner 不可用
     *
     * @return true 清除成功,false 无权限或清除失败
     */
    private fun clearProxyViaSecureSettings(): Boolean {
        if (!hasSecureSettingsPermission()) return false
        return try {
            // Settings.Global.putString 清 http_proxy
            val ok1 = android.provider.Settings.Global.putString(
                getApplication<Application>().contentResolver,
                android.provider.Settings.Global.HTTP_PROXY,
                ":0"
            )
            // 验证清掉了
            val after = android.provider.Settings.Global.getString(
                getApplication<Application>().contentResolver,
                android.provider.Settings.Global.HTTP_PROXY
            )
            after.isNullOrBlank() || after == ":0"
        } catch (_: SecurityException) {
            false  // 权限被收回
        } catch (_: Exception) {
            false
        }
    }

    /** 测试通知能否显示(诊断指示器不显示问题) */
    fun testNotification() {
        indicator.test()
        appendLog("已发测试通知,请下拉查看通知栏")
    }

    fun loadGroups() {
        if (!isRunning) { error = "mihomo 未运行"; return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                groups = api.getSelectorGroups()
                if (groups.isNotEmpty()) screen = Screen.Nodes
            } catch (e: Exception) {
                // API 不通时检查 mihomo 是否还活着,给用户精确诊断
                val runner = getRunner()
                if (runner != null) {
                    val alive = controller.isRunning(runner)
                    if (!alive) {
                        isRunning = false
                        val log = controller.tailLog(runner, 30)
                        error = "mihomo 已崩溃,无法连接 API。日志:\n$log"
                    } else {
                        error = "无法连接 mihomo API: ${e.message}\n(进程在跑但 9090 端口不通,可能配置错误)"
                    }
                } else {
                    error = "无法连接 mihomo API: ${e.message}"
                }
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
        val runner = getRunner() ?: run { error = "无可用执行通道,请先点启动"; return }
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) { error = "请输入订阅链接"; return }
        updating = true
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appendLog("更新订阅: $url")
                val raw = subscription.download(url)
                subscriptionCache.save(url, raw)  // 更新缓存
                val config = subscription.injectControllerConfig(raw)
                val ok = controller.updateConfigAndReload(runner, config)
                if (ok) {
                    appendLog("订阅更新成功,mihomo 已热重载")
                    groups = api.getSelectorGroups()
                    delays = emptyMap()
                } else {
                    error = "reload 失败,mihomo 可能需要重启"
                    appendLog(controller.tailLog(runner))
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
     * 限流并发(5 路),既比串行快很多,又不至于被机场限速。
     * 结果实时更新到 [delays]。
     */
    fun testAllDelays() {
        if (!isRunning || groups.isEmpty()) { error = "无可用节点"; return }
        if (testingDelays) return
        testingDelays = true
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allNodes = groups.flatMap { it.all }.distinct()
                appendLog("开始测 ${allNodes.size} 个节点延迟(5 并发)...")
                val result = mutableMapOf<String, Int>()
                val done = AtomicInteger(0)
                // 用 Semaphore 限流 5 并发
                val sem = Semaphore(5)
                val jobs = allNodes.map { node ->
                    async {
                        sem.acquire()
                        try {
                            val d = api.testDelay(node)
                            synchronized(result) { result[node] = d }
                            // 每测完一个刷新一次 UI
                            delays = result.toMap()
                            done.incrementAndGet()
                        } finally {
                            sem.release()
                        }
                    }
                }
                awaitAll(*jobs.toTypedArray())
                appendLog("延迟测试完成 ${done.get()}/${allNodes.size}")
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

    /** 切换排序方式(持久化,重启 App 保留偏好) */
    fun toggleSort() {
        sortByDelay = !sortByDelay
        prefs.edit().putBoolean(KEY_SORT_BY_DELAY, sortByDelay).apply()
    }

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
