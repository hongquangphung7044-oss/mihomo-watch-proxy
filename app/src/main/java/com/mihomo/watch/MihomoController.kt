package com.mihomo.watch

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * mihomo 内核生命周期编排。
 *
 * 完整流程:
 *   1. 从 APK 的 assets 释放 mihomo 二进制到 App 外部存储目录(App 有权写)
 *   2. 通过 Shizuku UserService(shell 权限)把二进制 cp 到 /data/local/tmp 并 chmod
 *   3. 把订阅生成的 config.yaml 同样 cp 到 /data/local/tmp/mihomo_home/
 *   4. 用 shell 启动 mihomo(nohop 脱离),监听 7890 端口
 *   5. 用 settings put global http_proxy 127.0.0.1:7890 设全局代理
 *
 * 路径约定(必须用 shell 可写可执行的 /data/local/tmp):
 *   - 二进制: /data/local/tmp/mihomo
 *   - 工作目录: /data/local/tmp/mihomo_home/
 *   - 配置: /data/local/tmp/mihomo_home/config.yaml
 *   - 日志: /data/local/tmp/mihomo_home/mihomo.log
 */
class MihomoController(private val context: Context) {

    companion object {
        const val MIHOMO_BIN = "/data/local/tmp/mihomo"
        const val MIHOMO_HOME = "/data/local/tmp/mihomo_home"
        const val CONFIG_PATH = "$MIHOMO_HOME/config.yaml"
        const val PROXY_ADDR = "127.0.0.1:7890"
        const val API_BASE = "http://127.0.0.1:9090"
        const val PREF_NAME = "mihomo_watch"
        const val PREF_KEY_BIN_VERSION = "bin_version_code"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** App 外部存储目录(shell 可读) */
    private val extDir: File
        get() = context.getExternalFilesDir(null)
            ?: throw RuntimeException("无法访问外部存储目录")

    /**
     * 从 assets 释放 mihomo 二进制到外部目录。
     * App 版本变化时重新释放,确保二进制跟 APK 同步更新。
     */
    fun ensureBinaryReleased(): File {
        val extBin = File(extDir, "mihomo")
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
                .let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode }
        } catch (e: PackageManager.NameNotFoundException) {
            1
        }
        val lastVersion = prefs.getInt(PREF_KEY_BIN_VERSION, -1)
        if (!extBin.exists() || extBin.length() < 1_000_000 || lastVersion != currentVersion) {
            context.assets.open("mihomo").use { input ->
                extBin.outputStream().use { output -> input.copyTo(output) }
            }
            prefs.edit().putInt(PREF_KEY_BIN_VERSION, currentVersion).apply()
        }
        return extBin
    }

    /** 通过 Shizuku 把外部目录的二进制安装到 /data/local/tmp */
    fun installBinary(service: IWatchService) {
        val extBin = ensureBinaryReleased()
        service.installBinary(extBin.absolutePath, MIHOMO_BIN)
        // 验证安装结果
        val check = service.exec("ls -la $MIHOMO_BIN 2>&1")
        if (!check.contains("mihomo")) {
            throw RuntimeException("二进制安装到 /data/local/tmp 失败: $check")
        }
    }

    /** 把 config 内容写到外部目录,再 cp 到 mihomo_home */
    fun installConfig(service: IWatchService, configContent: String) {
        val extConfig = File(extDir, "config.yaml")
        extConfig.writeText(configContent)
        service.installConfig(extConfig.absolutePath, CONFIG_PATH)
        val check = service.exec("head -3 $CONFIG_PATH 2>&1")
        if (check.contains("No such file") || check.isBlank()) {
            throw RuntimeException("配置安装失败: $check")
        }
    }

    /**
     * 启动完整代理链:安装二进制 + 安装配置 + 启动 mihomo + 设全局代理。
     * @param configContent 订阅下载并注入控制面板配置后的完整 yaml
     */
    fun start(service: IWatchService, configContent: String) {
        // 1. 二进制
        installBinary(service)
        // 2. 配置
        installConfig(service, configContent)
        // 3. 启动 mihomo
        service.startMihomo(MIHOMO_BIN, MIHOMO_HOME)
        // 4. 验证启动
        if (!service.isMihomoRunning) {
            // 读日志辅助排查
            val log = service.exec("tail -20 $MIHOMO_HOME/mihomo.log 2>&1")
            throw RuntimeException("mihomo 启动失败,日志:\n$log")
        }
        // 5. 设全局代理
        service.setProxy(PROXY_ADDR)
        // 验证代理设置
        val proxy = service.exec("settings get global http_proxy")
        if (!proxy.contains(PROXY_ADDR)) {
            throw RuntimeException("代理设置失败,当前 http_proxy=$proxy")
        }
    }

    /** 停止:先清代理,再杀 mihomo */
    fun stop(service: IWatchService) {
        try { service.clearProxy() } catch (_: Exception) {}
        try { service.stopMihomo() } catch (_: Exception) {}
    }

    /** mihomo 是否在运行 */
    fun isRunning(service: IWatchService): Boolean = try {
        service.isMihomoRunning
    } catch (e: Exception) {
        false
    }

    /** 读取最近日志(排错用) */
    fun tailLog(service: IWatchService, lines: Int = 30): String = try {
        service.exec("tail -$lines $MIHOMO_HOME/mihomo.log 2>&1")
    } catch (e: Exception) {
        "读取日志失败: ${e.message}"
    }

    /**
     * 更新订阅:重新写 config.yaml,然后调 mihomo API reload。
     * 不重启 mihomo 进程,避免断流。
     * @return true 表示 reload 请求成功
     */
    fun updateConfigAndReload(service: IWatchService, configContent: String): Boolean {
        installConfig(service, configContent)
        // 触发 mihomo 热重载
        return try {
            val api = MihomoApi()
            api.reloadConfig(CONFIG_PATH)
        } catch (e: Exception) {
            false
        }
    }
}
