package com.ripple.script.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ScriptParams(
    // —— 基础计数 ——
    val searchCount: Int = 5,
    val readCount: Int = 4,
    val readSeconds: Int = 12,
    val searchGapSeconds: Int = 3,
    val dailySetSeconds: Int = 15,

    // —— 模块开关 ——
    val searchEnabled: Boolean = true,
    val readEnabled: Boolean = true,
    val dailySetEnabled: Boolean = true,
    val checkInEnabled: Boolean = true,          // 新增：独立开关签入
    val autoCount: Boolean = false,             // 自动化模式（缺口计算）

    // —— 必应入口 ——
    val autoLaunch: Boolean = true,
    val bingTargetSerial: Long = 0L,

    // —— 签到（新增组）——
    val checkinCoinOnly: Boolean = true,        // 只点硬币，不点横幅兜底（更准）
    val checkinPreferOcr: Boolean = true,       // 用 OCR 定位今日高亮硬币
    val checkinSequentialFallback: Boolean = true, // 直跳失败顺序点所有硬币
    val checkinVerifySeconds: Int = 12,         // 签到验证等待（积分到账窗口）

    // —— 搜索（新增组）——
    val searchUseRandomWords: Boolean = true,   // 搜索词从内置词库随机抽取
    val searchMixEnglish: Boolean = false,      // 夹杂英文词
    val searchBackoffCount: Int = 2,            // 单条搜索最大重试次数

    // —— 阅读（新增组）——
    val readContinuousBatch: Int = 4,           // 连续阅读冗余篇数（吸收延迟）
    val readWatchdogSeconds: Int = 60,          // 阅读单篇看门狗超时
    val readReturnRewardsAfter: Int = 0,        // 每读 N 篇回一次积分页（0 表示读完再回）
    val readAutoExtend: Boolean = true,         // 计分延迟时自动续读 N 篇

    // —— 每日活动（新增组）——
    val dailyMissThreshold: Int = 6,            // 连续未命中活动卡 N 次即退出
    val dailyClickRetries: Int = 3,             // 每张活动卡最多重试点击数

    // —— 稳定性（新增组：看门狗/超时/恢复）——
    val watchdogIdleSeconds: Int = 60,          // 无活动 N 秒强制恢复
    val instancePickerTimeoutSec: Int = 8,      // 分身选择器超时
    val webViewRenderMs: Long = 3000L,          // 积分页 WebView 渲染最大等待
    val recoverAfterFrozenSec: Int = 12,        // 掉桌面后 N 秒没回必应则重启
    val autoResumeOnBreak: Boolean = true,      // 中断后重开自动断点续跑
    val verboseLogcat: Boolean = true           // 详细日志（便于调试）
) {
    /** 估算每日活动总耗时（3个活动 × 停留时间） */
    fun dailySetCountSeconds(): Int = if (dailySetEnabled) 3 * dailySetSeconds else 0
}

@Serializable
data class ScreenConfig(
    /** 限宽像素（0 = 自动用 600dp；>0 = 用户自定义像素） */
    val maxWidthPx: Int = 0,
    /** 目标屏幕宽度（调试参考） */
    val screenWidthPx: Int = 0,
    /** 目标屏幕高度（调试参考） */
    val screenHeightPx: Int = 0,
    /** 是否启用自定义；false 时走默认 600dp 响应式 */
    val enabled: Boolean = false
)

/** 全局参数 + 两套脚本参数（主空间 / 应用分身各自独立）。 */
@Serializable
data class RewardParams(
    val keepScreenMs: Long = 5 * 60_000L,
    val screen: ScreenConfig = ScreenConfig(),
    val main: ScriptParams = ScriptParams(),
    val clone: ScriptParams = ScriptParams()
)

@Serializable
data class RunRecord(
    val date: String,          // yyyy-MM-dd
    val success: Boolean,
    val message: String,
    val signedIn: Boolean,
    val searched: Int,
    val read: Int,
    val dailySet: Int = 0,     // 完成的每日活动数
    val timestamp: Long,
    val durationMs: Long = 0L  // 本次运行耗时（毫秒）
)

/**
 * 当日进度（断点续跑用）：进程被杀 / 清后台后重开，可跳过今日已完成的部分。
 * 只记「已完成的计数」，不记中间态，避免崩在半途留下脏数据。
 */
