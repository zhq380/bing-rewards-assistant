package com.ripple.script.rewards

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 页面类型枚举。
 *
 * 既是规则引擎（Phase 1）的输出，也是预留的 B 路线（端侧 TFLite 图像分类）
 * [ScreenshotClassifier] 的目标输出——两套识别都收敛到同一个枚举，上层无感。
 */
enum class PageType {
    REWARDS_HOME,   // 积分页（今日积分 / 搜索以赚取 / 阅读以赚取 卡片）
    DAILY_ACTIVITY, // 每日活动卡片区（赚取 N 积分 / +10 蓝按钮）
    ARTICLE,        // 文章阅读页（长正文、无积分卡片标记）
    SEARCH_RESULT,  // 搜索结果页（顶部地址栏 / 搜索输入框）
    AD_PAGE,        // 广告页（广告卡片成片、CTA 按钮密集）
    UNKNOWN         // 未能判定
}

/**
 * B 路线预留接口：端侧截图图像分类器。
 *
 * 当前实现 [KnnScreenshotClassifier]：z-score 欧氏 kNN，样本库来自 PC 端
 * train/train_proto.py 采集的真实手机截图。接入策略为「规则引擎优先，
 * 模型仅在规则 UNKNOWN 时兜底（且不进坏 rewards_home）」。
 */
interface ScreenshotClassifier {
    /** 模型是否已加载可用 */
    fun isAvailable(): Boolean

    /** 对整屏截图分类，返回页面类型；低置信度返回 null 交由规则引擎判定 */
    suspend fun classify(bitmap: Bitmap): PageType?

    /**
     * 带强门槛的分类：通过门槛返回 (页面类型, 置信分)，否则 null。
     * 只有「领域内且类间边界清晰」时才开口，供规则引擎 UNKNOWN 时兜底。
     */
    suspend fun classifyConfident(bitmap: Bitmap): Pair<PageType, Float>? =
        classify(bitmap)?.let { it to 1f }
}

/** Phase 2 前的空实现：等截图样本集训练完成后替换为真实 TFLite 分类器 */
class NoopScreenshotClassifier : ScreenshotClassifier {
    override fun isAvailable(): Boolean = false
    override suspend fun classify(bitmap: Bitmap): PageType? = null
}

/**
 * 一次"看屏幕"得到的结构化识别结果。
 *
 * 所有动作（签到/搜索/阅读/每日活动/状态扫描）统一只依赖本快照，
 * 不再各自散落 OCR + 无障碍双通道逻辑。
 */
data class PageSnapshot(
    val pageType: PageType,
    val allText: String,               // OCR 全文（去行分割后的纯文本）
    val lines: List<OcrBlock>,         // OCR 行（含坐标，用于点按钮/入口）
    val adZones: List<Rect>,           // 广告区域矩形（点击前必须检查拦截）
    val viaMlModel: Boolean,           // 页面类型是否来自图像分类模型（Phase 2）
    val screenW: Int = 0,              // 当前屏幕宽度
    val screenH: Int = 0               // 当前屏幕高度
)

/**
 * 统一页面识别核心（Phase 1：规则引擎，收敛散落的 OCR 判定）。
 *
 * 职责：
 * 1. 截屏 → OCR 全文 + 行（含坐标）；
 * 2. 页面类型判定（规则引擎，预留 [ScreenshotClassifier] 图像分类作为增强输入）；
 * 3. 广告区域提取（基于 OCR 文本行标记，供上层点击拦截）。
 *
 * 全部逻辑与无障碍树解耦——分身/WebView 不可读场景下是唯一可靠通道；
 * 主应用场景可把无障碍文本作为辅助输入提升判定置信度。
 */
