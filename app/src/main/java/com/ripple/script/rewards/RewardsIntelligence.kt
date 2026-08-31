package com.ripple.script.rewards

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 纯智能识别：广告判定 / 轮播页码 / CTA 按钮 / 标题去重 / 子树文本。
 * 来源于原 AccessibilityStepExecutor 的 companion 逻辑，仅依赖 AccessibilityNodeInfo，便于单测。
 */
object RewardsIntelligence {

    fun normalize(s: String): String =
        s.replace(Regex("[\\s\\p{P}\\p{S}…]"), "").lowercase()

    fun isCarouselIndicator(text: String): Boolean {
        val s = text.trim()
        if (s.length > 14) return false
        val p1 = Regex("^\\d{1,2}\\s*/\\s*\\d{1,2}$")            // 1/5
        val p2 = Regex("(?i)^page\\s*\\d{1,2}\\s*of\\s*\\d{1,2}$") // page 2 of 6
        val p3 = Regex("^第\\d{1,2}页.*共\\d{1,2}页$")             // 第2页 共6页
        return p1.matches(s) || p2.matches(s) || p3.matches(s)
    }

    val AD_MARKER_TEXTS = setOf(
        "广告选项", "广告", "赞助内容", "推广", "赞助", "已广告", "广告内容",
        "付费推广", "商业推广", "为您推荐", "相关推荐", "资讯推荐", "热门推荐",
        "Sponsored", "sponsored", "Promoted", "promoted", "AD", "Ad", "ad",
        "Advertisement", "advertisement", "Sponsored Content", "Paid Content",
        "Promoted by", "Sponsored by", "Presented by"
    )

    fun isAdMarkerText(text: String): Boolean = text.trim() in AD_MARKER_TEXTS

    private val AD_MARKER_ID_SUBSTRINGS = listOf(
        "ad_marker", "ad_label", "sponsor", "ad_tag", "promo", "native_ad",
        "ad_container", "ad_card", "ad_view", "banner_ad", "feed_ad"
    )
    private val AD_CLASS_NAME_SUBSTRINGS = listOf(
        "adview", "adcontainer", "nativead", "adcard", "bannerad",
        "feedad", "adlayout", "aditem", "adframe", "gdtad", "baiduad",
        "toutiaoad", "ksad"
    )
    private val AD_CTA_TEXTS = setOf(
        "立即下载", "查看详情", "了解更多", "立即安装", "立即领取", "点击下载",
        "免费下载", "去下载", "去安装", "立即抢购", "马上抢", "免费领取",
        "下载应用", "打开应用", "前往查看", "查看商品", "去购买", "立即购买",
        "下载APP", "立即体验", "点击查看", "去逛逛", "领券购买", "进入店铺",
        "Download", "Install", "Learn more", "Learn More", "Open app", "Open App",
        "Get it", "Get the app", "Shop now", "Shop Now", "Sign up", "Try it"
    )

    fun isAdCtaText(text: String): Boolean {
        val s = text.trim()
        if (s in AD_CTA_TEXTS) return true
        // 仅对确定的长词做子串匹配，避免误伤含 "ad"/"download" 的普通正文
        return AD_CTA_TEXTS.any { it.length > 6 && s.contains(it) }
    }

