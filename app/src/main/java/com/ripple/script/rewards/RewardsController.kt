package com.ripple.script.rewards

import android.content.Context
import android.os.PowerManager
import com.ripple.script.data.RewardsStore
import com.ripple.script.data.RunRecord
import com.ripple.script.data.SearchKeywords
import com.ripple.script.service.AutoAccessibilityService
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * 一键签到的运行驱动：编排 无障碍服务 → BingUi → RewardsAgent → 数据落盘 的完整流程。
 *
 * 关键点：
 * - 每次运行前轮询等待 [AutoAccessibilityService.instance]，应对 ColorOS 杀服务后的自愈窗口
 *   （每秒 poll 一次，最多 10 次；超时抛异常交由上层提示）。
 * - 全程持有 SCREEN_DIM_WAKE_LOCK 保持亮屏，finally 中释放。
 * - [RewardsResult] 成功后写入 [RewardsStore] 历史，便于签到页回溯。
 */
object RewardsController {

    /** 等待无障碍服务实例的最大轮询次数（每次 +1000ms） */
    private const val SERVICE_WAIT_ATTEMPTS = 10

    /**
     * 执行一次完整签到流程。
     *
     * @param onPhase 阶段进度回调（由 RewardsAgent.onProgress 转发）
     * @param onLog   附加日志/结果回调（区别于阶段进度，用于结尾摘要等）
     * @param onResult 最终 [RewardsResult] 回调
     */
    suspend fun run(
        context: Context,
        store: RewardsStore,
        onPhase: (String) -> Unit,
        onLog: (String) -> Unit,
        onResult: (RewardsResult) -> Unit,
        control: RunControl = RunControl(),
        isClone: Boolean = false
    ) {
        // 1. 等待无障碍服务实例（自愈窗口内自愈）
        val svc = waitForService(context, onLog)

        val params = store.loadParams()
        val script = if (isClone) params.clone else params.main
        val notiTitle = if (isClone) "微软积分 · 分身" else "微软积分 · 主应用"

        // 2. 组装运行时依赖
        val ui = AccessibilityBingUi(svc, script.bingTargetSerial, script.autoLaunch)
        // 统一识别核心：注入屏幕尺寸供广告区/坐标换算；B 路线端侧 kNN（protos.json 样本库）已接入
        val m = svc.resources.displayMetrics
        val classifier = PageStateClassifier(
            mlClassifier = KnnScreenshotClassifier(context),
            screenWidth = m.widthPixels,
            screenHeight = m.heightPixels
        )
        val words = runCatching { SearchKeywords.load(context) }.getOrDefault(emptyList())
        // 运行时进度追踪
        var searched = 0
        var read = 0
        var daily = 0
        val startAt = System.currentTimeMillis()

        val notiPhase: (String) -> Unit = { phase ->
            onPhase(phase)
            // 从阶段文本解析计数
            phase.let { p ->
                val s = "已搜索 (\\d+)".toRegex().find(p)?.groupValues?.get(1)?.toIntOrNull()
                if (s != null) searched = s
                val r = "第(\\d+)篇".toRegex().find(p)?.groupValues?.get(1)?.toIntOrNull()
                if (r != null) read = r
                val d = "每日活动完成（(\\d+)".toRegex().find(p)?.groupValues?.get(1)?.toIntOrNull()
                if (d != null) daily = d
            }
            val elapsed = (System.currentTimeMillis() - startAt) / 1000
            val estimatedTotal = (script.searchCount * script.searchGapSeconds +
                script.readCount * script.readSeconds +
                script.dailySetCountSeconds())
            val remaining = (estimatedTotal - elapsed).coerceAtLeast(0)
            ProgressNotification.show(
                context, phase,
                title = notiTitle,
                progress = estimateProgress(phase),
                shortText = shortTextOf(phase),
                searched = searched,
                read = read,
                daily = daily,
                remainingSec = remaining.toInt(),
                isPaused = control.paused.value
            )
        }

        // 注册通知按钮回调
        ProgressNotification.onAction = { action ->
            when (action) {
                ProgressNotification.ACTION_PAUSE -> {
                    control.setPaused(true)
                    onPhase("⏸ 已暂停")
                }
                ProgressNotification.ACTION_RESUME -> {
                    control.setPaused(false)
                    onPhase("▶ 继续执行")
                }
                ProgressNotification.ACTION_STOP -> {
                    control.requestStop()
                    onPhase("⏹ 已停止")
                }
            }
        }

        val agent = RewardsAgent(
            ui = ui,
            searchCount = script.searchCount,
            readCount = script.readCount,
            onProgress = notiPhase,
            keywords = { words },
            control = control,
            readMs = script.readSeconds * 1000L,
            searchGapMs = script.searchGapSeconds * 1000L,
            dailySetMs = script.dailySetSeconds * 1000L,
            searchEnabled = script.searchEnabled,
            readEnabled = script.readEnabled,
            dailySetEnabled = script.dailySetEnabled,
            isClone = isClone,
            autoCount = script.autoCount,
            classifier = classifier,
            // 断点续跑进度：按实例隔离（主空间 / 分身各一份）
            progress = RewardsStore(context, if (isClone) "clone" else "main")
        )

        // 3. 保持亮屏（finally 释放）
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "rewards:wake")
        wakeLock.acquire(params.keepScreenMs)

