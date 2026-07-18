package com.ys.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * mihomo RESTful API 客户端。
 *
 * mihomo 启动后会监听 external-controller 端口(9090),提供 API 用于:
 *  - 查询所有代理节点和分组
 *  - 切换 Selector 分组的当前节点(这就是"选节点")
 *  - 测延迟
 *
 * 所有请求需要带 Authorization: Bearer <secret>。
 *
 * 关键:client 必须 proxy(NO_PROXY),否则当系统残留 http_proxy=127.0.0.1:7890
 * 但 mihomo 未运行时,访问 127.0.0.1:9090 也会被转发到 7890 → 连接失败。
 */
class MihomoApi(private val baseUrl: String = MihomoController.API_BASE, private val secret: String = "watch123") {

    // 快接口 client(getProxies/selectNode/reloadConfig):本地 9090,响应 <100ms
    private val fastClient = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // 测速 client(testDelay):mihomo 内部 timeout=15s + 余量,readTimeout 必须大于 15s
    private val testClient = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 代理节点信息 */
    data class Proxy(
        val name: String,
        val type: String,        // Selector, URLTest, Fallback, Shadowsocks, Vmess...
        val now: String?,        // 当前选中节点(仅 Selector 类型)
        val all: List<String>    // 分组下所有可选节点
    )

    /** 获取所有代理 */
    fun getProxies(): Map<String, Proxy> {
        val resp = get("/proxies")
        val obj = JSONObject(resp)
        val proxies = obj.getJSONObject("proxies")
        val result = mutableMapOf<String, Proxy>()
        val keys = proxies.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val p = proxies.getJSONObject(name)
            // 修复:has("all") 在值为 null 时仍返回 true,会抛 JSONException。
            // 与 now 字段保持一致的 null 检查
            val all = if (p.has("all") && !p.isNull("all")) {
                p.getJSONArray("all").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } else emptyList()
            val now = if (p.has("now") && !p.isNull("now")) p.getString("now") else null
            result[name] = Proxy(name, p.optString("type"), now, all)
        }
        return result
    }

    /** 获取所有可切换的 Selector 分组(用户能在这里挑节点) */
    fun getSelectorGroups(): List<Proxy> {
        return getProxies().values.filter {
            it.type == "Selector" && it.all.isNotEmpty()
        }
    }

    /**
     * 切换 Selector 分组的当前节点。
     *
     * 修复:之前用字符串拼接 JSON body,如果 nodeName 含 " 或 \ 会破坏 JSON。
     * 改用 JSONObject 构造,自动转义特殊字符。
     */
    fun selectNode(groupName: String, nodeName: String): Boolean {
        return try {
            val jsonBody = JSONObject().put("name", nodeName).toString()
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/proxies/${urlEncode(groupName)}")
                .header("Authorization", "Bearer $secret")
                .put(body)
                .build()
            fastClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 测节点延迟,返回 ms,失败返回 -1。
     *
     * 回归修复:对标 v1.0.58 release(用户实测测速正常工作的版本)。
     *
     * 回归根因:
     *  全面审查 commit (cbaba81) 加了 `expected=204` 参数 + URL 改成 HTTP,
     *  导致测速全面失败:
     *   1. mihomo 的 `expected` 参数是"期望状态码",传 204 后要求测速 URL
     *      必须严格返回 204。但 generate_204 端点常被 CDN 重定向返回 200,
     *      mihomo 直接判失败,body 返回 {"message":"..."} 而非 {"delay":N}。
     *      v1.0.58 不传 expected → mihomo 默认接受所有 2xx → 测速总能成功。
     *   2. HTTP 容易被运营商/CDN 劫持重定向,HTTPS 更稳。
     *
     * 保留的改进(非回归点):
     *  - testClient(readTimeout=20s),mihomo 内部 timeout=15s,OkHttp 必须 >15s
     *  - 不依赖状态码直接读 body JSON,更鲁棒(mihomo 失败也可能返 200)
     */
    fun testDelay(nodeName: String, testUrl: String = "https://www.gstatic.com/generate_204"): Int {
        return try {
            // 不传 expected:让 mihomo 默认接受所有 2xx(对标 v1.0.58)
            val req = Request.Builder()
                .url("$baseUrl/proxies/${urlEncode(nodeName)}/delay?timeout=15000&url=${urlEncode(testUrl)}")
                .header("Authorization", "Bearer $secret")
                .get()
                .build()
            testClient.newCall(req).execute().use { resp ->
                // 不依赖状态码:mihomo 测速失败时返回 400/500/504 但 body 仍可能有信息,
                // 直接读 body JSON,有 delay 字段就用
                val body = resp.body?.string() ?: "{}"
                val obj = JSONObject(body)
                if (obj.has("delay") && !obj.isNull("delay")) obj.getInt("delay") else -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 重载配置文件(更新订阅后调用,无需重启 mihomo 进程)。
     * mihomo API: PUT /configs?force=true,body={"path":"<config.yaml 绝对路径>"}
     *
     * 修复:用 JSONObject 构造 body,避免 configPath 含特殊字符破坏 JSON。
     * @param configPath 配置文件路径,如 /data/local/tmp/mihomo_home/config.yaml
     */
    fun reloadConfig(configPath: String): Boolean {
        return try {
            val jsonBody = JSONObject()
                .put("path", configPath)
                .put("payload", "")
                .toString()
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/configs?force=true")
                .header("Authorization", "Bearer $secret")
                .put(body)
                .build()
            fastClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun get(path: String): String {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $secret")
            .get()
            .build()
        fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("API $path 失败: HTTP ${resp.code}")
            return resp.body?.string() ?: throw RuntimeException("空响应")
        }
    }

    /**
     * URL path 段编码(用于 /proxies/{name}/... 的 name 部分)。
     *
     * 关键修复(真正的测速根因):
     *  URLEncoder.encode 把空格编成 "+",但这是 query string 的编码规则。
     *  mihomo API 的 name 在 URL path 里(/proxies/{name}/delay),
     *  按 RFC 3986,path 里的 "+" 是字面字符,不会被解码成空格。
     *
     *  也就是说:
     *   - 旧代码:URLEncoder.encode("香港 01") = "香港+01"
     *   - mihomo 收到 /proxies/香港+01/delay,找名为 "香港+01" 的节点
     *   - 找不到 → 返回错误 → testDelay 返回 -1
     *
     *  完美解释用户报告:
     *   - wd-purple 订阅全部测不出来 → 节点名都含空格
     *   - 另一个机场部分能测 → 部分节点名不含空格
     *   - 手机上别的 Clash 客户端正常 → 它们 path 编码正确
     *
     *  修复:URLEncoder.encode 后把 "+" 替换成 "%20"(URL path 里空格的正确编码)。
     */
    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
