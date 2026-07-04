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
 * 提供两种执行 shell 命令的方式:
 *  1. UserService (首选):bindUserService 拿到 IWatchService 代理,方法调用式
 *  2. Shizuku.newProcess (备用):直接执行命令,不依赖 UserService 绑定
 *
 * 重要概念区分(用户常混淆):
 *  - Shizuku 已安装 ≠ Shizuku 已运行(需要 ADB 启动服务进程)
 *  - Shizuku 已运行 ≠ 已授权(需要在 Shizuku App 给本 App 授权)
 *  - 已授权 ≠ UserService 已绑定(bindUserService 是异步,可能失败)
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

    var onStateChanged: (() -> Unit)? = null

    private val userServiceArgs: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, WatchUserService::class.java.name)
        )
            .processNameSuffix("mihomo_service")
            .debuggable(BuildConfig.DEBUG)
            .version(2)  // version 升级,强制 Shizuku 重新加载 UserService

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

    /** binder 是否存活(精确检查,独立于 isRunning 的异常吞掉) */
    val isBinderAlive: Boolean
        get() = try {
            Shizuku.isBinderAlive()
        } catch (e: Exception) {
            false
        }

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

    fun bind(onConnected: (IWatchService) -> Unit, onFailed: (String) -> Unit) {
        if (!isRunning) {
            onFailed("Shizuku 服务未运行。请确认 Shizuku App 显示'正在运行'")
            return
        }
        if (!hasPermission()) {
            onFailed("未授权 Shizuku")
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
                service = IWatchService.Stub.asInterface(binder)
                service?.let { onConnected(it) }
                onStateChanged?.invoke()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                onStateChanged?.invoke()
            }

            override fun onBindingDied(name: ComponentName) {
                if (finished) return
                finished = true
                bindInProgress = false
                service = null
                conn = null
                onFailed("Shizuku binder 死亡")
                onStateChanged?.invoke()
            }

            override fun onNullBinding(name: ComponentName) {
                if (finished) return
                finished = true
                bindInProgress = false
                onFailed("UserService 绑定返回 null(Shizuku 加载 WatchUserService 失败)")
            }
        }
        conn = c
        bindInProgress = true
        try {
            Shizuku.bindUserService(userServiceArgs, c)
        } catch (e: Exception) {
            finished = true
            bindInProgress = false
            conn = null
            onFailed("bindUserService 异常: ${e.message}")
            return
        }
        mainHandler.postDelayed({
            if (!finished && service == null) {
                finished = true
                bindInProgress = false
                onFailed("连接 UserService 超时(8秒)。可改用'备用模式(直接执行)'绕过 UserService")
                onStateChanged?.invoke()
            }
        }, BIND_TIMEOUT_MS)
    }

    /**
     * 备用通道:直接用 Shizuku.newProcess 执行 shell 命令。
     * 不依赖 bindUserService,即使 UserService 绑定失败也能用。
     * 返回 stdout+stderr 合并文本。
     */
    fun execViaShizuku(cmd: String): String {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            out + err
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /** 备用通道是否可用(Shizuku 运行中 + 已授权) */
    val canExecDirectly: Boolean
        get() = isRunning && hasPermission()

    fun getDiagnostic(): String = buildString {
        append("=== Shizuku 状态 ===\n")
        append("已安装: $isInstalled\n")
        append("运行中(pingBinder): $isRunning\n")
        append("binder 存活(isBinderAlive): $isBinderAlive\n")
        append("已授权: ${hasPermission()}\n")
        append("UserService 已绑定: $isBound\n")
        append("bind 进行中: $bindInProgress\n")
        append("Shizuku 版本: $version\n")
        append("\n=== 本 App ===\n")
        append("包名: ${context.packageName}\n")
        append("UserService 类: ${WatchUserService::class.java.name}\n")
        append("\n=== 备用通道 ===\n")
        append("可直接执行(newProcess): $canExecDirectly\n")
        append("\n=== 排查建议 ===\n")
        if (!isInstalled) {
            append("→ 请安装 Shizuku(手表版即可)\n")
        } else if (!isRunning) {
            append("→ Shizuku 服务未运行!用 ADB 执行:\n")
            append("  adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n")
        } else if (!hasPermission()) {
            append("→ 在 Shizuku App 给本 App 授权\n")
        } else if (!isBound) {
            append("→ UserService 绑定失败,但可用'备用模式'\n")
            append("  备用模式直接用 Shizuku.newProcess 执行命令,不需要绑定\n")
        } else {
            append("→ 状态正常\n")
        }
    }

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
