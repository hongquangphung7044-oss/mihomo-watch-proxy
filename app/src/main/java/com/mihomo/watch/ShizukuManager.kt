package com.mihomo.watch

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Shizuku 集成封装。
 *
 * 核心是 UserService:在 Shizuku 进程(shell 权限)里运行 WatchUserService,
 * 拿到 IWatchService 代理后,所有命令都以 shell 身份执行。
 *
 * 关键坑(Wear OS 32 位):
 *  Shizuku 默认用 64 位 app_process 启动 UserService 进程,
 *  但 Galaxy Watch7 国行系统是 32 位(armeabi-v7a),64 位 app_process 无法运行,
 *  导致 onNullBinding,UserService 绑定失败。
 *  解决:通过 UserServiceArgs 的隐藏参数 use_32_bit_app_process=true 强制 32 位。
 *
 * 重要概念区分:
 *  - Shizuku 已安装 ≠ Shizuku 已运行(需要 ADB 启动服务进程)
 *  - Shizuku 已运行 ≠ 已授权
 *  - 已授权 ≠ UserService 已绑定(32 位系统上必须设 use32BitAppProcess)
 */
class ShizukuManager(private val context: Context) {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 0xA001
        private const val BIND_TIMEOUT_MS = 8000L
    }

    private var service: IWatchService? = null
    private var conn: ServiceConnection? = null
    private var bindInProgress: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 反射设 use32BitAppProcess 的结果(诊断用) */
    private var reflect32BitResult: String = "未尝试"

    /** 反射通道(Shizuku.newProcess)测试结果(诊断用) */
    private var reflectionTestResult: String = "未测试"

    var onStateChanged: (() -> Unit)? = null

    private val userServiceArgs: Shizuku.UserServiceArgs = run {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, WatchUserService::class.java.name)
        )
            .processNameSuffix("mihomo_service")
            .debuggable(BuildConfig.DEBUG)
            .version(4)  // 再次升级,强制 Shizuku 丢弃旧 UserService 进程

        // 关键:32 位系统必须用 32 位 app_process 启动 UserService 进程。
        // Galaxy Watch7 国行是 32 位(armeabi-v7a),Shizuku 默认用 64 位 app_process,
        // 会导致 WatchUserService 类加载失败 → onNullBinding。
        //
        // Shizuku.UserServiceArgs.use32BitAppProcess(true) 是 private 方法,
        // 通过反射设 use32BitAppProcess 字段。
        reflect32BitResult = try {
            val field = Shizuku.UserServiceArgs::class.java
                .getDeclaredField("use32BitAppProcess")
            field.isAccessible = true
            field.setBoolean(args, true)
            "成功(use32BitAppProcess=true)"
        } catch (e: Exception) {
            "失败: ${e.javaClass.simpleName}: ${e.message}"
        }
        args
    }

    fun registerBinderListeners() {
        try {
            Shizuku.addBinderReceivedListener { onStateChanged?.invoke() }
            Shizuku.addBinderDeadListener {
                service = null
                conn = null
                bindInProgress = false
                onStateChanged?.invoke()
            }
        } catch (e: Exception) {
        }
    }

    val isInstalled: Boolean
        get() = try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: Exception) {
            false
        }

    /** Shizuku 服务是否正在运行(binder 是否存活) */
    val isRunning: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }

    fun hasPermission(): Boolean = try {
        if (Shizuku.isPreV11()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    val isBound: Boolean get() = service != null

    val version: String
        get() = try {
            Shizuku.getVersion().toString()
        } catch (e: Exception) {
            "unknown"
        }

    /** 当前 Shizuku 运行权限:0=root,2000=shell */
    val uid: Int
        get() = try { Shizuku.getUid() } catch (e: Exception) { -1 }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                    Shizuku.removeRequestPermissionResultListener(this)
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            onResult(false)
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    /** 最近一次 bind 失败的原因(诊断用) */
    private var lastBindError: String = "未尝试 bind"

    fun bind(onConnected: (IWatchService) -> Unit, onFailed: (String) -> Unit) {
        if (!isRunning) {
            lastBindError = "Shizuku 服务未运行(pingBinder=false)"
            onFailed(lastBindError)
            return
        }
        if (!hasPermission()) {
            lastBindError = "未授权 Shizuku"
            onFailed(lastBindError)
            return
        }
        service?.let { s ->
            onConnected(s)
            return
        }
        if (bindInProgress) {
            onFailed("正在连接 UserService,请稍等")
            return
        }

        var finished = false
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (finished) return
                finished = true
                bindInProgress = false
                lastBindError = "无(已连接)"
                service = IWatchService.Stub.asInterface(binder)
                service?.let { onConnected(it) }
                onStateChanged?.invoke()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                lastBindError = "onServiceDisconnected(连接中断)"
                onStateChanged?.invoke()
            }

            override fun onBindingDied(name: ComponentName) {
                if (finished) return
                finished = true
                bindInProgress = false
                service = null
                conn = null
                lastBindError = "onBindingDied(binder 死亡,Shizuku 服务可能被杀或重启)"
                onFailed("Shizuku binder 死亡。请打开 Shizuku App 确认服务在运行,或重启 Shizuku 服务")
                onStateChanged?.invoke()
            }

            override fun onNullBinding(name: ComponentName) {
                if (finished) return
                finished = true
                bindInProgress = false
                lastBindError = "onNullBinding(Shizuku 启动了 UserService 进程,但 WatchUserService.onBind 返回 null 或类加载失败)"
                onFailed("UserService 绑定返回 null。\n可能原因:\n1. 32 位反射未生效(看诊断)\n2. WatchUserService 类在 Shizuku 进程加载失败\n3. AIDL 文件问题")
            }
        }
        conn = c
        bindInProgress = true
        lastBindError = "bind 已发起,等待回调中..."
        try {
            Shizuku.bindUserService(userServiceArgs, c)
        } catch (e: Exception) {
            finished = true
            bindInProgress = false
            conn = null
            lastBindError = "bindUserService 抛异常: ${e.javaClass.simpleName}: ${e.message}"
            onFailed("bindUserService 异常: ${e.message}")
            return
        }
        mainHandler.postDelayed({
            if (!finished && service == null) {
                finished = true
                bindInProgress = false
                lastBindError = "超时(8秒无回调)。Shizuku 可能没真正启动 UserService 进程"
                onFailed("连接 UserService 超时(8秒无回调)")
                onStateChanged?.invoke()
            }
        }, BIND_TIMEOUT_MS)
    }

    fun getDiagnostic(): String = buildString {
        append("=== Shizuku 状态 ===\n")
        append("已安装: $isInstalled\n")
        append("运行中(pingBinder): $isRunning\n")
        append("已授权: ${hasPermission()}\n")
        append("UserService 已绑定: $isBound\n")
        append("bind 进行中: $bindInProgress\n")
        append("Shizuku 版本: $version\n")
        append("Shizuku UID(0=root/2000=shell): $uid\n")
        append("\n=== 32 位反射 ===\n")
        append("use32BitAppProcess: $reflect32BitResult\n")
        append("(注:纯 32 位系统上此字段无效,app_process32 不存在则回退默认)\n")
        append("\n=== bind 失败原因 ===\n")
        append(lastBindError)
        append("\n\n=== 反射通道(Shizuku.newProcess)测试 ===\n")
        append(reflectionTestResult)
        append("\n\n=== 本 App ===\n")
        append("包名: ${context.packageName}\n")
        append("UserService 类: ${WatchUserService::class.java.name}\n")
        append("UserServiceArgs.version: 4\n")
        append("\n=== 排查建议 ===\n")
        if (!isInstalled) {
            append("→ 请安装 Shizuku\n")
        } else if (!isRunning) {
            append("→ Shizuku 服务未运行!用 ADB 执行:\n")
            append("  adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n")
        } else if (!hasPermission()) {
            append("→ 在 Shizuku App 给本 App 授权\n")
        } else {
            append("→ Shizuku 已就绪。即使 UserService 绑定失败,反射通道仍可启动代理。\n")
            append("→ 点'测试反射'验证;成功后直接点'启动'即可(自动走反射模式)。\n")
        }
    }

    /**
     * 测试反射通道(Shizuku.newProcess):跑 id + pgrep mihomo,验证能否以 shell 身份执行命令。
     * 这是 UserService 不可用时的备用通道,只要 Shizuku 运行中+已授权就一定能用。
     */
    fun testReflection() {
        reflectionTestResult = try {
            if (!isRunning) {
                "失败:Shizuku 服务未运行"
            } else if (!hasPermission()) {
                "失败:未授权 Shizuku"
            } else {
                val id = execViaReflection("id")
                val pgrep = execViaReflection("pgrep -f mihomo 2>/dev/null || echo NONE")
                "成功!\n  id: ${id.trim().take(120)}\n  mihomo 进程: ${pgrep.trim().take(80)}\n" +
                    "→ 反射通道可用,可点击'启动'启动代理(无需 UserService 绑定)"
            }
        } catch (e: Exception) {
            "失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 备用通道:反射调 Shizuku.newProcess 直接执行命令。
     * 完全绕过 UserService(Whizuku 手表版可能不支持 bindUserService)。
     *
     * Shizuku.newProcess 是 private static 方法,内部:
     *   1. 调 requireService() 拿 IShizukuService(service 字段,binder received 时设置)
     *   2. 调 service.newProcess(cmd, env, dir) 通过 binder 在 Shizuku 进程执行
     *   3. 返回 ShizukuRemoteProcess(继承 Process,stdin/stdout/stderr 都可用)
     *
     * 如果 service 字段为 null(binder 还没接收),手动触发 onBinderReceived。
     */
    fun execViaReflection(cmd: String): String {
        // 确保 service 字段已设置(binder received 时自动设置,但可能还没触发)
        ensureServiceField()

        // 反射调 Shizuku.newProcess(String[] cmd, String[] env, String dir)
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return out + err
    }

    /** 确保 Shizuku.service 字段已设置(binder received 时设置,可能需要手动触发) */
    private fun ensureServiceField() {
        try {
            val serviceField = Shizuku::class.java.getDeclaredField("service")
            serviceField.isAccessible = true
            if (serviceField.get(null) == null) {
                // service 为 null,用 getBinder() 手动触发 onBinderReceived
                val binder = Shizuku.getBinder()
                if (binder != null) {
                    val onBinderReceived = Shizuku::class.java.getDeclaredMethod(
                        "onBinderReceived",
                        IBinder::class.java,
                        String::class.java
                    )
                    onBinderReceived.isAccessible = true
                    onBinderReceived.invoke(null, binder, context.packageName)
                }
            }
        } catch (e: Exception) {
            // 忽略,继续尝试 newProcess(可能 service 已设置)
        }
    }

    /** 备用通道是否可用(Shizuku 运行中 + 已授权) */
    val canExecDirectly: Boolean
        get() = isRunning && hasPermission()

    fun getService(): IWatchService? = service

    fun unbind() {
        conn?.let {
            try {
                Shizuku.unbindUserService(userServiceArgs, it, true)
            } catch (_: Exception) {
            }
        }
        conn = null
        service = null
        bindInProgress = false
    }
}
