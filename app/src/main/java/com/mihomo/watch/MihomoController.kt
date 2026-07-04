package com.mihomo.watch

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * 命令执行器抽象。由 ShizukuManager 绑定的 UserService 提供:
 *   runner = service::exec (IWatchService 代理,运行在 Shizuku shell 权限进程)
 *
 * MihomoController 不关心具体来源,只调 runner(cmd) 拿 stdout+stderr。
 */
typealias CommandRunner = (String) -> String

/**
 * mihomo 内核生命周期编排。
 *
 * 完整流程:
 *   1. 从 APK 的 assets 释放 mihomo 二进制到 App 外部存储目录(App 有权写)
 *   2. 通过 Shizuku(shell 权限)把二进制 cp 到 /data/local/tmp 并 chmod
 *   3. 把订阅生成的 config.yaml 同样 cp 到 /data/local/tmp/mihomo_home/
 *   4. 用 shell 启动 mihomo(nohup 脱离),监听 7890 端口
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

    private val extDir: File
        get() = context.getExternalFilesDir(null)
            ?: throw RuntimeException("无法访问外部存储目录")

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

    /** 安装 mihomo 二进制到 /data/local/tmp */
    fun installBinary(runner: CommandRunner) {
        val extBin = ensureBinaryReleased()
        // 注意:cp 后必须 chmod 755,/data/local/tmp 才能执行
        val r = runner("cp '${extBin.absolutePath}' '$MIHOMO_BIN' && chmod 755 '$MIHOMO_BIN' && ls -la $MIHOMO_BIN 2>&1")
        if (!r.contains("mihomo")) {
            throw RuntimeException("二进制安装失败: $r")
        }
    }

    /** 安装配置文件到 mihomo_home */
    fun installConfig(runner: CommandRunner, configContent: String) {
        val extConfig = File(extDir, "config.yaml")
        extConfig.writeText(configContent)
        val r = runner("mkdir -p '$MIHOMO_HOME' && cp '${extConfig.absolutePath}' '$CONFIG_PATH' && head -3 $CONFIG_PATH 2>&1")
        if (r.contains("No such file") || r.isBlank()) {
            throw RuntimeException("配置安装失败: $r")
        }
    }

    /**
     * 启动完整代理链:安装二进制 + 安装配置 + 启动 mihomo + 设全局代理。
     * @param runner 命令执行器(UserService 或 newProcess)
     * @param configContent 订阅下载并注入控制面板配置后的完整 yaml
     */
    fun start(runner: CommandRunner, configContent: String) {
        // 1. 二进制
        installBinary(runner)
        // 2. 配置
        installConfig(runner, configContent)
        // 3. 启动 mihomo(nohup 脱离 runner 进程生命周期)
        runner("pkill -f $MIHOMO_BIN 2>/dev/null; sleep 1; true")
        runner("nohup $MIHOMO_BIN -d $MIHOMO_HOME > $MIHOMO_HOME/mihomo.log 2>&1 &")
        Thread.sleep(1500)  // 给 mihomo 启动时间
        // 4. 验证启动
        val pgrep = runner("pgrep -f mihomo 2>/dev/null || echo NONE")
        if (pgrep.contains("NONE") || pgrep.isBlank()) {
            val log = runner("tail -30 $MIHOMO_HOME/mihomo.log 2>&1")
            throw RuntimeException("mihomo 启动失败,日志:\n$log")
        }
        // 5. 设全局代理
        runner("settings put global http_proxy $PROXY_ADDR")
        val proxy = runner("settings get global http_proxy")
        if (!proxy.contains(PROXY_ADDR)) {
            throw RuntimeException("代理设置失败,当前 http_proxy=$proxy")
        }
    }

    /** 停止:先清代理,再杀 mihomo */
    fun stop(runner: CommandRunner) {
        try { runner("settings delete global http_proxy") } catch (_: Exception) {}
        try { runner("settings put global http_proxy :0") } catch (_: Exception) {}
        try { runner("pkill -f mihomo 2>/dev/null; true") } catch (_: Exception) {}
    }

    /** mihomo 是否在运行 */
    fun isRunning(runner: CommandRunner): Boolean = try {
        val r = runner("pgrep -f mihomo 2>/dev/null || echo NONE")
        !r.contains("NONE") && r.isNotBlank()
    } catch (e: Exception) {
        false
    }

    /** 读取最近日志(排错用) */
    fun tailLog(runner: CommandRunner, lines: Int = 30): String = try {
        runner("tail -$lines $MIHOMO_HOME/mihomo.log 2>&1")
    } catch (e: Exception) {
        "读取日志失败: ${e.message}"
    }

    /**
     * 更新订阅:重新写 config.yaml,然后调 mihomo API reload。
     * 不重启 mihomo 进程,不断流。
     */
    fun updateConfigAndReload(runner: CommandRunner, configContent: String): Boolean {
        installConfig(runner, configContent)
        return try {
            MihomoApi().reloadConfig(CONFIG_PATH)
        } catch (e: Exception) {
            false
        }
    }
}