@Serializable
data class RunProgress(
    val date: String = "",
    val searchedDone: Int = 0,
    val readDone: Int = 0,
    val dailyDone: Int = 0
)

/** 断点进度存取抽象（JVM 单测用内存实现，生产用 [RewardsStore]） */
interface RunProgressStore {
    /** 读取当日进度；非当日返回空进度 */
    fun load(): RunProgress
    fun save(p: RunProgress)
    fun clear()
}

/**
 * 运行参数 + 历史记录 + 当日进度存储（账号已由用户直接在 Bing 登录，不再存微软凭据）。
 *
 * @param instance 断点进度按实例隔离（"main" / "clone"），避免主空间与分身共用同一份进度。
 */
class RewardsStore(context: Context, private val instance: String = "main") : RunProgressStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "rewards")
    private val paramsFile = File(dir, "params.json")
    private val historyFile = File(dir, "history.json")
    private val progressFile = File(dir, "progress_$instance.json")

    fun loadParams(): RewardParams = runCatching {
        json.decodeFromString<RewardParams>(paramsFile.readText())
    }.getOrDefault(RewardParams())

    fun saveParams(p: RewardParams) {
        dir.mkdirs()
        paramsFile.writeText(json.encodeToString<RewardParams>(p))
    }

    /** 读取某个脚本（主空间/分身）的一套参数 */
    fun loadScript(isClone: Boolean): ScriptParams {
        val p = loadParams()
        return if (isClone) p.clone else p.main
    }

    /** 保存某个脚本（主空间/分身）的一套参数，保留另一套与全局字段 */
    fun saveScript(isClone: Boolean, s: ScriptParams) {
        val p = loadParams()
        val updated = if (isClone) p.copy(clone = s) else p.copy(main = s)
        saveParams(updated)
    }

    fun addRecord(r: RunRecord) {
        dir.mkdirs()
        val existing = runCatching {
            json.decodeFromString<List<RunRecord>>(historyFile.readText())
        }.getOrDefault(emptyList())
        val list = existing + r
        historyFile.writeText(json.encodeToString(ListSerializer(RunRecord.serializer()), list))
    }

    fun loadRecords(): List<RunRecord> = runCatching {
        json.decodeFromString<List<RunRecord>>(historyFile.readText())
    }.getOrDefault(emptyList())

    /** 7 日趋势：按日期聚合每日成功积分（搜索+阅读+活动），返回近 7 天每天的积分 */
    fun loadWeeklyPoints(): List<Pair<String, Int>> {
        val records = loadRecords()
        val cal = java.util.Calendar.getInstance()
        val result = mutableListOf<Pair<String, Int>>()
        for (i in 6 downTo 0) {
            val c = cal.clone() as java.util.Calendar
            c.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val dateStr = "%04d-%02d-%02d".format(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH)
            )
            val dayPoints = records
                .filter { it.date == dateStr && it.success }
                .sumOf { it.searched * 5 + it.read * 20 + it.dailySet * 10 }
            result.add(dateStr to dayPoints)
        }
        return result
    }

    /** 完成率：最近 N 次运行中成功率 */
    fun completionRate(limit: Int = 10): Float {
        val records = loadRecords().takeLast(limit)
        if (records.isEmpty()) return 0f
        return records.count { it.success }.toFloat() / records.size
    }

    /** 平均耗时（秒） */
    fun averageDurationSec(): Long {
        val records = loadRecords().filter { it.durationMs > 0 }
        if (records.isEmpty()) return 0
        return records.map { it.durationMs }.average().toLong() / 1000
    }

    /** 累计总积分（估算） */
    fun totalEstimatedPoints(): Int {
        return loadRecords().filter { it.success }.sumOf {
            it.searched * 5 + it.read * 20 + it.dailySet * 10
        }
    }

    // ---- 断点续跑进度（progress.json） ----

    override fun load(): RunProgress {
        val p = runCatching {
            json.decodeFromString<RunProgress>(progressFile.readText())
        }.getOrNull() ?: return RunProgress()
        // 跨天即失效，避免昨天的进度影响今天
        return if (p.date == java.time.LocalDate.now().toString()) p else RunProgress()
    }

    override fun save(p: RunProgress) {
        runCatching {
            dir.mkdirs()
            progressFile.writeText(json.encodeToString(RunProgress.serializer(), p))
        }
    }

    override fun clear() {
        runCatching { progressFile.delete() }
    }
}