    fun hasAdClassName(node: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < 100) {
            val n = queue.removeFirst(); visited++
            val cls = n.className?.toString().orEmpty()
            if (AD_CLASS_NAME_SUBSTRINGS.any { cls.contains(it, ignoreCase = true) }) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    fun hasAdCtaButton(node: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < 100) {
            val n = queue.removeFirst(); visited++
            val txt = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            if (isAdCtaText(txt) || isAdCtaText(desc)) return true
            if (n.isClickable && (txt.isNotEmpty() || desc.isNotEmpty())) {
                if (AD_CTA_TEXTS.any { txt.contains(it) || desc.contains(it) }) return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur = node
        repeat(6) {
            val p = cur.parent ?: return cur
            if (p.isClickable) return p
            cur = p
        }
        return node
    }

    fun subtreeText(node: AccessibilityNodeInfo, depth: Int = 5): String {
        val sb = StringBuilder(256)
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            if (d > 0) for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d - 1) }
        }
        walk(node, depth)
        return sb.toString()
    }

    /** 收集长度达标的文本节点 */
    fun collectTextNodes(root: AccessibilityNodeInfo, minLen: Int): List<Pair<String, AccessibilityNodeInfo>> {
        val out = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val n = queue.removeFirst(); visited++
            val txt = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val best = if (txt.length >= desc.length) txt else desc
            if (best.length >= minLen) out.add(best to n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /**
     * 收集广告区域矩形。isAdMarker 回调由底层注入（含 resource-id/className 判定）。
     */
    fun collectAdZones(root: AccessibilityNodeInfo, screenH: Int,
                       isAdMarker: (AccessibilityNodeInfo) -> Boolean): List<Rect> {
        val zones = mutableListOf<Rect>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val n = queue.removeFirst(); visited++
            val txt = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val rid = n.viewIdResourceName?.toString().orEmpty()
            val cls = n.className?.toString().orEmpty()
            val marked = isAdMarkerText(txt) || isAdMarkerText(desc) ||
                AD_MARKER_ID_SUBSTRINGS.any { rid.contains(it, ignoreCase = true) } ||
                AD_CLASS_NAME_SUBSTRINGS.any { cls.contains(it, ignoreCase = true) } ||
                isCarouselIndicator(txt) || isCarouselIndicator(desc) ||
                isAdMarker(n)
            if (marked) {
                val markerRect = Rect()
                n.getBoundsInScreen(markerRect)
                if (!markerRect.isEmpty) {
                    val anc = findClickableAncestor(n)
                    val ar = Rect()
                    anc.getBoundsInScreen(ar)
                    if (!ar.isEmpty && ar.height() < screenH / 2 && ar.width() < screenH) zones.add(ar)
                    else zones.add(Rect(
                        markerRect.left, (markerRect.top - 300).coerceAtLeast(0),
                        markerRect.right, markerRect.bottom + 120
                    ))
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return zones
    }

    /** 关键词命中：含中文用子串，纯 ASCII 用词边界（避免误伤 ad/read） */
    fun hitsKeyword(fullText: String, words: List<String>): Boolean {
        for (w in words) {
            if (w.isEmpty()) continue
            if (w.any { it.code > 127 }) { if (fullText.contains(w)) return true }
            else if (Regex("(?i)\\b${Regex.escape(w)}\\b").containsMatchIn(fullText)) return true
        }
        return false
    }

    /**
     * 未登录（会话过期）时必应首页/积分页出现的登录入口文案。
     * 复用 [hitsKeyword]：中文走子串、纯 ASCII 走词边界，避免误伤正文里含 "sign in" 的新闻标题。
     */
    val SIGN_IN_ENTRY_WORDS = listOf("登录", "Sign in", "Sign up", "Signin", "注册")

    /**
     * 已登录（会话有效）的特征文案：积分页专属卡片文案或账户入口。
     * 命中任一即认定会话有效——这些文案只有登录态下才会渲染。
     */
    val SIGNED_IN_MARKERS = listOf(
        "今日积分", "搜索以赚取", "阅读以赚取", "每日奖励", "我的 Microsoft",
        "Microsoft 账户", "Microsoft account", "Microsoft Rewards"
    )

    /**
     * 是否为「登录入口」文本：短文本（≤24 字，按钮/链接级）且命中登录标记词。
     *
     * 长度门槛用于排除正文标题（如「如何登录 Microsoft 账户」这类长句不是登录入口）。
     */
    fun isSignInEntryText(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty() || s.length > 24) return false
        return hitsKeyword(s, SIGN_IN_ENTRY_WORDS)
    }

    /** 文本集合中是否命中已登录特征（积分卡片 / 账户入口） */
    fun hasSignedInMarker(texts: List<String>): Boolean =
        texts.any { t -> SIGNED_IN_MARKERS.any { m -> t.contains(m, ignoreCase = true) } }

    /** 缺口标记：卡片仍显示「需要 N 积分」即今日未完成 */
    private val GAP_WORDS = listOf("需要", "剩余", "还需", "need", "remaining")

    /**
     * 判定微软 Rewards 单个任务卡片今日是否已完成（真机实测文案，1272x2772 / 中文界面）：
     *  - 进行中： 「搜索以赚取, , 已赚取 3 积分(需要 60 积分)」→ 含「需要/剩余」缺口标记
     *  - 已完成： 「阅读以赚取, 已赚取的 30 积分」→ 含「已赚取/已完成」且无缺口标记
     *
     * 判定顺序（逐步收紧，降低误判）：
     * 1) 缺口标记优先：只要出现「需要/剩余/还需」一律判未完成；
     * 2) 完成式文案：命中「已赚 / 已完成 / earned / completed」；
     * 3) 防误杀：能解析出「已赚取 N 积分」且 N ≤ 0 时，说明尚未计分，不判完成。
     */
    fun isTaskCompleted(cardText: String): Boolean {
        val s = cardText.trim()
        if (s.isEmpty()) return false
        if (GAP_WORDS.any { s.contains(it, ignoreCase = true) }) return false
        val hasEarn = s.contains("已赚") || s.contains("已完成") ||
            s.contains("earned", ignoreCase = true) || s.contains("completed", ignoreCase = true)
        if (!hasEarn) return false
        val earned = Regex("已赚取\\s*的?\\s*(\\d+)\\s*积分").find(s)?.groupValues?.get(1)?.toIntOrNull()
        return earned == null || earned > 0
    }

    /** 每项（搜索/阅读）单次的积分值，用于自动化模式反推剩余次数 */
    const val POINTS_PER_OP = 3

    /**
     * 解析任务卡片进度文本，返回 (已赚积分, 需要积分)。
     * 例：「阅读以赚取, , 已赚取 6 积分(需要 30 积分)」→ (6, 30)
     *     「搜索以赚取, 已赚取的 60 积分」→ (60, 60)（无缺口=已完成）
     * 无法解析出「已赚取 N 积分」时返回 null。
     */
    fun parseCardProgress(cardText: String): Pair<Int, Int>? {
        val s = cardText.trim()
        if (s.isEmpty()) return null
        val earned = Regex("已赚取\\s*的?\\s*(\\d+)\\s*积分").find(s)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val needed = Regex("[（(]?需要\\s*的?\\s*(\\d+)\\s*积分").find(s)?.groupValues?.get(1)?.toIntOrNull()
        // 无「需要」缺口 → 视为已达标
        return earned to (needed ?: earned)
    }

    /** 按每项 [pointsPerOp] 积分计算还需执行的次数（向上取整，向下限 0） */
    fun remainingOps(earned: Int, needed: Int, pointsPerOp: Int = POINTS_PER_OP): Int {
        val gap = needed - earned
        if (gap <= 0) return 0
        return (gap + pointsPerOp - 1) / pointsPerOp
    }

    /** 从卡片文本直接得到剩余执行次数；解析失败返回 null（交由调用方回退手动次数） */
    fun remainingOpsFromCard(cardText: String, pointsPerOp: Int = POINTS_PER_OP): Int? {
        val (earned, needed) = parseCardProgress(cardText) ?: return null
        return remainingOps(earned, needed, pointsPerOp)
    }
}