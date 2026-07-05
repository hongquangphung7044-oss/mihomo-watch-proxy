package com.mihomo.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity

/**
 * 表盘"运行中"指示器封装。
 *
 * Wear OS 3+ 的 OngoingActivity API:发一个 ongoing notification + OngoingActivity,
 * 系统会在表盘底部显示可点按的小圆圈图标(三星 Galaxy Watch 底部那个圆圈就是这个机制)。
 * 用户点圆圈可一键回到 App。
 *
 * 用法:
 *   - 代理启动成功后调 [show]
 *   - 代理停止后调 [hide]
 *
 * 注意:
 *  - Android 13+(API 33)需要 POST_NOTIFICATIONS 运时权限,由 MainActivity 申请
 *  - 不需要 ForegroundService,纯 ongoing notification + OngoingActivity 更轻量
 *  - 通知频道必须先创建才能发通知
 */
class ProxyIndicator(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "proxy_running"
        private const val NOTIFICATION_ID = 0xBEEF
    }

    /** 频道是否已创建(避免重复创建) */
    private var channelCreated = false

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 显示表盘指示器 */
    fun show(text: String = "代理运行中") {
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)  // 用 App 图标做指示器图标
            .setContentTitle("mihomo 代理")
            .setContentText(text)
            .setOngoing(true)            // 关键:ongoing 通知才能配 OngoingActivity
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)  // 低优先级,不响铃不弹窗
            .setShowWhen(false)

        // 点指示器回到 App
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            val pi = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(pi)
        }

        // 配 OngoingActivity:表盘底部圆圈 + 启动器"最近用过"显示
        val ongoingActivity = OngoingActivity.Builder(
            context, NOTIFICATION_ID, builder
        )
            .setAnimatedIcon(R.drawable.ic_launcher_foreground)  // 活动模式图标(可动画)
            .setStaticIcon(R.drawable.ic_launcher_foreground)    // 环境模式(息屏)静态图标
            .setTouchIntent(launchIntent?.let {
                PendingIntent.getActivity(
                    context, 1, it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            })
            .setStatus(OngoingActivity.Status.Builder()
                .addTemplate(text)
                .build())
            .build()

        ongoingActivity.apply(context)

        // apply 内部已发通知,但保险起见再 notify 一次(某些 Wear OS 版本 apply 行为不一)
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    /** 隐藏表盘指示器 */
    fun hide() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "代理运行状态",
                NotificationManager.IMPORTANCE_LOW  // 低重要性:不响铃,只在通知栏显示
            ).apply {
                description = "代理运行时显示表盘指示器"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        channelCreated = true
    }
}
