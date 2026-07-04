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
 * 关键:Shizuku 必须先在手表上启动(通过 ADB 激活或 root 激活),
 * 然后本 App 才能拿到 shell 权限去执行 mihomo 和 settings 命令。
 *
 * 重要概念区分(用户常混淆):
 *  - Shizuku 已安装 ≠ Shizuku 已运行(需要 ADB 启动服务进程)
 *  - Shizuku 已运行 ≠ 已授权(需要在 Shizuku App 给本 App 授权)
 *  - 已授权 ≠ UserService 已绑定(bindUserService 是异步,可能失败)
 */
class ShizukuManager(private val context: Context) {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 0xA001
        private const val BIND_TIMEOUT_MS = 4000L
    }

    private var service: IWatchService? = null
    private var conn: ServiceConnection? = null
    /** bind 已发起但未回调,防止重复 bind 和判断超时 */
    private var bindInProgress: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 外部状态变更回调(binder 接收/死亡时通知 UI 刷新) */
    var onStateChanged: (() -> Unit)? = null

    private val userServiceArgs: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, WatchUserService::class.java.name)
        )
            .processNameSuffix("mihomo_service")
            .debuggable(BuildConfig.DEBUG)
            .version(1)

    /** 注册 Shizuku binder 监听(在 ViewModel 初始化时调用一次) */
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
            // 老版本 Shizuku 可能没有这些 API,忽略
        }
    }

    /** Shizuku 应用是否已安装 */
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

    /** 是否已获得 Shizuku 授权 */
    fun hasPermission(): Boolean = try {
        if (Shizuku.isPreV11()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /** UserService 是否已成功绑定(service 可用) */
    val isBound: Boolean get() = service != null

    /** Shizuku 版本号(诊断用) */
    val version: String
        get() = try {
            Shizuku.getVersion().toString()
        } catch (e: Exception) {
            "unknown"
        }

    /** 申请权限,结果通过回调返回 */
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

    /**
     * bind UserService,拿到 shell 权限的接口。
     * 加了超时检测:4 秒内未回调 onServiceConnected 视为失败。
     */
    fun bind(onConnected: (IWatchService) -> Unit, onFailed: (String) -> Unit) {
        if (!isRunning) {
            onFailed("Shizuku 服务未运行。请在手表打开 Shizuku App 确认显示'正在运行';若未运行,需通过 ADB 执行启动命令")
            return
        }
        if (!hasPermission()) {
            onFailed("未授权 Shizuku。请在 Shizuku App 里给本 App 授权,或点 App 内'授权'按钮")
            return
        }
        // 已连接,直接复用
        service?.let { s ->
            onConnected(s)
            return
        }
        if (bindInProgress) {
            onFailed("正在连接 Shizuku UserService,请等几秒再试")
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
                onFailed("Shizuku binder 死亡(服务可能被杀)。请重新打开 Shizuku App 用 ADB 启动服务")
                onStateChanged?.invoke()
            }

            override fun onNullBinding(name: ComponentName) {
                if (finished) return
                finished = true
                bindInProgress = false
                onFailed("UserService 绑定返回 null(Shizuku 进程加载 WatchUserService 失败)")
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
            onFailed("bindUserService 抛异常: ${e.message}")
            return
        }
        // 超时检测:4 秒未回调视为失败
        mainHandler.postDelayed({
            if (!finished && service == null) {
                finished = true
                bindInProgress = false
                onFailed("连接 Shizuku UserService 超时(4秒)。可能原因:\n" +
                    "1. Shizuku 服务进程未真正运行(仅在 App 里授权不够,需 ADB 启动)\n" +
                    "2. WatchUserService 在 Shizuku 进程加载失败\n" +
                    "3. Shizuku 版本与 SDK 不兼容\n" +
                    "请点'诊断'查看详细状态")
                onStateChanged?.invoke()
            }
        }, BIND_TIMEOUT_MS)
    }

    /** 详细诊断信息(排错用) */
    fun getDiagnostic(): String = buildString {
        append("Shizuku 已安装: $isInstalled\n")
        append("Shizuku 运行中: $isRunning\n")
        append("已授权: ${hasPermission()}\n")
        append("UserService 已绑定: $isBound\n")
        append("bind 进行中: $bindInProgress\n")
        append("Shizuku 版本: $version\n")
        append("本 App 包名: ${context.packageName}\n")
        append("UserService 类: ${WatchUserService::class.java.name}\n")
        if (!isInstalled) {
            append("\n→ 请在手表安装 Shizuku App\n")
        } else if (!isRunning) {
            append("\n→ Shizuku 服务未运行!需要在电脑连手表 ADB,执行:\n")
            append("  adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n")
            append("(或参考 Shizuku 官网的 ADB 启动方式)\n")
        } else if (!hasPermission()) {
            append("\n→ 打开 Shizuku App,在授权列表里给本 App 开启权限\n")
        } else if (!isBound) {
            append("\n→ 已授权但 UserService 绑定失败,点'重新连接'重试\n")
        } else {
            append("\n→ 状态正常,service 已就绪\n")
        }
    }

    /** 已绑定时直接返回,否则返回 null */
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
