package com.ripple.script.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启动 / 应用更新后自启：
 * - BOOT_COMPLETED：手机重启后拉起常驻前台服务，保持应用后台就绪
 * - MY_PACKAGE_REPLACED：APK 覆盖安装后自动恢复常驻
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> FloatingControllerService.startIdle(context)
        }
    }
}
