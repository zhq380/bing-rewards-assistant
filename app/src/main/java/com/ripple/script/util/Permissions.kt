package com.ripple.script.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.ripple.script.service.AutoAccessibilityService

object Permissions {

    /** 无障碍服务是否已在系统设置中开启 */
    fun isAccessibilityOn(context: Context): Boolean {
        val expected = ComponentName(context, AutoAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { entry ->
            val flat = runCatching { ComponentName.unflattenFromString(entry)?.flattenToString() }.getOrNull()
            entry.equals(expected, ignoreCase = true) || flat?.equals(expected, ignoreCase = true) == true
        }
    }

    /** 是否已授予 WRITE_SECURE_SETTINGS（adb: pm grant）：可自动恢复无障碍 = "锁定" */
    fun canLockAccessibility(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 重新启用本应用的无障碍服务（需 WRITE_SECURE_SETTINGS 权限）。
     * 保留其他已启用的服务，返回是否写入成功。
     */
    fun enableAccessibility(context: Context): Boolean {
        if (!canLockAccessibility(context)) return false
        val expected = ComponentName(context, AutoAccessibilityService::class.java).flattenToString()
        val existing = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val newValue = if (existing.split(':').any { it.equals(expected, ignoreCase = true) }) existing
        else if (existing.isBlank()) expected
        else "$existing:$expected"
        val ok1 = Settings.Secure.putString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue
        )
        val ok2 = Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        return ok1 && ok2
    }

    /**
     * 强制重绑无障碍服务（需 WRITE_SECURE_SETTINGS，阻塞约 0.6s，请在后台线程调用）。
     *
     * 应对 定制 ROM 的「假活」状态：设置项仍显示开启，但服务实例已被系统杀死。
     * 处理方式：先把本服务从列表移除 → 短暂等待系统完成解绑清理 → 再写回，
     * 强制系统走一次完整的 unbind → bind 流程，服务得以重建。
     */
    fun forceReenable(context: Context): Boolean {
        if (!canLockAccessibility(context)) return false
        val expected = ComponentName(context, AutoAccessibilityService::class.java).flattenToString()
        val others = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':')
            .filter { it.isNotBlank() && !it.equals(expected, ignoreCase = true) }
        // 第一步：移除本服务，让系统清理已死的绑定
        Settings.Secure.putString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            others.joinToString(":")
        )
        try {
            Thread.sleep(600)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 第二步：写回，触发系统重新 bind
        val newValue = (others + expected).joinToString(":")
        val ok1 = Settings.Secure.putString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue
        )
        val ok2 = Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        return ok1 && ok2
    }

    /** 悬浮窗权限 */
    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 电池优化白名单（「不限制」后台运行） */
    fun isIgnoringBattery(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
