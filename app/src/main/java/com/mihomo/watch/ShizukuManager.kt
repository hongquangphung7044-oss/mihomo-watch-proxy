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

    var onStateChanged: (() -> Unit)? = null

    private val userServiceArgs: Shizuku.UserServiceArgs = run {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, WatchUserService::class.java.name)
        )
            .processNameSuffix("mihomo_service")
            .debuggable(BuildConfig.DEBUG)
            .version(3)  // version 升级,强制 Shizuku 丢弃旧 UserService 进程重新创建

        // 关键:32 位系统必须用 32 位 app_process 启动 UserService 进程。
        // Galaxy Watch7 国行是 32 位(armeabi-v7a),Shizuku 默认用 64 位 app_process,
        // 会导致 WatchUserService 类加载失败 → onNullBinding → UserService 绑定不上。
        //
        // Shizuku.UserServiceArgs.use32BitAppProcess(true) 是 private 方法,
        // 只能通过反射设置 use32BitAppProcess 字段。
        // 参考 Shizuku 源码 api/.../Shizuku.java 第 587/661 行。
        try {
            val field = Shizuku.UserServiceArgs::class.java
                .getDeclaredField("use32BitAppProcess")
            field.isAccessible = true
            field.setBoolean(args, true)
        } catch (e: Exception) {
            // 反射失败(Shizuku SDK 版本变化),记录但不阻断
            // 大多数现代设备 64 位兼容,不设也能跑
            e.printStackTrace()
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

    fun bind(onConnected: (IWatchService) -> Unit, onFailed: (String) -> Unit) {
        if (!isRunning) {
            onFailed("Shizuku 服务未运行")
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
                onFailed("Shizuku binder 死亡,服务可能被杀")
                onStateChanged?.invoke()
            }

            override fun onNullBinding(name: ComponentName) {
                if (finished) return
                finished = true
                bindInProgress = false
                onFailed("UserService 返回 null(Shizuku 加载 WatchUserService 失败)。可能原因:32 位系统未用 32 位 app_process,或 WatchUserService 类加载失败")
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
                onFailed("连接 UserService 超时(8秒)")
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
        append("32 位 app_process: 已通过反射设置\n")
        append("\n=== 本 App ===\n")
        append("包名: ${context.packageName}\n")
        append("UserService 类: ${WatchUserService::class.java.name}\n")
        append("\n=== 排查建议 ===\n")
        if (!isInstalled) {
            append("→ 请安装 Shizuku(手表版/Whizuku 均可)\n")
        } else if (!isRunning) {
            append("→ Shizuku 服务未运行!用 ADB 执行:\n")
            append("  adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n")
        } else if (!hasPermission()) {
            append("→ 在 Shizuku App 给本 App 授权\n")
        } else if (!isBound) {
            append("→ UserService 绑定失败。本版本已加 32 位 app_process 参数\n")
            append("  若仍失败,请反馈诊断截图\n")
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
