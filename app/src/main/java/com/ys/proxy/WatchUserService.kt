package com.ys.proxy

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService 实现。
 *
 * 运行在 Shizuku 进程中,uid=shell(2000),拥有 shell 权限。
 * 这是绕过三星 VpnService 限制的核心:
 *   - shell 权限可以直接执行 /data/local/tmp/ 下的二进制(mihomo)
 *   - shell 权限可以执行 settings put global http_proxy 设全局代理
 *   - 完全不触碰 VpnService,所以三星的限制管不到
 *
 * 注意:nohup 启动 mihomo 后,即使 UserService 被 unbind/销毁,mihomo 进程仍会继续运行。
 */
class WatchUserService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IWatchService.Stub() {

        override fun exec(cmd: String): String {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))

                // 修复:并行读 stdout/stderr,避免管道缓冲区死锁。
                // 之前顺序读会导致 stderr 填满 64KB 管道缓冲区后子进程阻塞 → 死锁。
                val errBuilder = StringBuilder()
                val errThread = Thread {
                    try {
                        BufferedReader(InputStreamReader(process.errorStream)).use { errBuilder.append(it.readText()) }
                    } catch (_: Exception) {}
                }
                errThread.start()

                val outBuilder = StringBuilder()
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { outBuilder.append(it.readText()) }
                } catch (_: Exception) {}

                // waitFor 加 30s 超时,避免命令挂起永久阻塞
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return outBuilder.toString() + errBuilder.toString() + "\n[TIMEOUT: 命令执行超时 30s]"
                }
                errThread.join(2000)
                outBuilder.toString() + errBuilder.toString()
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }

        override fun startMihomo(binPath: String, homePath: String) {
            // 确保 home 目录存在
            exec("mkdir -p $homePath")
            // 先停掉旧的,避免端口冲突。
            // 修复:用 -x 精确匹配进程名,避免 -f 匹配 shell 命令行自身(自杀)
            exec("pkill -x mihomo 2>/dev/null; sleep 1; true")
            // nohup 后台启动,脱离 UserService 进程生命周期
            exec("nohup $binPath -d $homePath > $homePath/mihomo.log 2>&1 &")
            // 给一点启动时间
            Thread.sleep(800)
        }

        override fun stopMihomo() {
            // 修复:用 -x 精确匹配,避免 -f 自匹配 shell
            exec("pkill -x mihomo 2>/dev/null; true")
        }

        override fun setProxy(proxy: String) {
            // 全局 http 代理,所有遵守系统代理的 App 都会走
            exec("settings put global http_proxy $proxy")
        }

        override fun clearProxy() {
            exec("settings delete global http_proxy")
            // 兼容部分系统的 settings put global http_proxy :none 写法
            exec("settings put global http_proxy :0")
        }

        override fun installBinary(srcPath: String, dstPath: String) {
            // 从 App 外部存储目录拷到 /data/local/tmp,赋可执行权限
            exec("cp '$srcPath' '$dstPath' && chmod 755 '$dstPath'")
        }

        override fun installConfig(srcPath: String, dstPath: String) {
            val dir = dstPath.substringBeforeLast('/')
            exec("mkdir -p '$dir' && cp '$srcPath' '$dstPath'")
        }

        override fun isMihomoRunning(): Boolean {
            // 修复:用 -x 精确匹配,避免 -f 匹配 shell 命令行自身
            val r = exec("pgrep -x mihomo 2>/dev/null || echo NONE")
            return !r.contains("NONE") && r.isNotBlank()
        }
    }
}
