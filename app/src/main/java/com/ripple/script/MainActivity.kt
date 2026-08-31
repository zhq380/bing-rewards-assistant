package com.ripple.script

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ripple.script.ui.AppRoot
import com.ripple.script.ui.theme.RippleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotiIfNeeded()
        ensureAccessibilityOn()
        setContent {
            RippleTheme {
                AppRoot()
            }
        }
        // 延迟请求电池优化白名单：确保 UI 先渲染完成，
        // 避免定制 ROM 在系统设置页面弹出时将后台 Activity 杀掉
        if (savedInstanceState == null && !isFirstLaunch) {
            isFirstLaunch = true
            window.decorView.postDelayed({ requestIgnoreBatteryIfNeeded() }, 800L)
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台都检查：定制 ROM 可能在后台杀掉无障碍，
        // 用户点开应用的瞬间是恢复的最佳时机（有 WRITE_SECURE_SETTINGS 则直接写回）
        ensureAccessibilityOn()
    }

    companion object {
        private var isFirstLaunch = false
    }

    /**
     * 确保无障碍开启：
     * 1. 已开启 → 无操作
     * 2. 被杀且有 WRITE_SECURE_SETTINGS → 后台线程自动写回恢复
     * 3. 被杀且无权限 → 发高优先级通知引导（守护协程也会接力检测）
     */
    private fun ensureAccessibilityOn() {
        if (com.ripple.script.util.Permissions.isAccessibilityOn(this) &&
            com.ripple.script.service.AutoAccessibilityService.instance != null
        ) return
        Thread {
            val ok = com.ripple.script.util.Permissions.enableAccessibility(this)
            android.util.Log.w("RippleGuard", "MainActivity 检测到无障碍失效，恢复=$ok")
        }.start()
        // 无论能否自动恢复，确保常驻守护服务在跑（它会轮询检测并通知引导）
        com.ripple.script.service.FloatingControllerService.startIdle(this)
    }

    /** Android 13+ 需运行时授予通知权限（前台服务状态通知用） */
    private fun requestNotiIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }

    /** 请求加入电池优化白名单（「不限制」后台运行），已加入则不弹窗 */
    private fun requestIgnoreBatteryIfNeeded() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }
}
