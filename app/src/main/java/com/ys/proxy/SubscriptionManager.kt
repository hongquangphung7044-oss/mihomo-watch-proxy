package com.ys.proxy

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 订阅下载器。
 *
 * 用户输入机场订阅链接,下载下来通常是 Clash 格式的 config.yaml。
 * 本类只负责下载,不做格式转换(需要 v2ray 转 Clash 的用户请先用 subconverter)。
 *
 * 注意:切换订阅时必须通过当前运行的 mihomo 代理(127.0.0.1:7890)下载新订阅,
 * 否则需要代理才能访问的机场订阅会下载失败(典型场景:用直连订阅 A 开代理,
 * 再切换到需要代理的订阅 B,此时不能先停 A,否则 B 下载不了)。
 */
class SubscriptionManager {

    /** 直连 client(首次启动用) */
    private val directClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 走本地 mihomo 代理的 client(切换订阅时用,确保需代理的订阅能下载) */
    private val proxiedClient: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890)))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 下载订阅内容,返回原始文本。失败抛异常。
     * @param viaProxy true=通过当前运行的 mihomo 代理下载(切换订阅时用);
     *                 false=直连(首次启动,mihomo 还没跑时用)
     */
    fun download(url: String, viaProxy: Boolean = false): String {
        val client = if (viaProxy) proxiedClient else directClient
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "clash.meta/v1.19 (mihomo-watch-proxy)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}")
            }
            return resp.body?.string()
                ?: throw RuntimeException("响应体为空")
        }
    }

    /**
     * 把用户下载的 config.yaml 注入控制面板配置,生成最终给 mihomo 用的 config。
     *
     * 处理逻辑:
     *  1. 扫描用户 config,移除已存在的 mixed-port/external-controller/secret/allow-lan/mode 行(去重,避免 YAML 重复 key 报错)
     *  2. 在文件开头注入我们要的配置
     *
     * @param rawConfig 用户订阅下载的原始 yaml
     * @param secret 控制面板密码
     * @return 注入后的完整 config.yaml
     */
    fun injectControllerConfig(rawConfig: String, secret: String = "watch123"): String {
        val overrideKeys = setOf(
            "mixed-port", "external-controller", "secret", "allow-lan", "mode",
            "log-level", "external-ui"
        )

        // 过滤掉用户 config 里的覆盖项,避免重复 key
        val filteredLines = rawConfig.lines().filterNot { line ->
            val trimmed = line.trimStart()
            // 只处理顶层 key(不缩进),避免误伤 proxy 节点里的同名字段
            if (trimmed.startsWith("-") || line.startsWith(" ") || line.startsWith("\t")) {
                false
            } else {
                val key = trimmed.substringBefore(":", "").trim()
                key in overrideKeys
            }
        }

        val header = buildString {
            appendLine("# === mihomo-watch-proxy 注入配置 ===")
            appendLine("mixed-port: 7890")
            appendLine("allow-lan: false")
            appendLine("mode: rule")
            appendLine("log-level: info")
            appendLine("external-controller: 127.0.0.1:9090")
            appendLine("secret: '$secret'")
            appendLine("# === 用户订阅内容 ===")
        }

        return header + filteredLines.joinToString("\n")
    }
}
