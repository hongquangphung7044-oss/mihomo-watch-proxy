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
        // 关键修复:addBinderDeadListener 回调在 binder 线程触发,
        // 直接修改 Compose state 会闪退,必须切回主线程。
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        shizuku.onStateChanged = { mainHandler.post { refreshShizuku() } }
        shizuku.registerBinderListeners()
        // 加载已保存的订阅列表
        savedSubscriptions = subStore.list()
        // 恢复上次使用的订阅链接(删后台重启不用重输)
        subscriptionUrl = prefs.getString(KEY_LAST_URL, "") ?: ""
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
    var log by mutableStateOf("")
        private set
    var groups by mutableStateOf<List<MihomoApi.Proxy>>(emptyList())
        private set

    /**
     * 所有 proxy 的完整快照(含落地节点 + 所有分组),用于精准判断引用关系。
     * 仅在 loadGroups 时更新,filteredGroups 读它做过滤判断。
     */
    private var allProxiesSnapshot: Map<String, MihomoApi.Proxy> = emptyMap()

    /**
     * 过滤后的分组列表(只展示含落地节点的分组,过滤引用型分组)。
     *
     * 背景:机场订阅的 proxies 通常包含:
     *  - PROXY / 🚀 节点选择:all 是落地节点列表(SS/VMess/Trojan...),用户能挑
     *  - YouTube / Apple / Google:all 里全是引用其他分组(如 ["PROXY"] 或 ["🚀 节点选择"]),
     *    在手表上切这种分组没意义(只是改了分流路径,不是选落地节点)
     *
     * 严格过滤规则(用 allProxiesSnapshot 而非 groups):
     *  一个 Selector 分组是"落地节点分组" ⟺ 它的 all 里**至少有一个元素**
     *  在 allProxiesSnapshot 中不是 Selector/URLTest/Fallback 类型(即落地节点,
     *  type 是 Shadowsocks/Vmess/Trojan/Hysteria 等具体协议)。
     *
     * 这样能精准区分:落地节点(SS/VMess) vs 分组引用(Selector/URLTest/Fallback 的 name)。
     * 之前用 groups.map { it.name } 判断是错的 —— 落地节点根本不在 groups 里
     * (groups 只含 Selector),所以 YouTube 分组的 all=["PROXY"] 会因为
     * "PROXY" 不在落地节点 name 集合里而被误判为落地节点分组。
     *
     * 用户反馈"还是多个分组"就是因为这个逻辑漏洞。
     */
    val filteredGroups: List<MihomoApi.Proxy>
        get() {
            if (groups.isEmpty()) return emptyList()
            // 所有 type 是分组类型(Selector/URLTest/Fallback/LoadBalance)的 name
            // → 这些是"分组引用",不是落地节点
            val groupReferenceNames = allProxiesSnapshot.values
                .filter { it.type in setOf("Selector", "URLTest", "Fallback", "LoadBalance") }
                .map { it.name }
                .toHashSet()
            return groups.filter { group ->
                // 至少有一个 all 元素不是分组引用 → 是真正的落地节点
                group.all.any { it !in groupReferenceNames }
            }
        }
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

    /**
     * 扁平化的所有落地节点列表(去重)。
     *
     * 用户反馈"要直接显示所有节点,不要按分组"。这里直接从 mihomo 返回的所有
     * proxies 中按 type 过滤出落地节点,UI 直接 items(allNodes) 渲染单行胶囊即可。
     *
     * 关键修复(对标 FlClash/NekoBox + 解决 v1.0.58 之后回归的"部分/全部节点测不出来"):
     *  旧实现只从 type==Selector 的分组(groups)的 all 字段收集,再过滤掉分组引用名。
     *  但很多机场(尤其 subconverter 转换的 clash 订阅)结构是:
     *
     *    PROXY (Selector) all=["♻️ 自动选择"]                ← 只引用 URLTest 分组
     *    ♻️ 自动选择 (URLTest) all=["节点1","节点2",...]      ← 真正落地节点在这
     *
     *  旧逻辑:
     *   - groups 只含 PROXY(Selector)
     *   - PROXY.all=["♻️ 自动选择"] 全是分组引用 → 被过滤掉
     *   - ♻️ 自动选择 是 URLTest 不在 groups 里 → 它的 all 不会被遍历
     *   - 结果:allNodes 空!UI 一个节点都不显示!
     *
     *  这正是 wd-purple 订阅"完全测不出来"的根因 —— 不是测速失败,是节点没显示。
     *
     *  新逻辑(对标 FlClash):直接按 type 过滤,不依赖分组结构:
     *   - 排除分组类型(Selector/URLTest/Fallback/LoadBalance/Relay)
     *   - 排除内置类型(Direct/Reject/Pass/Compatible)
     *   - 剩下的就是落地节点(SS/VMess/Trojan/Hysteria/Vless/Tuic/WireGuard...)
     *
     *  这样不管机场怎么组织分组,所有落地节点都能显示出来,无遗漏无重复。
     */
    val allNodes: List<String>
        get() {
            if (allProxiesSnapshot.isEmpty()) return emptyList()
            // 排除分组类型 + 内置类型,剩下的就是落地节点
            val nonNodeTypes = setOf(
                "Selector", "URLTest", "Fallback", "LoadBalance", "Relay",
                "Direct", "Reject", "Pass", "Compatible"
            )
            return allProxiesSnapshot.values
                .filter { it.type !in nonNodeTypes }
                .map { it.name }
                .distinct()
        }

    /**
     * 节点当前所属的分组名(用于显示)。
     * 一个节点可能被多个分组引用,这里返回第一个找到的分组名。
     */
    fun nodeGroup(nodeName: String): String? {
        return groups.firstOrNull { it.all.contains(nodeName) }?.name
    }

    /**
     * 用户当前选中的节点(扁平化视图下)。
     *
     * 用于解决"多个黄点"问题:订阅里多个 Selector 分组各自有默认选中节点
     * (PROXY 选了 A,🚀 节点选择 选了 B,🎮 游戏平台 选了 C...),
     * 用户点节点后,需要区分"自己选的" vs "分组默认的"。
     *
     * 用户选过的节点 → userSelectedNode 记下,UI 用琥珀金高亮(强)
     * 其他分组默认选中的节点 → UI 用次要色标黄点(弱)
     * 未选中节点 → 无标记
     */
    private var userSelectedNode: String? = null

    /**
     * 节点选中状态(三态)。
     *
     *  - USER_SELECTED:用户刚选的(强高亮,琥珀金背景)
     *  - GROUP_DEFAULT:分组默认选中(弱标记,灰色小圆点)
     *  - NONE:未选中
     *
     * 这样用户能一眼看出哪个是自己选的,哪些是订阅分组的默认选择。
     */
    enum class NodeSelectedState { USER_SELECTED, GROUP_DEFAULT, NONE }

    fun nodeSelectedState(nodeName: String): NodeSelectedState {
        val userSel = userSelectedNode
        if (userSel != null) {
            return if (nodeName == userSel) NodeSelectedState.USER_SELECTED else NodeSelectedState.NONE
        }
        // 用户未选过:显示所有分组默认选中的节点(让用户看到分组当前状态)
        return if (groups.any { it.now == nodeName }) NodeSelectedState.GROUP_DEFAULT else NodeSelectedState.NONE
    }

    /** 兼容旧调用(等价于 state != NONE) */
    fun isNodeActive(nodeName: String): Boolean {
        return nodeSelectedState(nodeName) != NodeSelectedState.NONE
    }

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
                // 清理残留:http_proxy 设置了但 mihomo 没在跑(旧 App 卸载/崩溃残留),
                // 必须清理,否则 OkHttp 直连下载订阅会走死代理 → "无法连接"
                if (!running) {
                    val proxy = try { runner("settings get global http_proxy") } catch (_: Exception) { "" }
                    if (proxy.isNotBlank() && proxy.contains(":") && !proxy.contains(":0")) {
                        try {
                            runner("settings delete global http_proxy")
                            runner("settings put global http_proxy :0")
                            appendLog("清理了残留的全局代理(旧 mihomo 未运行)")
                        } catch (_: Exception) {}
                    }
                }
            }
        } else {
            // Shizuku 不可用:isRunning 无法检测,但 http_proxy 可能残留。
            // 谨慎修复:只读 Settings.Global 检测残留代理并警告(用 ContentResolver,
            // 普通 App 可读,不需要 Shizuku)。不在 init 块执行,只在这里异步执行。
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val cr = getApplication<Application>().contentResolver
                    val proxy = android.provider.Settings.Global.getString(
                        cr, android.provider.Settings.Global.HTTP_PROXY
                    )
                    if (!proxy.isNullOrBlank() && proxy.contains(":") && !proxy.contains(":0")) {
                        // 切回主线程修改 Compose state
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        mainHandler.post {
                            error = "⚠️ Shizuku 已断开,检测到残留代理: $proxy\n" +
                                "点'停止'可能无法清除。自救方法:\n" +
                                "1. 启动 Shizuku 后重新操作\n" +
                                "2. ADB: adb shell settings delete global http_proxy"
                        }
                    }
                } catch (_: Exception) {
                    // 读取失败不影响 App 运行
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

    // ── 长按菜单(更新 / 重命名) ──────────────────────────────────────────

    /** 长按某条已保存订阅时,该字段非 null → 弹出操作菜单 */
    var actionSub by mutableStateOf<SavedSubscription?>(null)
        private set

    /** 打开长按操作菜单 */
    fun startSubAction(sub: SavedSubscription) { actionSub = sub }
    /** 关闭长按操作菜单 */
    fun cancelSubAction() { actionSub = null }

    /**
     * 更新已保存的订阅(长按菜单"更新"项):
     * - 代理运行中:走 [updateSubscription] 热重载(不停 mihomo)
     * - 代理未运行:仅重新下载并缓存订阅,等用户启动时直接用缓存
     *
     * 注意:需要代理才能下载的订阅,必须在代理运行中时更新
     * (通过当前 mihomo 代理下载)。代理未运行时直连下载会失败。
     */
    fun updateSavedSubscription(sub: SavedSubscription) {
        actionSub = null
        subscriptionUrl = sub.url
        if (isRunning) {
            appendLog("更新订阅(热重载): ${sub.name}")
            updateSubscription()
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    updating = true
                    appendLog("代理未运行,直接下载并缓存: ${sub.name}")
                    val raw = subscription.download(sub.url)
                    subscriptionCache.save(sub.url, raw)
                    appendLog("订阅缓存已更新: ${sub.name}")
                } catch (e: Exception) {
                    error = "更新订阅失败: ${e.message}\n(如该订阅需要代理,请先启动代理后再更新)"
                } finally {
                    updating = false
                }
            }
        }
    }

    // ── 重命名 ────────────────────────────────────────────────────────────

    /** 重命名中的订阅(非 null 时弹出重命名对话框) */
    var renamingSub by mutableStateOf<SavedSubscription?>(null)
        private set
    /** 重命名对话框输入内容 */
    var renameText by mutableStateOf("")
        private set

    fun startRename(sub: SavedSubscription) {
        actionSub = null
        renamingSub = sub
        renameText = sub.name
    }
    fun updateRenameText(s: String) { renameText = s }
    fun cancelRename() {
        renamingSub = null
        renameText = ""
    }
    fun confirmRename() {
        val sub = renamingSub ?: return
        val newName = renameText.trim()
        if (newName.isEmpty()) { error = "名称不能为空"; return }
        subStore.rename(sub.name, newName)
        savedSubscriptions = subStore.list()
        appendLog("已重命名: ${sub.name} → $newName")
        renamingSub = null
        renameText = ""
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
            // Shizuku 不可用,无法杀 mihomo 进程,但可以提示用户
            error = "⚠️ Shizuku 通道不可用,无法停止代理!\n" +
                "mihomo 进程和 http_proxy 可能残留。\n\n" +
                "自救方法(任选其一):\n" +
                "1. 启动 Shizuku 服务后重新点'停止'\n" +
                "2. ADB: adb shell settings delete global http_proxy\n" +
                "3. ADB 杀进程: adb shell pkill -9 -x mihomo"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 先停前台服务(通知消失)
                MihomoForegroundService.stop(getApplication())
                // 停 mihomo 进程(SIGKILL 确保立即退出,避免状态残留)
                controller.stop(runner)
                // 等待 500ms 让进程真正退出,避免 refreshShizuku 误判还在跑
                Thread.sleep(500)
                isRunning = false
                groups = emptyList()
                userSelectedNode = null  // 停止代理清空用户选择
                appendLog("已停止,代理已清除")
            } catch (e: Exception) {
                error = "停止失败: ${e.message}"
            }
        }
    }

    /** 测试通知能否显示(诊断指示器不显示问题) */
    fun testNotification() {
        indicator.test()
        appendLog("已发测试通知,请下拉查看通知栏")
    }

    fun loadGroups() {
        if (!isRunning) { error = "mihomo 未运行"; return }
        // 切换订阅/重新加载时清空用户选择,回到"显示分组默认选择"模式
        userSelectedNode = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 先取完整快照(含落地节点 + 所有分组),用于 filteredGroups 精准过滤
                allProxiesSnapshot = api.getProxies()
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
                allProxiesSnapshot = api.getProxies()
                groups = api.getSelectorGroups()
            } else {
                error = "切换节点失败"
            }
        }
    }

    /**
     * 扁平化视图下切换节点:把所有包含该节点的 Selector 分组都切到该节点。
     *
     * 关键修复(对标 FlClash/NekoBox + 解决"点节点 IP 不变"问题):
     *
     * 旧逻辑只切一个"主分组"(GLOBAL/PROXY 优先),但这在很多订阅结构下切不动 IP:
     *  - 订阅里有多个 Selector 分组(PROXY、🚀 节点选择、🎮 游戏平台、📺 流媒体...)
     *    各自选了不同节点 → UI 上多个节点都有黄点(用户报告的现象)
     *  - mihomo 的 rule 最终匹配到哪个分组,流量就走那个分组的 now
     *  - 你只切 PROXY,但实际规则走的是 🚀 节点选择 → IP 不变
     *
     * 新逻辑(对标 FlClash 的"全局选择"行为):
     *  - 找到所有 all 包含该节点的 Selector 分组
     *  - 逐个调 API 切换(并发,API 幂等所以并发安全)
     *  - 任一切失败不阻断其他切换,但记录错误
     *  - 全部切完后重新拉取状态校正 UI
     *
     * 这样不管 mihomo 的规则匹配到哪个分组,出口都是你选的节点。
     * 对于 URLTest/Fallback 分组(它们不在 groups 里)无法切换,但 Selector 切完
     * 后 URLTest 的 now 通常也会被 mihomo 重新测速选择(如果订阅规则没走 URLTest)。
     */
    fun selectAnyNode(nodeName: String) {
        val targetGroups = groups.filter { it.all.contains(nodeName) }
        if (targetGroups.isEmpty()) {
            error = "节点 $nodeName 不在任何 Selector 分组中"
            return
        }

        // 记下用户选的节点,UI 用琥珀金高亮(与分组默认选中的灰色区分)
        userSelectedNode = nodeName

        // 乐观更新:立即把所有相关 Selector 分组的 now 都改成 nodeName,UI 即时响应
        groups = groups.map { g ->
            if (g.all.contains(nodeName)) g.copy(now = nodeName) else g
        }

        viewModelScope.launch(Dispatchers.IO) {
            var failedGroups = mutableListOf<String>()
            // 并发切换所有相关 Selector 分组(API 幂等,并发安全)
            val results = targetGroups.map { g ->
                async {
                    g.name to api.selectNode(g.name, nodeName)
                }
            }.awaitAll()
            for ((gName, ok) in results) {
                if (!ok) failedGroups.add(gName)
            }
            // 重新拉取真实状态,校正乐观更新(无论成败都拉,确保 UI 准确)
            try {
                allProxiesSnapshot = api.getProxies()
                groups = api.getSelectorGroups()
            } catch (_: Exception) {}
            if (failedGroups.isNotEmpty()) {
                error = "部分分组切换失败: ${failedGroups.joinToString(", ")}"
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
     * 测所有节点延迟(扁平化,不分分组)。
     * 限流并发(10 路),既比串行快很多,又不至于被机场限速。
     * 结果分批更新 UI(每 800ms 一次),避免节点多时频繁重组导致闪退。
     *
     * 用 allNodes(扁平化所有落地节点)而非 groups.flatMap { it.all },
     * 避免重复测同一个节点(一个节点可能被多个分组引用)。
     */
    fun testAllDelays() {
        if (!isRunning || groups.isEmpty()) { error = "无可用节点"; return }
        if (testingDelays) return
        val nodes = allNodes
        if (nodes.isEmpty()) { error = "无可用节点"; return }
        testingDelays = true
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appendLog("开始测 ${nodes.size} 个节点延迟(10 并发)...")
                val result = mutableMapOf<String, Int>()
                val done = AtomicInteger(0)
                val total = nodes.size
                val sem = Semaphore(10)
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val lastUiUpdate = java.util.concurrent.atomic.AtomicLong(0L)
                val uiInterval = 800L
                val jobs = nodes.map { node ->
                    async {
                        sem.acquire()
                        try {
                            val d = api.testDelay(node)
                            synchronized(result) { result[node] = d }
                            done.incrementAndGet()
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate.get() > uiInterval) {
                                lastUiUpdate.set(now)
                                val snapshot = synchronized(result) { result.toMap() }
                                mainHandler.post { delays = snapshot }
                            }
                        } finally {
                            sem.release()
                        }
                    }
                }
                awaitAll(*jobs.toTypedArray())
                val finalSnapshot = synchronized(result) { result.toMap() }
                mainHandler.post { delays = finalSnapshot }
                appendLog("延迟测试完成 ${done.get()}/${total}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Activity 销毁时 viewModelScope 取消,awaitAll 会抛 CancellationException,
                // 应当重抛而非当成业务错误显示给用户
                throw e
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
            // 切回主线程修改 Compose state,避免闪退
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post { delays = delays.toMutableMap().apply { put(node, d) } }
        }
    }

    /** 切换排序方式(持久化,重启 App 保留偏好) */
    fun toggleSort() {
        sortByDelay = !sortByDelay
        prefs.edit().putBoolean(KEY_SORT_BY_DELAY, sortByDelay).apply()
    }

    /**
     * 对所有节点(扁平化)按当前排序方式返回。
     * - sortByDelay=true:延迟升序,未测/失败的放最后
     * - sortByDelay=false:原始顺序
     */
    fun sortedAllNodes(): List<Pair<String, Int?>> {
        val nodes = allNodes
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

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 追加日志。修复:必须切回主线程写 mutableStateOf,
     * 之前在 IO 线程直接写违反 Compose 契约,长期会触发异常/闪退。
     */
    private fun appendLog(s: String) {
        val entry = s.take(800)
        mainHandler.post {
            log = (entry + "\n---\n" + log).take(2000)
        }
    }

    /**
     * 修复(对标开源项目):ViewModel 销毁时必须注销 Shizuku binder listeners,
     * 否则 Shizuku 单例永久持有 AppViewModel 引用 → 内存泄漏 + 已销毁 state 回调闪退。
     */
    override fun onCleared() {
        super.onCleared()
        shizuku.unregisterBinderListeners()
        shizuku.unbind()
    }
}
