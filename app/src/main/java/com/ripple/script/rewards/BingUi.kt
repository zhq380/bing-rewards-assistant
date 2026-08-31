package com.ripple.script.rewards

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.ripple.script.service.AccessibilityStepExecutorCompat
import com.ripple.script.service.AutoAccessibilityService
import com.ripple.script.util.AppLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 对 Bing 的无障碍操作抽象：RewardsAgent 只依赖本接口，可 mock/fake 单测。
 * 真实现 [AccessibilityBingUi] 封装 [AutoAccessibilityService] + [AccessibilityStepExecutorCompat]。
 */
interface BingUi {
    /** 当前屏幕全部可见文本 */
    suspend fun screenTexts(): List<String>
    /** 点击包含该文本的节点；找不到返回 false */
    suspend fun clickText(text: String): Boolean
    /** 点击 resource-id 包含该子串的节点 */
    suspend fun clickResourceId(id: String): Boolean
    /** 点击指定屏幕区域中心 */
    suspend fun clickBounds(bounds: Rect): Boolean
    /** 按屏幕绝对坐标点击（用于原生层已知布局；Bing 底部导航/入口坐标已标定） */
    suspend fun click(x: Int, y: Int): Boolean
    /** 向上滚动一屏（用于把折叠在底部的入口滚出可见） */
    suspend fun scrollUp(): Boolean
    /** 向下滚动一屏（用于回到页面顶部区域） */
    suspend fun scrollDown(): Boolean
    /** 在焦点输入框写入文本 */
    suspend fun typeText(s: String): Boolean
    /** 提交 IME（回车搜索） */
    suspend fun pressEnter(): Boolean
    /** 输入并提交搜索 */
    suspend fun typeAndSubmit(s: String): Boolean
    /** 系统返回 */
    suspend fun back(): Boolean
    /** 在给定时间内滚动查找文本 */
    suspend fun scrollToText(text: String, timeoutMs: Long): Boolean
    /** 在给定时间内等待文本出现 */
    suspend fun waitForText(text: String, timeoutMs: Long): Boolean
    /**
     * 点击文本/desc 命中且完整位于可视区域内的可点击节点（避免点到屏幕外被裁剪的 WebView 节点）。
     * 命中但不可见时返回 false，交由上层滚动后重试。
     */
    suspend fun clickTextVisible(text: String): Boolean
    /**
     * 点击文本/desc 命中的任意节点中心（不要求 isClickable）。
     * 部分原生按钮（如 Rewards「签入」）文本节点自身 clickable=false，点击其中心同样生效。
     */
    suspend fun clickTextAny(text: String): Boolean
    /** 当前前台包名 */
    suspend fun foregroundPackage(): String
    /** 将 Bing 应用带到前台；成功返回 true */
    suspend fun launch(): Boolean
    /** 截取当前物理屏（供 OCR 读取分身必应内容）；失败返回 null */
    suspend fun captureScreen(): Bitmap?
    /** 当前屏幕逻辑宽高（px，供 OCR 行可视性校验） */
    suspend fun visibleWidth(): Int
    suspend fun visibleHeight(): Int
    /** 找到 content-desc 包含 [desc] 的可点击节点，返回其 bounds；找不到返回 null */
    suspend fun findDesc(desc: String): Rect?
    /** 找到 text 包含 [text] 的节点（不要求 clickable），返回其 bounds；找不到返回 null */
    suspend fun findText(text: String): Rect?
    /** 找到所有 text 包含 [keyword] 的节点，返回 (text, bounds) 列表；按屏幕从上到下、从左到右排序 */
    suspend fun findAllText(keyword: String): List<Pair<String, Rect>>
    /** 找到所有 content-desc 包含 [desc] 的可点击节点，返回 bounds 列表 */
    suspend fun findAllDesc(desc: String): List<Rect>
    /** 找到所有 content-desc 包含 [desc] 的节点，返回 content-desc 文本 + bounds */
    suspend fun findAllDescWithText(desc: String): List<Pair<String, Rect>>
    /** 点击确认弹窗（奖励/通知提示家常） */
    fun dismissDialogs()
    /**
     * 处理 应用分身功能选择器（MultiAppResolverActivity）：点目标实例（主空间/分身）。
     * 阅读/唤起链接时隐式 Intent 会弹选择器，必须在脚本内自动选定，否则永远停在选择器上。
     */
    suspend fun resolveChooser(): Boolean