        // 切换前停留提示，避免"一点就瞬间跳去必应"
        onPhase("正在前往必应执行…")
        delay(2000)

        val result = try {
            ProgressNotification.show(context, "一键签到开始", title = notiTitle)
            agent.run()
        } finally {
            if (wakeLock.isHeld) {
                runCatching { wakeLock.release() }
            }
            ProgressNotification.dismiss(context)
            ProgressNotification.onAction = null
            ProgressNotification.unregisterReceiver(context)
        }
        val duration = System.currentTimeMillis() - startAt
        // 4. 落盘历史记录（成功与否均记录，保留人工干预线索）
        store.addRecord(
            RunRecord(
                date = LocalDate.now().toString(),
                success = result.success,
                message = result.message,
                signedIn = result.signedIn,
                searched = result.searched,
                read = result.read,
                dailySet = result.dailySet,
                timestamp = System.currentTimeMillis(),
                durationMs = duration
            )
        )

        onLog(result.message.ifBlank { if (result.success) "签到完成" else "签到未完成" })
        onResult(result)
    }

    /** 从阶段描述提取供流体云胶囊显示的浓缩短文本 */
    private fun shortTextOf(phase: String): String {
        val s = phase.trim()
        return when {
            s.contains("每日活动") || s.contains("活动") -> "每日活动"
            s.contains("已搜索") -> s.substringAfter("已搜索").take(8)
            s.contains("搜索") -> "搜索中"
            s.contains("第") && s.contains("篇") -> "阅读中"
            s.contains("阅读") -> "阅读中"
            s.contains("签到") -> "签到中"
            s.contains("准备") -> "准备中"
            s.contains("收尾") || s.contains("完成") -> "已完成"
            else -> s.take(8)
        }
    }

    /** 从阶段描述粗估进度百分比（0-100），供通知进度条展示 */
    private fun estimateProgress(phase: String): Int = when {
        phase.contains("准备") -> 5
        phase.contains("签到") -> 20
        phase.contains("每日活动") || phase.contains("活动") -> 25
        phase.contains("扫描") || phase.contains("检测") -> 30
        phase.contains("已搜索") -> 55
        phase.contains("搜索") -> 50
        phase.contains("第") && phase.contains("篇") -> 80
        phase.contains("阅读") -> 75
        phase.contains("收尾") || phase.contains("完成") -> 100
        else -> 30
    }

    private suspend fun waitForService(context: Context, onLog: (String) -> Unit): AutoAccessibilityService {
        // 第一段：常规自愈窗口
        repeat(SERVICE_WAIT_ATTEMPTS) { attempt ->
            AutoAccessibilityService.instance?.let { return it }
            onLog("等待无障碍服务自愈…（${attempt + 1}/$SERVICE_WAIT_ATTEMPTS）")
            delay(1000)
        }
        // 第二段：触发强制重绑（WRITE_SECURE_SETTINGS 授予时移除再写回）后再等
        onLog("无障碍服务未恢复，尝试强制重绑…")
        runCatching { com.ripple.script.service.AccessibilityGuard.check(context) }
        repeat(20) { attempt ->
            AutoAccessibilityService.instance?.let { return it }
            onLog("等待重绑后自愈…（${attempt + 1}/20）")
            delay(1000)
        }
        AutoAccessibilityService.instance?.also { return it }
        throw IllegalStateException("无障碍服务不可用（等待自愈 30s 超时），请在系统设置中开启")
    }
}