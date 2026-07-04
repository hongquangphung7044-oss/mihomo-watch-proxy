package com.mihomo.watch

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Shizuku 集成封装。
 *
 * 职责:
 *  1. 检测 Shizuku 是否安装、是否运行、是否已授权
 *  2. 申请权限
 *  3. bind UserService 拿到 [IWatchService] 代理
 *
 * 关键:Shizuku 必须先在手表上启动(通过 ADB 激活或 root 激活),
 * 然后本 App 才能拿到 shell 权限去执行 mihomo 和 settings 命令。
 */
class ShizukuManager(private val context: Context) {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 0xA001
    }

    private var service: IWatchService? = null
    private var conn: ServiceConnection? = null

    private val userServiceArgs: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, WatchUserService::class.java.name)
        )
            .processNameSuffix("mihomo_service")
            .debuggable(BuildConfig.DEBUG)
            .version(1)

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

    /** bind UserService,拿到 shell 权限的接口 */
    fun bind(onConnected: (IWatchService) -> Unit, onFailed: (String) -> Unit) {
        if (!isRunning) {
            onFailed("Shizuku 未运行,请先在 Shizuku App 中启动服务")
            return
        }
        if (!hasPermission()) {
            onFailed("未授权 Shizuku,请先点击授权")
            return
        }
        // 已连接,直接复用
        service?.let { onConnected(it); return }

        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IWatchService.Stub.asInterface(binder)
                service?.let { onConnected(it) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }

            override fun onBindingDied(name: ComponentName) {
                service = null
            }

            override fun onNullBinding(name: ComponentName) {
                onFailed("UserService 绑定返回 null")
            }
        }
        conn = c
        try {
            // bindUserService 返回 false 表示绑定失败
            val ok = Shizuku.bindUserService(userServiceArgs, c)
            if (!ok) {
                onFailed("bindUserService 返回 false,可能 Shizuku 未运行")
                conn = null
            }
        } catch (e: Exception) {
            onFailed("bindUserService 异常: ${e.message}")
            conn = null
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
    }
}
