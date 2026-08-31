package com.ripple.script.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 手势分发失败/超时异常（替代旧引擎的 StepFailureException，供调用方重试） */
class GestureFailureException(message: String) : Exception(message)

class AutoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.d("RippleGuard", "无障碍服务已连接")
    }

    override fun onDestroy() {
        instance = null
        selfHeal("destroy")
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        selfHeal("unbind")
        // 返回 true：后续系统重绑时走 onRebind，保持服务可复用
        return true
    }

    /**
     * 事件驱动自愈：被系统解绑/销毁的瞬间立即恢复（需 adb 授予 WRITE_SECURE_SETTINGS）。
     * 这是恢复最快的路径（秒级），比守护协程轮询（10s~5min）快得多，且零轮询成本。
     *
     * 用独立线程而非协程：服务正被销毁时其所属协程可能已被取消，裸线程更可靠；
     * 多次重试 + 回避竞态（守护协程可能同时恢复，双保险以 instance 非空为完成标志）。
     */
    private fun selfHeal(reason: String) {
        if (!com.ripple.script.util.Permissions.canLockAccessibility(this)) {
            android.util.Log.w("RippleGuard", "selfHeal($reason) 跳过：未授予 WRITE_SECURE_SETTINGS")
            return
        }
        Thread {
            try {
                for (attempt in 1..5) {
                    // 守护协程或另一次自愈已完成恢复
                    if (AutoAccessibilityService.instance != null) return@Thread
                    val stillOn = com.ripple.script.util.Permissions.isAccessibilityOn(this)
                    val ok = if (stillOn) {
                        // 假活：设置仍开启但本实例已死 → 移除再写回强制重绑
                        com.ripple.script.util.Permissions.forceReenable(this)
                    } else {
                        // 设置已被关闭 → 直接写回
                        com.ripple.script.util.Permissions.enableAccessibility(this)
                    }
                    android.util.Log.w(
                        "RippleGuard",
                        "selfHeal($reason) attempt=$attempt mode=${if (stillOn) "rebind" else "writeback"} ok=$ok"
                    )
                    if (ok) {
                        Thread.sleep(1200)
                        if (AutoAccessibilityService.instance != null) {
                            android.util.Log.w("RippleGuard", "selfHeal($reason) 恢复成功")
                            return@Thread
                        }
                    }
                    Thread.sleep(1500)
                }
                android.util.Log.w("RippleGuard", "selfHeal($reason) 未成功，交由守护协程继续接管")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /**
     * 截取当前物理屏（display 0）。无障碍 takeScreenshot 截的是物理显示而非按用户隔离，
     * 因此能截到分身（u999）必应的画面，供后续 OCR 读取任务卡片文字。
     */
    suspend fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ContextCompat.getMainExecutor(this),
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bmp = runCatching {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                            runCatching { result.hardwareBuffer.close() }
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    override fun onInterrupt() = Unit

    // ---------- 手势分发 ----------

    private fun singlePath(x: Int, y: Int): Path = Path().apply {
        moveTo(x.toFloat(), y.toFloat())
        lineTo(x + 1f, y + 1f)
    }

    private fun linePath(x1: Int, y1: Int, x2: Int, y2: Int): Path = Path().apply {
        moveTo(x1.toFloat(), y1.toFloat())
        lineTo(x2.toFloat(), y2.toFloat())
    }

    /** 挂起等待一次手势完成（超时保护：回调偶发丢失时抛出失败，交给调用方重试而非永久挂起） */
    suspend fun awaitGesture(path: Path, duration: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(16)))
            .build()

        try {
            withTimeout<Unit>(duration + 1500) {
                suspendCancellableCoroutine { cont ->
                    val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resumeWithException(GestureFailureException("手势被系统取消"))
                        }
                    }, null)

                    if (!dispatched && cont.isActive) {
                        cont.resumeWithException(GestureFailureException("手势分发失败，请检查无障碍服务"))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw GestureFailureException("手势执行超时")
        }
    }

    suspend fun dispatchClick(x: Int, y: Int) = awaitGesture(singlePath(x, y), 50)

    suspend fun dispatchLongPress(x: Int, y: Int, duration: Long) =
        awaitGesture(singlePath(x, y), duration.coerceAtLeast(200))

    suspend fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long) =
        awaitGesture(linePath(x1, y1, x2, y2), duration.coerceAtLeast(50))

    companion object {
        @Volatile
        var instance: AutoAccessibilityService? = null
            private set
    }
}