package com.ripple.script.util

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log

/**
 * 精确到用户空间的应用启动工具。
 *
 * ColorOS 的应用分身（com.oplus.multiapp）在目标包同时存在主空间与分身实例时，
 * 普通 `getLaunchIntentForPackage + startActivity` 会弹出「主空间 / 分身」选择框。
 * 只要脚本绑定了目标应用（无论主空间 serial=0 还是分身 serial>0），都应调用本工具，
 * 用 [LauncherApps.startMainActivity] 直接指定 UserHandle，彻底避开选择器。
 */
object AppLauncher {
    private const val TAG = "RippleLaunch"

    /**
     * 将应用启动到指定用户空间。
     *
     * @param userSerial 目标用户序列号：0 = 主空间，>0 = 应用分身/工作资料。
     * @return true 表示已成功调度启动；false 表示未找到目标用户或可启动 Activity。
     */
    fun launchToUser(context: Context, packageName: String, userSerial: Long): Boolean {
        val result = runCatching { launchInternal(context, packageName, userSerial) }
        result.onFailure { Log.w(TAG, "launchToUser 异常: ${it.message}") }
        return result.getOrDefault(false)
    }

    private fun launchInternal(
        context: Context,
        packageName: String,
        userSerial: Long
    ): Boolean {
        val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager

        // 先在 userProfiles 里按 serial 精确匹配（与选择器存储的 serial 同源，保证回环一致）
        val profiles = um.userProfiles
        val serialList = profiles.joinToString { um.getSerialNumberForUser(it).toString() }
        Log.d(TAG, "userProfiles=[$serialList] 目标serial=$userSerial pkg=$packageName")

        val profileHandle: UserHandle? = profiles.firstOrNull {
            um.getSerialNumberForUser(it) == userSerial
        }
        if (profileHandle == null && userSerial == 0L) {
            // 兜底：serial=0 视为主空间（个别 ROM 主用户 serial 可能非 0）
            Log.w(TAG, "serial=0 无 profile 匹配，回退 Process.myUserHandle")
        }
        val handle: UserHandle = profileHandle
            ?: Process.myUserHandle().takeIf { userSerial == 0L }
            ?: return launchByDirectHandle(la, userSerial, packageName)

        val activities = la.getActivityList(packageName, handle)
        Log.d(TAG, "getActivityList 返回 ${activities.size} 个 activity")
        val comp = activities.firstOrNull()?.componentName ?: run {
            Log.w(TAG, "未找到可启动 activity: $packageName")
            // profile 里找不到（如系统分身只镜像未独立安装）→ 尝试直接构造句柄
            return launchByDirectHandle(la, userSerial, packageName)
        }

        // startMainActivity 内部会补全 NEW_TASK / RESET_TASK_IF_NEEDED，无需手动加 flags
        la.startMainActivity(comp, handle, null, null)
        Log.d(TAG, "startMainActivity 成功: $comp @serial=${um.getSerialNumberForUser(handle)}")
        return true
    }

    /**
     * ColorOS 应用分身（com.oplus.multiapp，固定 user 999）不在 userProfiles 内，
     * 直接构造 [UserHandle.of]（隐藏 API，走反射）尝试启动。serial 与 userId 在该场景下一致。
     */
    private fun launchByDirectHandle(
        la: LauncherApps,
        userSerial: Long,
        packageName: String
    ): Boolean = runCatching {
        if (userSerial <= 0L || userSerial > Int.MAX_VALUE) return false
        val handle = userHandleOf(userSerial.toInt()) ?: return false
        val activities = la.getActivityList(packageName, handle)
        Log.d(TAG, "directHandle($userSerial) getActivityList 返回 ${activities.size} 个")
        val comp = activities.firstOrNull()?.componentName ?: return false
        la.startMainActivity(comp, handle, null, null)
        Log.d(TAG, "directHandle startMainActivity 成功 @user=$userSerial")
        true
    }.onFailure {
        Log.w(TAG, "directHandle($userSerial) 失败: ${it.message}")
    }.getOrDefault(false)

    /**
     * 反射构造任意 userId 的 [UserHandle]（UserHandle.of 为隐藏 API，灰名单可反射）。
     * 返回 null 表示当前系统限制不可用。
     */
    fun userHandleOf(userId: Int): UserHandle? = runCatching {
        val method = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
        @Suppress("PrivateApi")
        method.invoke(null, userId) as? UserHandle
    }.onFailure {
        Log.w(TAG, "UserHandle.of 反射失败: ${it.message}")
    }.getOrNull()
}