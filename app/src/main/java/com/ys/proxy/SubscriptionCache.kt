package com.ys.proxy

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 订阅内容本地缓存。
 *
 * 作用:避免每次启动代理都重新下载订阅。首次下载后缓存到 App 私有目录,
 * 后续启动直接读缓存(秒开);"更新订阅"按钮才强制重新下载。
 *
 * 缓存策略:
 *  - 以订阅 URL 的 MD5 作为文件名,存在 filesDir/subs/ 下
 *  - 每个 URL 一个缓存文件,切换订阅时各自独立
 *  - 删除保存的订阅时不主动清缓存(占空间小,且下次重新下载会覆盖)
 *
 * 注意:缓存的是机场返回的原始 config.yaml,不是注入控制配置后的版本
 * (注入逻辑每次启动都跑,保证 mixed-port/secret 等可调整)
 */
class SubscriptionCache(context: Context) {
    private val cacheDir = File(context.filesDir, "subs").apply { mkdirs() }

    /** 读取缓存的订阅内容,无缓存返回 null */
    fun load(url: String): String? {
        val file = File(cacheDir, fileName(url))
        return if (file.exists()) file.readText() else null
    }

    /** 保存订阅内容到缓存(覆盖已有) */
    fun save(url: String, content: String) {
        File(cacheDir, fileName(url)).writeText(content)
    }

    /** 清除所有缓存(诊断/排错用) */
    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun fileName(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return "sub_$hash.yaml"
    }
}
