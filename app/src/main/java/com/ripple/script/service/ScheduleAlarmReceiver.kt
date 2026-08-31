package com.ripple.script.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 保活 Receiver：被 AlarmManager 唤醒后进行自检。
 * - BOOT_COMPLETED：重启后注册下一次看门狗自检
 * - ACTION_KEEPALIVE：看门狗自检（进程被杀后的最后一道兜底，
 *   参照 Tasker/Auto.js 的 AlarmManager 守护方案：协程守护随进程死，
 *   系统闹钟不随之取消，可把整个应用重新拉起）
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // 重启后只需恢复系统级看门狗（重启后所有 alarm 失效）
                KeepAlive.scheduleNext(context)
            }
            ACTION_KEEPALIVE -> {
                KeepAlive.scheduleNext(context) // 续期下一次自检
                CoroutineScope(Dispatchers.IO).launch {
                    // 进程死而复生后这里必然 alive=false → 尽力恢复无障碍（需 WRITE_SECURE_SETTINGS）
                    AccessibilityGuard.check(context)
                    // 前台守护服务随进程死掉时重新拉起（运行中则不打扰）
                    if (!FloatingControllerService.serviceAlive) {
                        FloatingControllerService.startIdle(context)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_KEEPALIVE = "com.ripple.script.ACTION_KEEPALIVE"
    }
}

/**
 * 无障碍保活统一检测（单一逻辑，多通道复用）：
 * - ACTION_KEEPALIVE 系统闹钟看门狗（进程死后复活兜底）
 * - ACTION_TIME_TICK 分钟级心跳（FloatingControllerService 动态注册）
 * - FloatingControllerService 的守护协程
 *
 * 借鉴参考实现（智慧岛 BootReceiver 监听 BOOT_COMPLETED + ACTION_TIME_TICK）
 * 的分钟级心跳思路：把「假活」发现/恢复窗口从分钟/小时级收缩到 1 分钟内。
 */
object AccessibilityGuard {

    /**
     * 一次性检测 + 尽力恢复。必须在后台线程调用（内部 forceReenable 会 sleep）。
     *
     * a) 设置开 + 实例活  → 运行正常，返回 true
     * b) 设置开 + 实例死（假活）→ forceReenable 移除再写回强制重绑
     * c) 设置关  → enableAccessibility 写回开启
     *
     * @return true 表示当前无障碍正常运行或已成功恢复
     */
    fun check(context: Context): Boolean {
        val settingOn = com.ripple.script.util.Permissions.isAccessibilityOn(context)
        val alive = AutoAccessibilityService.instance != null
        if (settingOn && alive) return true
        val ok = if (settingOn && !alive) {
            com.ripple.script.util.Permissions.forceReenable(context)
        } else {
            com.ripple.script.util.Permissions.enableAccessibility(context)
        }
        android.util.Log.w("RippleGuard", "保活检测: setting=$settingOn alive=$alive 恢复=$ok")
        return ok
    }
}

/**
 * 无障碍看门狗：AlarmManager 系统级闹钟自检（进程死后的复活兜底）。
 * - ACTION_TIME_TICK 每分钟心跳只在进程存活期间有效；进程被系统彻底回收后，
 *   动态 receiver 随之失效，仍靠本闹钟把应用拉起再恢复无障碍
 * - 间隔已从 15 分钟加快到 3 分钟（存活期检测交给分钟级心跳，本闹钟专注复活死进程）
 * - 注：用户「强制停止」会取消所有闹钟，此场景只能靠开机/下次手动启动恢复
 */
object KeepAlive {
    // 3 分钟：ALARM 既要及时复活死进程，又不能过密导致精确闹钟触发被系统限制。可再调低，但分钟级心跳已覆盖存活场景
    private const val INTERVAL_MS = 3 * 60_000L
    private const val REQUEST_CODE = 0x5EE1

    fun scheduleNext(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ScheduleAlarmReceiver.ACTION_KEEPALIVE
            }
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            // 精确闹钟可在 Doze 深睡中唤醒；无权限时退化为宽松版本（维护窗口触发）
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }
    }
}