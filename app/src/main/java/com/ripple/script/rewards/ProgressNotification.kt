package com.ripple.script.rewards

import android.app.Activity
import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.ripple.script.MainActivity
import com.ripple.script.R

/**
 * 一键会话的实时进度通知。
 *
 * - Android 16 (API 36) 及以上：用 Google「Live Updates」(Promoted Ongoing) + [Notification.ProgressStyle]，
 *   高版本系统的流体通知会原生承接，以胶囊/卡片形态呈现在状态栏。
 * - 低于 API 36：降级为普通 ongoing 通知（仍保持同 ID 更新进度）。
 *
 * 增强：
 * - 流体云胶囊显示：标题 + 短文本 + 进度条
 * - 展开内容：阶段详情 + 搜索数/阅读数/活动数 + 预计剩余时间
 * - 暂停/继续/停止操作按钮
 */
object ProgressNotification {

    private const val CHANNEL_ID = "ripple_reward_progress"
    private const val NOTI_ID = 2001

    const val ACTION_PAUSE = "com.ripple.script.ACTION_PAUSE"
    const val ACTION_RESUME = "com.ripple.script.ACTION_RESUME"
    const val ACTION_STOP = "com.ripple.script.ACTION_STOP"

    /** 暂停/停止事件回调，由 RewardsController 注册 */
    var onAction: ((action: String) -> Unit)? = null

    private var receiverRegistered = false
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                ACTION_PAUSE, ACTION_RESUME, ACTION_STOP -> {
                    onAction?.invoke(action)
                }
            }
        }
    }

    fun ensureReceiver(context: Context) {
        if (!receiverRegistered) {
            val filter = IntentFilter(ACTION_PAUSE)
            filter.addAction(ACTION_RESUME)
            filter.addAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(actionReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    fun unregisterReceiver(context: Context) {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(actionReceiver) }
            receiverRegistered = false
        }
    }

    /**
     * 显示进度通知。
     *
     * @param context 上下文
     * @param text 当前阶段描述（如「签到中」「搜索 3/5」）
     * @param progress 进度百分比 0-100
     * @param title 通知标题（区分主应用/分身）
     * @param shortText 流体云胶囊上的浓缩文本
     * @param searched 已搜索次数
     * @param read 已阅读次数
     * @param daily 已完成每日活动数
     * @param remainingSec 预计剩余秒数（可选）
     * @param isPaused 是否暂停中
     */
    fun show(
        context: Context,
        text: String,
        progress: Int = 0,
        title: String = "微软积分",
        shortText: String? = null,
        searched: Int = 0,
        read: Int = 0,
        daily: Int = 0,
        remainingSec: Int = -1,
        isPaused: Boolean = false
    ) {
        ensureReceiver(context)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "积分任务进度",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableVibration(false)
                    setShowBadge(false)
                }
            )

            // 点击通知跳转应用主页
            val contentIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val piFlags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getActivity(context, 0, contentIntent, piFlags)

            // 暂停/继续/停止 按钮 PendingIntent
            val pausePi = PendingIntent.getBroadcast(
                context, 1, Intent(ACTION_PAUSE).setPackage(context.packageName), piFlags
            )
            val resumePi = PendingIntent.getBroadcast(
                context, 2, Intent(ACTION_RESUME).setPackage(context.packageName), piFlags
            )
            val stopPi = PendingIntent.getBroadcast(
                context, 3, Intent(ACTION_STOP).setPackage(context.packageName), piFlags
            )

            // 构建展开内容
            val detailLines = mutableListOf<String>()
            detailLines.add(text)
            if (searched > 0 || read > 0 || daily > 0) {
                val parts = mutableListOf<String>()
                if (searched > 0) parts.add("搜索 $searched")
                if (read > 0) parts.add("阅读 $read")
                if (daily > 0) parts.add("活动 $daily")
                detailLines.add(parts.joinToString(" · "))
            }
            if (remainingSec > 0) {
                val min = remainingSec / 60
                val sec = remainingSec % 60
                detailLines.add("预计剩余 ${min}分${sec.toString().padStart(2,'0')}秒")
            }
            if (isPaused) detailLines.add("⏸ 已暂停")

            val expandedText = detailLines.joinToString("\n")

            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .setAutoCancel(false)
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(expandedText)
                        .setBigContentTitle(title)
                )
                .addAction(
                    if (isPaused) R.drawable.ic_play else R.drawable.ic_play,
                    if (isPaused) "继续" else "暂停",
                    if (isPaused) resumePi else pausePi
                )
                .addAction(R.drawable.ic_play, "停止", stopPi)

            // Android 16 起：流体云增强
            if (Build.VERSION.SDK_INT >= 36) {
                builder.addExtras(
                    android.os.Bundle().apply {
                        putBoolean("android.requestPromotedOngoing", true)
                        if (shortText != null) putCharSequence("android.shortCriticalText", shortText)
                    }
                )
                builder.setStyle(
                    Notification.ProgressStyle()
                        .setStyledByProgress(true)
                        .setProgress(progress.coerceIn(0, 100))
                )
            }

            nm.notify(NOTI_ID, builder.build())
        }
    }

    fun dismiss(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTI_ID) }
    }
}
