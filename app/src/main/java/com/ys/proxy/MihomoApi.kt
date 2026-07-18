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
     * 测试 URL 选择(参考开源代理 Clash for Android / FlClash / Mihomo 默认配置):
     *  - 默认用 `http://www.gstatic.com/generate_204`(HTTP,非 HTTPS)
     *    这是 Clash 系内核的默认测速 URL,几乎所有开源代理都用它。
     *  - HTTP 的 204 端点只测连通性 + RTT,不涉及 TLS 握手,最稳。
     *
     * 关键修复(对标开源项目,彻底解决测速失败):
     *  - expected 参数用 204(默认值),匹配 generate_204 端点实际返回的状态码。
     *    之前用 expected=200 是错的 —— generate_204 端点按定义返回 204 No Content,
     *    expected=200 会让 mihomo 判定所有节点测速失败。
     *  - 不依赖 HTTP 状态码:mihomo 测速失败时返回 400/500/504,body 是
     *    {"message":"..."} 而非 {"delay":N}。直接读 body JSON,有 delay 字段就用。
     *  - 用独立的 testClient(readTimeout=20s),避免被 fastClient 的 5s 超时截断
     *    (mihomo 内部 timeout=15s,OkHttp 必须 >15s)。
     *
     * timeout:15000ms(15 秒),给慢节点 + 网络抖动足够时间。
     */
    fun testDelay(nodeName: String, testUrl: String = "http://www.gstatic.com/generate_204"): Int {
        return try {
            // expected=204:generate_204 端点按定义返回 204 No Content
            val req = Request.Builder()
                .url("$baseUrl/proxies/${urlEncode(nodeName)}/delay?timeout=15000&expected=204&url=${urlEncode(testUrl)}")
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

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
