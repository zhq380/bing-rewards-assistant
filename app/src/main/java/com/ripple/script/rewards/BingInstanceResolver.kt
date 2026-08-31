package com.ripple.script.rewards

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import com.ripple.script.util.AppLauncher

/**
 * 检测必应（com.microsoft.bing）在手机上安装的实例（用户空间），供设置页选择跳转目标。
 *
 * 约定 userSerial：0 = 主空间；>0 = 应用分身（部分 ROM 固定在 user 999）。
 * 规则：用户空间里存在可启动的必应 Activity 即视为一个可用实例。
 *
 * > 说明：某些 ROM 的应用分身固定在一个 user（999）内，通常只开 1 个分身；
 * > 若用户开了多个分身，本列表仍按「主空间 + 分身(999)」两项聚合展示，
 * > 选「分身」即操作该分身 user 下的必应。
 */
object BingInstanceResolver {

    const val BING_PACKAGE = "com.microsoft.bing"
    const val MAIN_SERIAL = 0L
    const val DUAL_SERIAL = 999L // 应用分身通常固定 user

    data class BingInstance(val serial: Long, val label: String)

    /** 列出可用必应实例：主空间 + 分身（若存在） */
    fun list(context: Context): List<BingInstance> {
        val out = mutableListOf<BingInstance>()
        val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        // 主空间
        if (la.getActivityList(BING_PACKAGE, Process.myUserHandle()).isNotEmpty()) {
            out += BingInstance(MAIN_SERIAL, "主空间 · 微软必应")
        }
        // 分身（固定 user 999）
        val dualHandle = AppLauncher.userHandleOf(DUAL_SERIAL.toInt())
        if (dualHandle != null && la.getActivityList(BING_PACKAGE, dualHandle).isNotEmpty()) {
            out += BingInstance(DUAL_SERIAL, "分身 · 微软必应 1")
        }
        return out
    }

    /** 是否选中的是分身 */
    fun isDual(serial: Long): Boolean = serial > 0L
}