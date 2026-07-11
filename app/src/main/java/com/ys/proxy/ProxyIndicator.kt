package com.ys.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

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
 *  - setTouchIntent 必须传非空 PendingIntent,否则 OngoingActivity 抛异常
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

        // 点指示器回到 App 的 PendingIntent(必须非空,OngoingActivity.setTouchIntent 要求)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return  // 极少情况拿不到 launch intent,直接放弃
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val touchIntent = PendingIntent.getActivity(context, 0, launchIntent, flags)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // 用系统图标,避免 vector 兼容性问题
            .setContentTitle("mihomo 代理")
            .setContentText(text)
            .setOngoing(true)            // 关键:ongoing 通知才能配 OngoingActivity
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // 默认优先级,确保显示
            .setShowWhen(false)
            .setContentIntent(touchIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // 锁屏也可见

        // 配 OngoingActivity:表盘底部圆圈 + 启动器"最近用过"显示
        val ongoingActivity = OngoingActivity.Builder(
            context, NOTIFICATION_ID, builder
        )
            .setStaticIcon(android.R.drawable.ic_dialog_info)  // 环境模式(息屏)静态图标
            .setTouchIntent(touchIntent)  // 必须非空
            .setStatus(Status.Builder()
                .addTemplate(text)
                .build())
            .build()

        // apply 把 OngoingActivity 配置写回 builder
        ongoingActivity.apply(context)
        // 兜底:显式 notify 一次,确保通知发出(某些 Wear OS 版本 apply 不发通知)
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    /** 隐藏表盘指示器 */
    fun hide() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /**
     * 测试通知能否显示(诊断用)。
     *
     * 发一个普通(非 ongoing)通知,如果通知栏能看到,说明通知系统工作正常,
     * 问题在 OngoingActivity 配置;如果连这个都看不到,说明权限或系统设置有问题。
     */
    fun test() {
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("mihomo 测试通知")
            .setContentText("如果你能看到这条,通知系统正常")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        notificationManager.notify(NOTIFICATION_ID + 1, builder.build())
    }

    private fun ensureChannel() {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "代理运行状态",
                NotificationManager.IMPORTANCE_DEFAULT  // 默认重要性,确保三星系统不静默丢弃
            ).apply {
                description = "代理运行时显示表盘指示器"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        channelCreated = true
    }
}
