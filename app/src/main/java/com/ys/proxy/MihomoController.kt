package com.ys.proxy

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
     * 安装 GeoIP/GeoSite 数据库到 mihomo_home。
     *
     * 关键修复:解决 "MMDB invalid, remove and download" 导致 mihomo 卡死问题。
     *
     * 问题根因:
     *  mihomo 启动时需要 GeoIP 数据库(geoip.metadb)来解析 GEOIP 规则(如 GEOIP,CN,DIRECT)。
     *  如果文件缺失或损坏,mihomo 会尝试从 github 下载,但:
     *    - 手表在国内,github 直连不通(需要代理,但代理还没起来 → 鸡生蛋问题)
     *    - 下载卡住 → 9090 端口迟迟不起 → App 显示"无法连接"
     *
     * 解决方案:
     *  1. 把 geoip.metadb + geosite.dat 打包到 assets(随 APK 分发)
     *  2. 每次启动 mihomo 前都重新安装一份(覆盖可能损坏的旧文件)
     *  3. 配置 geo-auto-update: false 禁止自动更新(避免更新时损坏)
     */
    fun installGeodata(runner: CommandRunner) {
        // 1. 释放 assets 里的 geo 数据到 App 外部目录
        val extGeoip = File(extDir, "geoip.metadb")
        val extGeosite = File(extDir, "geosite.dat")
        if (!extGeoip.exists() || extGeoip.length() < 1_000_000) {
            context.assets.open("geoip.metadb").use { input ->
                extGeoip.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!extGeosite.exists() || extGeosite.length() < 1_000_000) {
            context.assets.open("geosite.dat").use { input ->
                extGeosite.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // 2. 先删除 mihomo_home 里可能损坏的旧 geo 文件(包括各种历史命名)
        //    mihomo 检测到文件存在但无效时会 "remove and download",我们提前删干净
        runner("rm -f $MIHOMO_HOME/geoip.metadb $MIHOMO_HOME/country.mmdb $MIHOMO_HOME/geoip.dat $MIHOMO_HOME/geosite.dat $MIHOMO_HOME/GeoIP.dat $MIHOMO_HOME/GeoSite.dat 2>/dev/null; true")
        // 3. 复制新鲜的 geo 数据到 mihomo_home
        val r = runner("cp '${extGeoip.absolutePath}' '$MIHOMO_HOME/geoip.metadb' && cp '${extGeosite.absolutePath}' '$MIHOMO_HOME/geosite.dat' && ls -la $MIHOMO_HOME/geoip.metadb $MIHOMO_HOME/geosite.dat 2>&1")
        if (!r.contains("geoip.metadb") || !r.contains("geosite.dat")) {
            throw RuntimeException("GeoIP 数据库安装失败: $r")
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
        // 2.5 GeoIP/GeoSite 数据库(必须在启动 mihomo 前安装好,否则 mihomo 会尝试下载导致卡死)
        installGeodata(runner)
        // 3. 先彻底停旧 mihomo(SIGKILL,避免端口 7890/9090 占用导致新进程启动失败)
        //    关键修复:不能用 pkill -f mihomo,因为 shell 命令行也包含 "mihomo",
        //    pkill 会先杀掉执行命令的 shell 自身 → 命令中断 → 旧 mihomo 杀不掉
        //    改用 -x 精确匹配进程名(comm="mihomo"),shell 的 comm 是 "sh" 不会被匹配
        killAllMihomo(runner)
        // 4. 启动 mihomo(nohup 脱离 runner 进程生命周期)
        runner("nohup $MIHOMO_BIN -d $MIHOMO_HOME > $MIHOMO_HOME/mihomo.log 2>&1 &")
        Thread.sleep(2000)  // 给 mihomo 启动时间(配置加载需要时间)
        // 5. 验证启动:pgrep -x 精确匹配进程名(避免误匹配 shell)
        val pgrep = runner("pgrep -x mihomo 2>/dev/null || echo NONE")
        if (pgrep.contains("NONE") || pgrep.isBlank()) {
            val log = runner("tail -30 $MIHOMO_HOME/mihomo.log 2>&1")
            throw RuntimeException("mihomo 启动失败,日志:\n$log")
        }
        // 6. 等 API 端口就绪(进程在跑 ≠ 配置加载完,9090 监听才算就绪)
        var portReady = false
        for (i in 1..10) {
            val check = runner("netstat -tnl 2>/dev/null | grep ':9090' || echo NO_PORT")
            if (!check.contains("NO_PORT")) {
                portReady = true
                break
            }
            Thread.sleep(500)
        }
        if (!portReady) {
            val log = runner("tail -40 $MIHOMO_HOME/mihomo.log 2>&1")
            throw RuntimeException("mihomo 启动了但 API 端口 9090 未就绪,可能配置错误。日志:\n$log")
        }
        // 7. 设全局代理
        runner("settings put global http_proxy $PROXY_ADDR")
        val proxy = runner("settings get global http_proxy")
        if (!proxy.contains(PROXY_ADDR)) {
            throw RuntimeException("代理设置失败,当前 http_proxy=$proxy")
        }
    }

    /**
     * 停止:先清代理,再杀 mihomo。
     *
     * 关键修复(网络瘫痪 bug):
     *  旧版只是 try/catch 静默吞掉异常,一旦 Shizuku runner 通道异常(命令执行失败但
     *  不抛异常,只返回错误字符串),http_proxy 清不掉 → 整机网络瘫痪,重启也无法自愈。
     *  现在改为:清完后读回验证,没清干净就抛异常,让 UI 明确告知用户停止失败。
     *
     * @throws RuntimeException 如果代理没清除干净(用户需要知道,否则以为停了其实还瘫着)
     */
    fun stop(runner: CommandRunner) {
        // 1. 清全局代理(两条命令都跑,兼容不同系统)
        var lastErr = ""
        try { runner("settings delete global http_proxy") } catch (e: Exception) { lastErr = e.message ?: "" }
        try { runner("settings put global http_proxy :0") } catch (e: Exception) { lastErr = e.message ?: "" }
        // 2. 验证 http_proxy 确实清掉了(没清掉会导致整机网络瘫痪)
        val proxy = try { runner("settings get global http_proxy") } catch (e: Exception) { lastErr = e.message ?: ""; "" }
        if (proxy.isNotBlank() && proxy.contains(":") && !proxy.contains(":0")) {
            throw RuntimeException(
                "全局代理清除失败!当前 http_proxy=$proxy\n" +
                "这会导致整机网络瘫痪(所有流量被劫持到不存在的 7890 端口)。\n" +
                "请用 ADB 手动清理:adb shell settings delete global http_proxy\n" +
                "或确认 Shizuku 服务正在运行后重试。"
            )
        }
        // 3. 杀 mihomo 进程(killAllMihomo 内部用 -x 精确匹配,避免 pkill 自杀 shell)
        killAllMihomo(runner)
        // 4. 验证 mihomo 确实杀了(用 -x 精确匹配,避免误匹配 shell)
        val pgrep = try { runner("pgrep -x mihomo 2>/dev/null || echo NONE") } catch (_: Exception) { "NONE" }
        if (!pgrep.contains("NONE") && pgrep.isNotBlank()) {
            // 杀不掉!输出详细诊断(可能是 D 状态不可中断睡眠)
            val ps = try { runner("ps -ef 2>/dev/null | grep '[m]ihomo' || echo NO_PS") } catch (_: Exception) { "NO_PS" }
            throw RuntimeException(
                "mihomo 进程未完全停止(SIGKILL 杀不掉,可能处于 D 状态不可中断睡眠)。\n" +
                "残留 PID: ${pgrep.trim()}\n进程信息: $ps\n" +
                "请用 ADB 强杀: adb shell kill -9 ${pgrep.trim()}\n" +
                "或重启手表后重试。"
            )
        }
    }

    /**
     * 彻底杀掉所有 mihomo 进程(用 SIGKILL,精确匹配避免杀掉 shell 自身)。
     *
     * 关键修复:旧版用 pkill -9 -f mihomo,但 -f 匹配整个命令行,
     * 执行命令的 shell(sh -c "pkill -9 -f mihomo...")命令行也包含 "mihomo",
     * pkill 会先杀掉 shell 自身 → 命令中断 → 旧 mihomo 杀不掉。
     *
     * 现在用三重保险:
     * 1. pkill -x mihomo(-x 精确匹配进程名 comm="mihomo",shell 的 comm 是 "sh")
     * 2. ps + grep 精确匹配二进制路径(兜底,-x 在某些系统可能不支持)
     * 3. 再 pkill -x 一次(确保僵尸进程清干净)
     */
    private fun killAllMihomo(runner: CommandRunner) {
        try {
            // 方法1:pkill -x 精确匹配进程名(shell comm 是 "sh",不会被匹配)
            runner("pkill -9 -x mihomo 2>/dev/null; true")
            Thread.sleep(500)
            // 方法2:ps + grep 精确匹配二进制路径(兜底,处理 -x 不支持或 comm 被截断的情况)
            // grep '[m]ihomo' 技巧:正则中括号防止 grep 匹配到自身
            runner("ps -ef 2>/dev/null | grep '[m]ihomo' | grep -v grep | awk '{print \$2}' | xargs -r kill -9 2>/dev/null; true")
            Thread.sleep(500)
            // 方法3:再 pkill -x 一次,清残留
            runner("pkill -9 -x mihomo 2>/dev/null; true")
            Thread.sleep(300)
        } catch (_: Exception) {}
    }

    /** mihomo 是否在运行(用 -x 精确匹配进程名,避免误匹配 shell) */
    fun isRunning(runner: CommandRunner): Boolean = try {
        val r = runner("pgrep -x mihomo 2>/dev/null || echo NONE")
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
