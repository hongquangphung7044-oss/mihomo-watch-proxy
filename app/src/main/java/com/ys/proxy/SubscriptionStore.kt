package com.ys.proxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedSubscription(
    val name: String,
    val url: String,
    val savedAt: Long
)

/**
 * 多订阅本地存储。
 *
 * 用 SharedPreferences 存 JSON 数组,每个订阅: {name, url, savedAt}。
 * 名字默认取 URL 域名,同名会覆盖。
 */
class SubscriptionStore(context: Context) {

    private val prefs = context.getSharedPreferences("saved_subscriptions", Context.MODE_PRIVATE)

    /** 列出所有已保存订阅,按保存时间倒序(最新的在前) */
    fun list(): List<SavedSubscription> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(json)
        val result = mutableListOf<SavedSubscription>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                SavedSubscription(
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    savedAt = obj.getLong("savedAt")
                )
            )
        }
        return result.sortedByDescending { it.savedAt }
    }

    /** 保存(同名覆盖) */
    fun save(name: String, url: String) {
        val current = list().filterNot { it.name == name }.toMutableList()
        current.add(SavedSubscription(name, url, System.currentTimeMillis()))
        write(current)
    }

    fun delete(name: String) {
        write(list().filterNot { it.name == name })
    }

    private fun write(list: List<SavedSubscription>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("name", s.name)
                    put("url", s.url)
                    put("savedAt", s.savedAt)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "subscriptions"
    }
}
