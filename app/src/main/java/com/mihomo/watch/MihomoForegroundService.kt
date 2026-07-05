package com.mihomo.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/**
 * 代理运行状态前台服务。
 *
 * 核心作用:
 *  1. 让通知持久化 —— App 被杀后通知仍然存在(系统优先保活前台服务)
 *  2. 在手表"后台服务"列表显示 —— 用户能看到代理在跑,可手动停
 *  3. 表盘底部圆圈指示器(OngoingActivity) —— 必须配前台服务才稳定显示
 *
 * 注意:这个 Service 不负责启动/停止 mihomo 进程(mihomo 由 Shizuku 以 shell
 * 权限独立运行)。Service 只管通知显示。停止代理时 AppViewModel 会先停 mihomo,
 * 再调 stopService() 让通知消失。
 *
 * 生命周期:
 *  - startForegroundService → onStartCommand → startForeground(显示通知)
 *  - stopService → onDestroy(通知消失,Service 退出)
 *  - App 被杀但 Service 还在 → 通知保持,mihomo 不受影响
 */
class MihomoForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "proxy_running"
        private const val NOTIFICATION_ID = 0xBEEF

        /** 启动前台服务 */
        fun start(context: Context) {
            val intent = Intent(context, MihomoForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止前台服务(通知消失) */
        fun stop(context: Context) {
            context.stopService(Intent(context, MihomoForegroundService::class.java))
        }

        /** 更新通知文本(可选,如显示当前节点) */
        fun update(context: Context, text: String) {
            val intent = Intent(context, MihomoForegroundService::class.java)
                .putExtra("text", text)
                .putExtra("update", true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private var channelCreated = false

        private fun ensureChannel(context: Context) {
            if (channelCreated) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "代理运行状态",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "代理运行时显示通知和表盘指示器"
                    setShowBadge(false)
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
            channelCreated = true
        }

        private fun buildNotification(context: Context, text: String): Notification {
            ensureChannel(context)

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val touchIntent = if (launchIntent != null) {
                PendingIntent.getActivity(context, 0, launchIntent, flags)
            } else null

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_proxy_notification)
                .setContentTitle("mihomo 代理")
                .setContentText(text)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            if (touchIntent != null) {
                builder.setContentIntent(touchIntent)
            }

            // 配 OngoingActivity:表盘底部圆圈
            if (touchIntent != null) {
                val ongoingActivity = OngoingActivity.Builder(
                    context, NOTIFICATION_ID, builder
                )
                    .setStaticIcon(R.drawable.ic_proxy_notification)
                    .setTouchIntent(touchIntent)
                    .setStatus(Status.Builder().addTemplate(text).build())
                    .build()
                ongoingActivity.apply(context)
            }

            return builder.build()
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "代理运行中"

        val notification = buildNotification(this, text)

        // Android 14+(API 34)必须指定 foregroundServiceType
        // specialUse 适用于代理/VPN 等不属于标准类别的服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY  // 被杀后系统尝试重建
    }

    override fun onDestroy() {
        super.onDestroy()
        // 通知由 stopService 自动取消,这里不额外处理
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 用户从最近任务滑掉 App 时调用,但 Service 继续运行 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 不停 service,保持通知和后台运行
        super.onTaskRemoved(rootIntent)
    }
}
