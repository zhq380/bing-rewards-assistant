package com.ripple.script.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.os.bundleOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 供 RewardsUi 复用的输入/手势原语（不依赖旧 Step 模型）。
 *
 * 从原 `AccessibilityStepExecutor.input` 与提交搜索逻辑提炼为可直接挂起调用的扩展，
 * 统一复用 `rootInActiveWindow` 与 `bundleOf`/ACTION_SET_TEXT/ACTION_IME_ACTION。
 */
object AccessibilityStepExecutorCompat {

    /** 向焦点输入节点写入文本；找不到焦点输入节点返回 false */
    suspend fun inputText(svc: AutoAccessibilityService, text: String): Boolean =
        withContext(Dispatchers.Default) {
            val root = svc.rootInActiveWindow ?: return@withContext false
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: return@withContext false
            val args = bundleOf(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

    /**
     * 提交搜索（回车）。必应搜索框是自定义输入框，不响应无障碍 ACTION_IME_ENTER，
     * 故直接点击键盘右下角「搜索/回车」键提交（真机标定坐标，1272x2772）。
     */
    suspend fun submitIme(svc: AutoAccessibilityService): Boolean =
        withContext(Dispatchers.Default) {
            svc.dispatchClick(SEARCH_ENTER_X, SEARCH_ENTER_Y)
            true
        }

    /** 系统级返回 */
    suspend fun goBack(svc: AutoAccessibilityService): Boolean =
        withContext(Dispatchers.Default) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }

    /** 按屏高比例向上滑动（ratio∈0.1~1.0） */
    suspend fun scrollUp(svc: AutoAccessibilityService, ratio: Float) {
        val m = svc.resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        val dist = (h * ratio.coerceIn(0.1f, 1.0f)).toInt()
        svc.dispatchSwipe(w / 2, h * 3 / 4, w / 2, h * 3 / 4 - dist, 400)
    }

    /** 键盘右下角「搜索/回车」键中心坐标（1272x2772） */
    private const val SEARCH_ENTER_X = 1140
    private const val SEARCH_ENTER_Y = 2520
}