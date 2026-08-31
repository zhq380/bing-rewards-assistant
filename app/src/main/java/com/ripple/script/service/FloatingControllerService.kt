package com.ripple.script.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台常驻服务：只负责「无障碍保活 + 常驻通知」，不再执行任何脚本。
 * - 注册 ACTION_TIME_TICK 分钟级心跳，进程存活期间每 60 秒检测一次无障碍「假活」/关闭
 * - 启动无障碍守护协程（自适应轮询），并在被系统清理时尽力恢复
 * - 通过 IDLE 常驻通知让系统（含 ColorOS）将其视为用户可见的活跃任务
 */
class FloatingControllerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var guardJob: kotlinx.coroutines.Job? = null
    private var timeTickReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // 借鉴参考实现（智慧岛）：注册 ACTION_TIME_TICK 分钟级心跳，
        // 进程存活期间每 60 秒检测一次无障碍「假活」/关闭状态并尽力恢复
        registerTimeTickHeartbeat()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceAlive = true
        // 无论以何种模式启动，均进入空闲常驻态，保持后台就绪 + 无障碍保活
        startAsForeground(MODE_IDLE)
        startAccessibilityGuard()
        KeepAlive.scheduleNext(this) // 系统级看门狗（进程死后仍可拉起）
        return START_STICKY
    }

    /** 用户划掉任务卡片：常驻服务继续守护（stopWithTask=false 已声明，此处加固重启） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (guardJob?.isActive != true) {
            val pi = android.app.PendingIntent.getService(
                this, 2,
                Intent(this, FloatingControllerService::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.setExact(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000, pi)
        }
    }

    /**
     * 无障碍守护（自适应轮询，作为 selfHeal 事件自愈的第二道保险）：
     *
     * 检测两类失效：
     * a) 设置被系统关闭 → enableAccessibility 写回
     * b) 「假活」：设置仍开启但服务实例已死 → forceReenable 移除再写回强制重绑
     *
     * 轮询策略：
     * - 空闲常驻态：静默巡检 3 分钟一次（与分钟级心跳同频；读 Settings.Secure 是轻量 IPC）
     * - 检测到一次被杀后：进入 2 分钟快速窗口，每 10 秒检测（ColorOS 常连环杀）
     *
     * 前提：adb 一次性授予 WRITE_SECURE_SETTINGS 后即可自动恢复，无需 root：
     *   adb shell pm grant com.ripple.script android.permission.WRITE_SECURE_SETTINGS
     */
    private fun startAccessibilityGuard(runMode: Boolean = false) {
        guardJob?.cancel()
        guardJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // 快速检测窗口截止时间；运行态从一开始就进入快速窗口
            var fastUntil = if (runMode) System.currentTimeMillis() + 60 * 60 * 1000L else 0L
            while (isActive) {
                val settingOn = com.ripple.script.util.Permissions.isAccessibilityOn(this@FloatingControllerService)
                val instanceAlive = AutoAccessibilityService.instance != null
                if (!settingOn || !instanceAlive) {
                    // 统一走 AccessibilityGuard 尽力恢复（forceReenable/enableAccessibility）
                    val restored = AccessibilityGuard.check(this@FloatingControllerService)
                    if (!restored) notifyAccessibilityOff()
                    // 被杀后进入快速窗口，捕捉连环杀
                    fastUntil = System.currentTimeMillis() + 2 * 60 * 1000L
                }
                val fast = System.currentTimeMillis() < fastUntil
                // 空闲巡检与分钟级心跳同频（3 分钟）；快速窗内每 10 秒
                delay(if (fast) 10_000L else 3 * 60 * 1000L)
            }
        }
    }

    /**
     * 分钟级心跳（ACTION_TIME_TICK）：系统每分钟广播一次。
     * - 动态注册（静态注册在 Android 8.0+ 收不到隐式广播）
     * - 进程存活期间每 60 秒检测一次无障碍「假活」/关闭状态并尽力恢复，
     *   借鉴参考实现（智慧岛 BootReceiver 监听 ACTION_TIME_TICK）的保活思路
     * - 进程被彻底回收后动态 receiver 随之失效，此时由 AlarmManager 看门狗复活
     */
    private fun registerTimeTickHeartbeat() {
        if (timeTickReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action != Intent.ACTION_TIME_TICK) return
                // 心跳内不能做阻塞（forceReenable 会 sleep），移交 IO 协程执行
                CoroutineScope(Dispatchers.IO).launch { AccessibilityGuard.check(context) }
            }
        }
        val filter = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        timeTickReceiver = receiver
        android.util.Log.d("RippleGuard", "分钟级心跳已注册 (ACTION_TIME_TICK)")
    }

    /** 无障碍被关闭且无法自动恢复：发高优先级通知，点击直达无障碍设置 */
    private fun notifyAccessibilityOff() {
        val channelId = "ripple_a11y_guard"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                channelId, "无障碍守护", android.app.NotificationManager.IMPORTANCE_HIGH
            )
        )
        val pi = android.app.PendingIntent.getActivity(
            this, 1,
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("无障碍服务被关闭")
            .setContentText("请重新开启无障碍，以便自动完成积分任务")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, notification)
    }

    /** 升级为前台服务：常驻通知让系统（含 ColorOS 后台清理）视为用户可见的活跃任务 */
    private fun startAsForeground(mode: String) {
        val channelId = "ripple_controller"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                channelId,
                "后台服务",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
        val notification = android.app.Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("积分助手")
            .setContentText("后台常驻就绪")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTI_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    override fun onDestroy() {
        serviceAlive = false
        timeTickReceiver?.let { runCatching { unregisterReceiver(it) } }
        timeTickReceiver = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 前台服务是否存活（供看门狗 Receiver 判断是否需要拉起） */
        @Volatile
        var serviceAlive: Boolean = false
            private set

        const val EXTRA_MODE = "mode"
        const val MODE_IDLE = "idle"
        private const val NOTI_ID = 1001

        /** 保活拉起：进入空闲常驻态（不显示悬浮条） */
        fun startIdle(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, FloatingControllerService::class.java)
                        .putExtra(EXTRA_MODE, MODE_IDLE)
                )
            }
        }
    }
}