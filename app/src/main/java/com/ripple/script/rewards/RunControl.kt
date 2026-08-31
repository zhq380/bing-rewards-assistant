package com.ripple.script.rewards

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 一键会话的运行时控制：暂停/继续 与 停止。
 *
 * 由主页（暂停/继续按钮）与停止按钮驱动；[RewardsAgent] 在各阶段关键点调用
 * [awaitIfPaused] 实现暂停挂起，被 [requestStop] 后抛出 [StoppedException] 提前结束。
 */
class RunControl {

    /** 是否暂停（true=暂停；暂停期间脚本挂起，不操作无障碍） */
    val paused = MutableStateFlow(false)

    /** 是否已请求停止（一旦为 true 不可逆，脚本尽快结束） */
    val cancelled = MutableStateFlow(false)

    fun requestStop() { cancelled.value = true }
    fun setPaused(v: Boolean) { paused.value = v }

    /**
     * 暂停挂起点：暂停期间循环等待；期间被请求停止则抛 [StoppedException]。
     * 未被停止的正常流程应立即返回。
     */
    suspend fun awaitIfPaused() {
        if (cancelled.value) throw StoppedException("已停止")
        while (paused.value) {
            if (cancelled.value) throw StoppedException("已停止")
            delay(80)
        }
    }

    /**
     * 可中断的 delay：暂停时立即挂起等待，恢复后继续计时；
     * 被停止时抛 [StoppedException]。步长 80ms 确保暂停响应 <100ms。
     */
    suspend fun interruptibleDelay(ms: Long) {
        if (cancelled.value) throw StoppedException("已停止")
        var remaining = ms
        val start = System.currentTimeMillis()
        while (remaining > 0) {
            if (cancelled.value) throw StoppedException("已停止")
            if (paused.value) {
                awaitIfPaused()
                val elapsed = System.currentTimeMillis() - start
                remaining = ms - elapsed
            } else {
                val step = remaining.coerceAtMost(80)
                delay(step)
                remaining -= step
            }
        }
    }

    /** 用户/保护机制主动停止脚本时抛出，供 [RewardsAgent] 提前结束并给出提示。 */
    class StoppedException(message: String) : Exception(message)
}