class PageStateClassifier(
    private val mlClassifier: ScreenshotClassifier = NoopScreenshotClassifier(),
    private val screenHeight: Int = 0,
    private val screenWidth: Int = 0
) {
    /** 主入口：截屏（+可选无障碍文本辅助）→ 结构化的 [PageSnapshot] */
    suspend fun classify(bitmap: Bitmap, accessibilityTexts: List<String> = emptyList()): PageSnapshot {
        val allText = runCatching { ScreenOcr.recognize(bitmap) }.getOrDefault("")
        val lines = runCatching { ScreenOcr.recognizeLines(bitmap) }.getOrDefault(emptyList())

        // 规则引擎优先（OCR 文本判据可靠）；仅当规则 UNKNOWN 时让 kNN 兜底，
        // 且 kNN 不输出 rewards_home —— 防止图像模型把非积分页误报为积分页。
        val ruleType = ruleBasedType(allText, lines, accessibilityTexts)
        var type = ruleType
        var viaMl = false
        if (ruleType == PageType.UNKNOWN && mlClassifier.isAvailable()) {
            val ml = runCatching { mlClassifier.classifyConfident(bitmap) }.getOrNull()
            if (ml != null && ml.first != PageType.REWARDS_HOME) {
                type = ml.first
                viaMl = true
            }
        }

        val adZones = collectAdZonesFromLines(lines, accessibilityTexts)

        // 屏幕尺寸：优先构造传入值，否则从 OCR 行范围推断
        val hForSnap = screenHeight.takeIf { it > 0 } ?: (lines.maxOfOrNull { it.bounds.bottom } ?: 0)
        val wForSnap = screenWidth.takeIf { it > 0 } ?: (lines.maxOfOrNull { it.bounds.right } ?: 0)

        return PageSnapshot(
            pageType = type,
            allText = allText,
            lines = lines,
            adZones = adZones,
            viaMlModel = viaMl,
            screenW = wForSnap,
            screenH = hForSnap
        )
    }

    /** 纯 OCR 全文识别（兼容旧调用，内部仍走同一引擎） */
    suspend fun recognizeText(bitmap: Bitmap): String =
        runCatching { ScreenOcr.recognize(bitmap) }.getOrDefault("")

    /** OCR 行识别（含坐标） */
    suspend fun recognizeLines(bitmap: Bitmap): List<OcrBlock> =
        runCatching { ScreenOcr.recognizeLines(bitmap) }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // 页面类型规则判定
    // ------------------------------------------------------------------

    private fun ruleBasedType(
        allText: String,
        lines: List<OcrBlock>,
        accessibilityTexts: List<String>
    ): PageType {
        val t = accessibilityTexts.joinToString(" ") + " " + allText

        // 1) 广告页判定优先（广告标记集中出现）
        val adMarkCount = lines.count { isAdMarkerLine(it.text) } +
            accessibilityTexts.count { RewardsIntelligence.isAdMarkerText(it.trim()) }
        if (adMarkCount >= 2) return PageType.AD_PAGE

        // 2) 积分页（Rewards Home）：今日积分 / 搜索以赚取 / 阅读以赚取 / 签入
        val hasRewardsHome =
            t.contains("今日积分") || t.contains("签入") || t.contains("签到") ||
            (t.contains("搜索以赚取") && t.contains("阅读以赚取"))
        if (hasRewardsHome) return PageType.REWARDS_HOME

        // 3) 每日活动区：每日活动/今日活动 标题 + 赚取按钮
        val hasDaily =
            (t.contains("每日活动") || t.contains("今日活动")) &&
            (t.contains("赚取") || t.contains("+10"))
        if (hasDaily) return PageType.DAILY_ACTIVITY

        // 4) 搜索结果页：顶部地址栏特征（含 bing.com/q= 或 搜索框文案）
        if (t.contains("bing.com") && (t.contains("q=") || t.contains("搜索"))) {
            return PageType.SEARCH_RESULT
        }

        // 5) 文章页：正文较长且无积分标记
        val longLines = lines.count { it.text.length >= 20 }
        val hasRewardMarker = t.contains("赚取") || t.contains("积分") ||
            t.contains("搜索以赚取") || t.contains("阅读以赚取")
        if (longLines >= 3 && !hasRewardMarker) return PageType.ARTICLE

        return PageType.UNKNOWN
    }

    // ------------------------------------------------------------------
    // 广告区域提取（基于 OCR 行文本标记）
    // ------------------------------------------------------------------

    /** 单行是否为广告标记行（短标记文本，防误伤正文长行） */
    fun isAdMarkerLine(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty() || s.length > 12) return false
        return RewardsIntelligence.AD_MARKER_TEXTS.any { s == it || s.contains(it) } ||
            RewardsIntelligence.isAdMarkerText(s)
    }

    /**
     * 从 OCR 行构建广告区域：广告标记行向上延伸一卡（约 4% 屏高）、
     * 向下延伸约半卡（约 2% 屏高），覆盖整张广告卡片点击禁区。
     */
    private fun collectAdZonesFromLines(
        lines: List<OcrBlock>,
        accessibilityTexts: List<String>
    ): List<Rect> {
        val zones = mutableListOf<Rect>()
        val h = if (screenHeight > 0) screenHeight else (lines.maxOfOrNull { it.bounds.bottom } ?: 0)
        for (line in lines) {
            if (isAdMarkerLine(line.text)) {
                val r = line.bounds
                val up = (r.top - h * 0.04f).toInt().coerceAtLeast(0)
                val down = r.bottom + (h * 0.02f).toInt()
                zones.add(Rect(r.left, up, r.right, down))
            }
        }
        return zones
    }

    /** 点是否落在任一广告区 */
    fun isPointInAdZone(x: Int, y: Int, snap: PageSnapshot): Boolean =
        snap.adZones.any { it.contains(x, y) }

    // ------------------------------------------------------------------
    // 常见判定快捷方法（上层动作统一询问，不再各自实现）
    // ------------------------------------------------------------------

    fun isRewardsHome(snap: PageSnapshot): Boolean =
        snap.pageType == PageType.REWARDS_HOME

    fun isDailyActivity(snap: PageSnapshot): Boolean =
        snap.pageType == PageType.DAILY_ACTIVITY

    fun isArticle(snap: PageSnapshot): Boolean =
        snap.pageType == PageType.ARTICLE

    fun isSearchResult(snap: PageSnapshot): Boolean =
        snap.pageType == PageType.SEARCH_RESULT

    fun isAdPage(snap: PageSnapshot): Boolean =
        snap.pageType == PageType.AD_PAGE

    /** 判定积分页某任务卡片（搜索以赚取/阅读以赚取）今日是否已完成；未扫到返回 null */
    fun taskCardCompleted(snap: PageSnapshot, title: String): Boolean? {
        val idx = snap.allText.indexOf(title)
        if (idx < 0) return null
        val text = snap.allText
        var end = minOf(idx + CARD_SNIPPET_LEN, text.length)
        if (end < text.length) {
            // 截断处回退到最近的语义断点，避免把「(需要 30 积分)」切碎导致漏判缺口
            val from = maxOf(idx + 1, end - 24)
            for (i in end - 1 downTo from) {
                if (text[i] in SNIPPET_BREAK_CHARS) { end = i + 1; break }
            }
        }
        return RewardsIntelligence.isTaskCompleted(text.substring(idx, end))
    }

    /** 积分页是否显示「真棒 / 120/120」（全部完成） */
    fun isAllDone(snap: PageSnapshot): Boolean =
        snap.allText.contains("真棒") || snap.allText.contains("120/120")

    /** 从 OCR 行定位含 [keyword] 的文本行，返回该行（含坐标）；找不到返回 null */
    fun findLineByKeyword(lines: List<OcrBlock>, keyword: String): OcrBlock? =
        lines.firstOrNull { it.text.contains(keyword) }

    companion object {
        /** 任务卡片片段长度：需覆盖「搜索以赚取, , 已赚取 6 积分(需要 30 积分)」这类长卡片 */
        const val CARD_SNIPPET_LEN = 160
        /** 片段截断时回退锚点：优先在这些字符后切断，避免切碎缺口文本 */
        val SNIPPET_BREAK_CHARS = charArrayOf(')', '，', ',', '。', '、', ' ')
    }
}