    /**
     * 从必应窗口提取「新闻标题」候选（新闻流/信息流页的正文入口），返回 (标题文本, bounds)。
     * 过滤掉广告/导航/操作项（赞、分享、查看详细信息、搜索、tab 等），自上而下排序。
     */
    suspend fun newsTitleCandidates(): List<Pair<String, Rect>>
}

/**
 * 基于 [AutoAccessibilityService] 的 [BingUi] 真实现。
 * 文本匹配走语义 + 资源 id 双通道；scrollToText 用上滑循环 + screenTexts 判定。
 */
class AccessibilityBingUi(
    private val svc: AutoAccessibilityService,
    private val targetSerial: Long = BingInstanceResolver.MAIN_SERIAL,
    private val autoLaunch: Boolean = true
) : BingUi {

    internal val service: AutoAccessibilityService get() = svc

    override suspend fun screenTexts(): List<String> = withContext(Dispatchers.Default) {
        // 优先读取必应窗口的文本（悬浮胶囊会劫持 rootInActiveWindow，导致误读到脚本自身文案）
        val root = bingWindowRoot() ?: return@withContext emptyList()
        buildList {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var visited = 0
            while (q.isNotEmpty() && visited < 15000) {
                val n = q.removeFirst(); visited++
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrBlank()) add(t)
                val d = n.contentDescription?.toString()?.trim()
                if (!d.isNullOrBlank()) add(d)
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
        }
    }

    override suspend fun clickText(text: String): Boolean = withContext(Dispatchers.Default) {
        // 只在必应窗口内找目标节点：rootInActiveWindow 可能被悬浮胶囊劫持，
        // 若不限制包名会点到脚本应用自身的节点（如自家「应用」文案），导致点击落入错误应用
        val root = bingWindowRoot() ?: return@withContext false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 15000) {
            val n = q.removeFirst(); visited++
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val pkg = n.packageName?.toString().orEmpty()
            if (pkg.contains("bing") && n.isClickable && (t.contains(text) || d.contains(text))) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    android.util.Log.i("RippleReward", "clickText「$text」→node text=「$t」desc=「$d」bounds=$r")
                    svc.dispatchClick(r.centerX(), r.centerY())
                    return@withContext true
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        false
    }

    override suspend fun clickTextVisible(text: String): Boolean = withContext(Dispatchers.Default) {
        val m = svc.resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(bingWindowRoot() ?: return@withContext false)
        var visited = 0
        while (q.isNotEmpty() && visited < 15000) {
            val n = q.removeFirst(); visited++
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (n.isClickable && (t.contains(text) || d.contains(text))) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && r.top >= 0 && r.left >= 0 && r.bottom <= h && r.right <= w && r.height() > 20) {
                    android.util.Log.i("RippleReward", "clickTextVisible「$text」→$r")
                    svc.dispatchClick(r.centerX(), r.centerY())
                    return@withContext true
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        false
    }

    override suspend fun clickTextAny(text: String): Boolean = withContext(Dispatchers.Default) {
        val root = bingWindowRoot() ?: return@withContext false
        val m = svc.resources.displayMetrics
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 15000) {
            val n = q.removeFirst(); visited++
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && (t == text || t.contains(text)) || d.isNotEmpty() && d.contains(text)) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && r.width() > 0 && r.height() > 0 &&
                    r.top >= 0 && r.left >= 0 && r.bottom <= m.heightPixels && r.right <= m.widthPixels
                ) {
                    android.util.Log.i("RippleReward", "clickTextAny「$text」→ node text=`$t` desc=`${d.take(40)}` bounds=$r")
                    svc.dispatchClick(r.centerX(), r.centerY())
                    return@withContext true
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        false
    }

    /**
     * 定位必应应用窗口的 root（绕过脚本自身悬浮胶囊对 rootInActiveWindow 的劫持）。
     * 找不到必应窗口时回退到活动窗口（且仅当活动窗口是必应时使用）。
     */
    private fun bingWindowRoot(): AccessibilityNodeInfo? {
        val ws = runCatching { svc.windows }.getOrNull()
        if (!ws.isNullOrEmpty()) {
            for (win in ws) {
                if (win.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = win.root ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (pkg == BING_PACKAGE) return root
            }
        }
        val active = svc.rootInActiveWindow
        return active?.takeIf { it.packageName?.toString() == BING_PACKAGE }
    }

    override suspend fun clickResourceId(id: String): Boolean = withContext(Dispatchers.Default) {
        val root = bingWindowRoot() ?: return@withContext false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 15000) {
            val n = q.removeFirst(); visited++
            val pkg = n.packageName?.toString().orEmpty()
            if (pkg.contains("bing") && n.isClickable && n.viewIdResourceName?.toString()?.contains(id, true) == true) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty) { svc.dispatchClick(r.centerX(), r.centerY()); return@withContext true }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        false
    }

    override suspend fun findText(text: String): Rect? = withContext(Dispatchers.Default) {
        val root = bingWindowRoot() ?: return@withContext null
        val m = svc.resources.displayMetrics
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 15000) {
            val n = q.removeFirst(); visited++
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            // 优先匹配 text，其次 content-desc（部分 WebView 内容 desc 里也有）
            val match = when {
                t.isNotEmpty() && (t == text || t.contains(text)) -> t
                d.isNotEmpty() && d.contains(text) && !d.contains("已赚取") -> d
                else -> null
            }
            if (match != null) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && r.width() > 0 && r.height() > 0 &&
                    r.top >= 0 && r.left >= 0 &&
                    r.bottom <= m.heightPixels && r.right <= m.widthPixels
                ) {
                    android.util.Log.i("RippleReward", "findText「$text」→ text=`$t` desc=`${d.take(40)}` bounds=$r")
                    return@withContext r
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        null
    }

    override suspend fun findAllText(keyword: String): List<Pair<String, Rect>> = withContext(Dispatchers.Default) {
        val root = bingWindowRoot() ?: return@withContext emptyList()
        val m = svc.resources.displayMetrics
        val result = mutableListOf<Pair<String, Rect>>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 15000) {
            val n = q.removeFirst(); visited++
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val match = when {
                t.isNotEmpty() && (t == keyword || t.contains(keyword)) -> t
                d.isNotEmpty() && d.contains(keyword) && !d.contains("已赚取") -> d
                else -> null
            }
            if (match != null) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && r.width() > 0 && r.height() > 0 &&
                    r.top >= 0 && r.left >= 0 &&
                    r.bottom <= m.heightPixels && r.right <= m.widthPixels
                ) {
                    result.add(match to Rect(r))
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        // 按从上到下（Y）、再从左到右（X）排序
        result.sortBy { (_, r) -> r.top * 10000 + r.left }
        android.util.Log.i("RippleReward", "findAllText「$keyword」→ ${result.size} 个: ${result.map { (t, r) -> "`$t`@[$r]" }.joinToString()}")
        result
    }

    override suspend fun findDesc(desc: String): Rect? = withContext(Dispatchers.Default) {
        val root = svc.rootInActiveWindow ?: return@withContext null
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 15000) {
            val n = q.removeFirst(); visited++
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (d.isNotEmpty() && d.contains(desc) && !d.contains("已赚取")) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty) return@withContext r
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        null
    }

    override suspend fun findAllDesc(desc: String): List<Rect> = withContext(Dispatchers.Default) {
        findAllDescWithText(desc).map { it.second }
    }

    override suspend fun findAllDescWithText(desc: String): List<Pair<String, Rect>> = withContext(Dispatchers.Default) {
        val result = mutableListOf<Pair<String, Rect>>()
        val root = bingWindowRoot() ?: return@withContext result
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 15000) {
            val n = q.removeFirst(); visited++
            // 活动卡信息可能在 content-desc 也可能在 text（screenTexts 两者都收故能读到），
            // 这里统一取其中非空的一个，避免漏掉只存在 text 的节点
            val d = when {
                n.contentDescription?.isNotBlank() == true -> n.contentDescription.toString().trim()
                n.text?.isNotBlank() == true -> n.text.toString().trim()
                else -> ""
            }
            val pkg = n.packageName?.toString().orEmpty()
            // WebView 内容节点包名不一定是 com.microsoft.bing（screenTexts 能读到但本函数
            // 之前按 pkg 过滤导致每日活动卡 desc 匹配不到）→ 只排除脚本自身（悬浮胶囊）即可
            if (pkg != PACKAGE_SELF && d.isNotEmpty() && d.contains(desc) && !d.contains("已赚取")) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty) result.add(d to r)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        result
    }

    override suspend fun clickBounds(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        svc.dispatchClick(bounds.centerX(), bounds.centerY())
        return true
    }

    override suspend fun click(x: Int, y: Int): Boolean {
        // 只在必应前台才执行点击，绝不操作脚本自身页面
        if (!foregroundPackage().contains("bing")) {
            android.util.Log.i("RippleReward", "skip click: not in bing (pkg=${foregroundPackage()})")
            return false
        }
        svc.dispatchClick(x, y)
        return true
    }

    override suspend fun scrollUp(): Boolean {
        // 只在必应前台才滚动，避免滑动脚本自身页面导致迷路
        if (!foregroundPackage().contains("bing")) {
            android.util.Log.i("RippleReward", "skip scrollUp: not in bing (pkg=${foregroundPackage()})")
            return false
        }
        val m = svc.resources.displayMetrics
        svc.dispatchSwipe(
            m.widthPixels / 2, (m.heightPixels * 0.75f).toInt(),
            m.widthPixels / 2, (m.heightPixels * 0.4f).toInt(), 400
        )
        return true
    }

    override suspend fun scrollDown(): Boolean {
        // 只在必应前台才滚动，避免滑动脚本自身页面导致迷路
        if (!foregroundPackage().contains("bing")) {
            android.util.Log.i("RippleReward", "skip scrollDown: not in bing (pkg=${foregroundPackage()})")
            return false
        }
        val m = svc.resources.displayMetrics
        svc.dispatchSwipe(
            m.widthPixels / 2, (m.heightPixels * 0.4f).toInt(),
            m.widthPixels / 2, (m.heightPixels * 0.75f).toInt(), 400
        )
        return true
    }

    override suspend fun typeText(s: String): Boolean =
        AccessibilityStepExecutorCompat.inputText(svc, s)

    override suspend fun pressEnter(): Boolean =
        AccessibilityStepExecutorCompat.submitIme(svc)

    override suspend fun typeAndSubmit(s: String): Boolean {
        val ok = AccessibilityStepExecutorCompat.inputText(svc, s)
        if (ok) AccessibilityStepExecutorCompat.submitIme(svc)
        return ok
    }

    override suspend fun back(): Boolean {
        // 只在必应前台才允许返回：back 越界会退出必应回到桌面/脚本应用，导致任务卡死
        if (!foregroundPackage().contains("bing")) {
            android.util.Log.i("RippleReward", "skip back: not in bing (pkg=${foregroundPackage()})")
            return false
        }
        return AccessibilityStepExecutorCompat.goBack(svc)
    }

    override suspend fun scrollToText(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (screenTexts().any { it.contains(text) }) return true
            AccessibilityStepExecutorCompat.scrollUp(svc, 0.75f)
            delay(700)
        }
        return screenTexts().any { it.contains(text) }
    }

    override suspend fun waitForText(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (screenTexts().any { it.contains(text) }) return true
            delay(400)
        }
        return false
    }

    override suspend fun foregroundPackage(): String {
        // 悬浮胶囊（本应用 com.ripple.script）可能抢占 rootInActiveWindow，
        // 此时需扫描窗口列表找到其下真实的 APP 窗口（必应/分身选择器等）。
        val active = svc.rootInActiveWindow?.packageName?.toString() ?: ""
        if (active.contains("bing") || active.contains("multiapp")) return active
        if (active.isEmpty() || active == PACKAGE_SELF) {
            val ws = runCatching { svc.windows }.getOrNull() ?: emptyList()
            var best = ""
            var bestLayer = Int.MIN_VALUE
            for (w in ws) {
                if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val pkg = w.root?.packageName?.toString() ?: ""
                if (pkg.isEmpty() || pkg == PACKAGE_SELF) continue
                if (w.layer > bestLayer) {
                    bestLayer = w.layer
                    best = pkg
                }
            }
            if (best.isNotEmpty()) return best
        }
        return active
    }

    override suspend fun launch(): Boolean {
        // 用户关闭「自动跳转」：不主动拉起必应，仅在必应已在前台时可用
        if (!autoLaunch) return foregroundPackage().contains(BING_PACKAGE)
        val started = AppLauncher.launchToUser(svc, BING_PACKAGE, targetSerial)
        if (!started) {
            // 兜底：传统方式拉起
            val intent = runCatching {
                svc.packageManager.getLaunchIntentForPackage(BING_PACKAGE)
            }.getOrNull() ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { svc.startActivity(intent) }.getOrNull() ?: return false
        }
        // 等待前台切到 Bing；中途若弹出 分身应用选择器则自动选目标实例（限次防卡死）
        var chooserSeen = 0
        var lastFp = ""
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            val fp = foregroundPackage()
            if (fp.contains(BING_PACKAGE)) return true
            // 选择器已重试过 2 次仍无法解决 → 直接退出，交由 gate 看门狗/重试处理，绝不在此挂死
            if (chooserSeen >= 2) break
            if (fp != lastFp) {
                android.util.Log.i("RippleReward", "launch 前台包名: 「$fp」")
                lastFp = fp
            }
            if (fp.contains("multiapp")) {
                chooserSeen++
                android.util.Log.i("RippleReward", "launch 检测到分身选择器 #$chooserSeen，自动选实例")
                resolveChooser()
                delay(900)
            } else {
                delay(400)
            }
        }
        // 兜底：即使前台包名未被识别为 multiapp，也再尝试一次按选择器布局点选
        val fpEnd = foregroundPackage()
        if (fpEnd.contains("multiapp") || chooserSeen == 0) {
            android.util.Log.i("RippleReward", "launch 超时兜底强制处理（fp=「$fpEnd」）")
            runCatching { resolveChooser() }
            delay(700)
        }
        return foregroundPackage().contains(BING_PACKAGE)
    }

    /**
     * 分身选择器：按目标实例选中对应项。
     * 主空间项 label「微软必应」；分身项「微软必应 1 …」。
     *
     * 兼容两种窗口形态：
     * 1) 活动窗口即选择器（rootInActiveWindow 包名为 com.oplus.multiapp）；
     * 2) 活动窗口不是选择器但选择器弹窗存在（遍历所有窗口找 multiapp 取其 root）。
     */
    override suspend fun resolveChooser(): Boolean {
        val expect = if (BingInstanceResolver.isDual(targetSerial)) "微软必应 1" else "微软必应"
        var root: AccessibilityNodeInfo? = svc.rootInActiveWindow
        if (root?.packageName?.toString()?.contains("multiapp") != true) {
            // 活动窗口不是选择器：遍历全部窗口找 multiapp 窗口的 root
            val ws = runCatching { svc.windows }.getOrNull() ?: emptyList()
            android.util.Log.i("RippleReward", "resolveChooser: windows=${ws.size} active=${root?.packageName}")
            for (w in ws) {
                val pkg = w.root?.packageName?.toString() ?: ""
                if (pkg.contains("multiapp")) {
                    root = w.root
                    android.util.Log.i("RippleReward", "resolveChooser: 找到 multiapp 窗口 root")
                    break
                }
            }
        }
        if (root == null) {
            android.util.Log.i("RippleReward", "resolveChooser: 找不到选择器 root，坐标兜底")
            // 分身应用选择器布局稳定：主空间项在左侧、分身项在右侧（底部弹窗中部）。
            // 无障碍窗口 API 偶发返回空（windows=0）时按布局百分比点选，可显著提高成功率。
            val dm = svc.resources.displayMetrics
            val x = if (BingInstanceResolver.isDual(targetSerial)) (dm.widthPixels * 0.70f).toInt()
            else (dm.widthPixels * 0.30f).toInt()
            val y = (dm.heightPixels * 0.84f).toInt()
            android.util.Log.i("RippleReward", "resolveChooser 坐标兜底点击 expect=$expect @($x,$y)")
            svc.dispatchClick(x, y)
            return true
        }

        // 1) 优先直接点目标文本节点（行文本节点即使不可点，点击其中心同样生效）
        var hit: Pair<String, Rect>? = null
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 400) {
            val n = q.removeFirst(); visited++
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (t == expect || d == expect || d?.startsWith(expect) == true || t?.startsWith(expect) == true) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && r.width() > 0 && r.height() > 0) { hit = "text" to r; break }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        if (hit != null) {
            val r = hit!!.second
            android.util.Log.i("RippleReward", "resolveChooser 命中「$expect」(${hit!!.first}) $r")
            svc.dispatchClick(r.exactCenterX().toInt(), r.exactCenterY().toInt())
            return true
        }

        // 2) 兜底：可点击节点按位置取目标项（主空间取第一个，分身取第二个）
        val items = mutableListOf<Rect>()
        val q2 = ArrayDeque<AccessibilityNodeInfo>(); q2.add(root)
        var v2 = 0
        while (q2.isNotEmpty() && v2 < 400) {
            val n = q2.removeFirst(); v2++
            if (n.isClickable) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && r.width() > 0 && r.height() > 0) items.add(r)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q2.add(it) }
        }
        items.sortBy { it.top }
        android.util.Log.i("RippleReward", "resolveChooser 兜底: 可点击项=${items.size} dual=${BingInstanceResolver.isDual(targetSerial)}")
        val pick = if (BingInstanceResolver.isDual(targetSerial)) items.getOrNull(1) ?: items.firstOrNull()
        else items.firstOrNull()
        if (pick != null) {
            svc.dispatchClick(pick.exactCenterX().toInt(), pick.exactCenterY().toInt())
            return true
        } else {
            android.util.Log.i("RippleReward", "resolveChooser: 无可点击项，放弃")
            return false
        }
    }

    override suspend fun captureScreen(): Bitmap? = svc.captureScreen()

    override suspend fun visibleWidth(): Int = svc.resources.displayMetrics.widthPixels

    override suspend fun visibleHeight(): Int = svc.resources.displayMetrics.heightPixels

    /** 不该被当作新闻标题的成分：导航 tab、积分页卡片、推广/轮播卡片、应用菜单项、电商广告 */
    private val NEWS_TITLE_EXCLUDE = listOf(
        "广告", "分享", "赞 ", "查看详细信息", "了解更多", "更多此类",
        "探索国内新鲜事", "相机", "语音搜索", "标签页", "主页", "应用", "搜索",
        // 积分页标记与推广/轮播卡片（item 1 of N 为轮播组件连接文本）
        "item ", "已赚取", "积分", "拼图", "兑换", "Rewards", "充值", "今日积分",
        "更多活动", "每日活动", "签入", "签到", "详细信息",
        // 必应应用菜单项（书签/历史/设置/热搜/壁纸等入口，非新闻）
        "书签", "历史记录", "设置", "热搜", "壁纸", "图片", "天气", "翻译",
        "万有引力", "微软资讯", "MSN",
        // 电商/B2B 信息流广告（新闻流高频混排，误点不计分还进验证页）
        "爱采购", "批发", "供应商", "全网商品", "询价", "直通车", "采销", "订货",
        "限时", "秒杀", "特价", "大促", "优惠", "低至", "官方旗舰店", "官方店",
        "专营店", "好物", "爆款", "热卖", "领券", "返现", "满减", "拼团", "0元购",
        "入住可享", "点我领取", "免费领取", "免费下载", "下载App", "点击下载",
        // 必应工具页/天气卡/游戏推广（非新闻正文，点开不计分还占轮次）
        "隐私声明", "InPrivate", "局部晴朗", "°C", "°", "帮助与反馈", "帮助",
        "设置中心", "公测", "预约", "礼包", "taptap", "下载游戏",
    )

    override suspend fun newsTitleCandidates(): List<Pair<String, Rect>> =
        withContext(Dispatchers.Default) {
            val root = bingWindowRoot() ?: return@withContext emptyList()
            val m = svc.resources.displayMetrics
            val out = mutableListOf<Pair<String, Rect>>()
            // 收集所有可点击节点的文本/desc 与其 bounds（内容卡片节点常把标题放 content-desc）
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var visited = 0
            while (q.isNotEmpty() && visited < 20000) {
                val n = q.removeFirst(); visited++
                if (n.isClickable) {
                    val t = n.text?.toString()?.trim().orEmpty()
                    val d = n.contentDescription?.toString()?.trim().orEmpty()
                    val label = if (d.length >= t.length) d else t
                    if (label.length >= 10) {
                        val r = Rect(); n.getBoundsInScreen(r)
                        // 高度太高的是整屏容器/头部图，高度太矮的只是分割线；仅取首页靠上的内容卡片行
                        if (!r.isEmpty && r.height() in 40..900 && r.top in 0..m.heightPixels) {
                            val clean = NEWS_TITLE_EXCLUDE.none { label.contains(it) }
                            if (clean) out += label to r
                        }
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
            // 去掉极端重复（同一标题以不同 bounds 出现多次），按 top 排序
            val seen = HashSet<String>()
            out.filter { seen.add(it.first) }
                .sortedBy { it.second.top }
        }

    override fun dismissDialogs() { /* Task 6 基于真机探测补确认按钮定位 */ }

    companion object {
        private const val BING_PACKAGE = "com.microsoft.bing"
        private const val PACKAGE_SELF = "com.ripple.script"
    }
}