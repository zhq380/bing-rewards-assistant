package com.ripple.script.rewards

import android.graphics.Rect
import kotlinx.coroutines.delay
import com.ripple.script.data.RunProgress
import com.ripple.script.data.RunProgressStore
import com.ripple.script.rewards.AccessibilityBingUi

/**
 * 一次运行的结果。success=false 表示失败或需要人工干预（见 message）。
 */
data class RewardsResult(
    val success: Boolean,
    val message: String,
    val signedIn: Boolean,
    val searched: Int,
    val read: Int,
    val dailySet: Int = 0,
    val log: List<String>,
)

/**
 * 专职状态机智能体：准备(ensureBing) → 会话检测(detectSignedIn) → 登录(doSignIn) →
 * 签到(doCheckIn) → 搜索(doSearch) → 阅读(doRead) → 收尾。
 *
 * 仅依赖 [BingUi] 抽象，可 mock/fake 单测。
 *
 * > 凡依赖真实 Bing 界面（登录字段顺序、搜索框定位、上限文案、签到/阅读入口）的部分，
 * > 均用"语义匹配 + 可注入 lambda/常量"，待 Task 6 真机 dump 后替换为精确定位，勿写死像素坐标。
 */
class RewardsAgent(
    private val ui: BingUi,
    private val searchCount: Int,
    private val readCount: Int,
    private val onProgress: (String) -> Unit,
    private val keywords: () -> List<String> = { DEFAULT_KEYWORDS },
    private val control: RunControl = RunControl(),
    private val readMs: Long = READ_MS,
    private val searchGapMs: Long = SEARCH_GAP_MS,
    private val dailySetMs: Long = DAILY_DWELL_MS,
    private val searchEnabled: Boolean = true,
    private val readEnabled: Boolean = true,
    private val dailySetEnabled: Boolean = true,
    private val isClone: Boolean = false,
    private val autoCount: Boolean = false,
    private val classifier: PageStateClassifier = PageStateClassifier(),
    /** 离开必应后自动拉回的最大重试次数（无人接管：耗尽则终止，绝不挂起等人） */
    private val recoverAttempts: Int = BING_RECOVER_ATTEMPTS,
    /** 自动拉回每次重试的间隔 */
    private val recoverGapMs: Long = BING_RECOVER_GAP_MS,
    /** 断点续跑进度存储；为 null 时不启用（保持纯内存行为） */
    private val progress: RunProgressStore? = null,
) {
    private val log = mutableListOf<String>()
    private val rnd = java.util.Random()
    /** 最近一次有效活动时间戳（note/主循环都会刷新）。看门狗用它检测「静默卡死」 */
    private val lastActivityAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    private val searchedWords = mutableSetOf<String>()
    /** 本次运行已点开过的新闻标题（避免重复阅读同一篇导致不计分） */
    private val readArticles = mutableSetOf<String>()
    /** 自动化模式：解析积分卡片缺口预计算出的本次搜索/阅读执行次数 */
    private var autoSearchNeeded: Int? = null
    private var autoReadNeeded: Int? = null
    /** 实际执行的搜索/阅读次数（自动化模式下被预计算值覆盖，非自动化=手动配置） */
    private var searchRuns = searchCount
    private var readRuns = readCount

    // —— 断点续跑：本次运行的累计完成计数（含历史断点补偿），每完成一次即落盘 ——
    private var progressSearched = 0
    private var progressRead = 0
    private var progressDaily = 0

    private fun todayStr(): String = java.time.LocalDate.now().toString()

    /** 落盘当日进度，供进程被杀后重开时跳过已完成部分 */
    private fun persistProgress() {
        progress?.save(RunProgress(todayStr(), progressSearched, progressRead, progressDaily))
    }

    private fun note(s: String) {
        log += s
        lastActivityAt.set(System.currentTimeMillis())
        LogT.note(s)
        onProgress(s)
    }

    /** 可中断 delay：所有延迟都走 interruptibleDelay，确保暂停即时响应 */
    private suspend fun idelay(ms: Long) {
        control.interruptibleDelay(ms)
    }

    suspend fun run(): RewardsResult {
        return try {
            runInternal()
        } catch (e: RunControl.StoppedException) {
            RewardsResult(false, e.message ?: "已停止", false, 0, 0, log = log)
        } catch (e: Exception) {
            RewardsResult(false, "异常: ${e.message}", false, 0, 0, log = log)
        }
    }

    /**
     * 运行门控点：手动暂停/停止 + 「离开必应自动拉回」。
     *
     * 无人接管要求：脚本自身**绝不进入"等待人工"暂停态**。
     * - 手动暂停（用户在通知栏点了暂停）：仍然挂起等待「继续」——这是用户主动干预，保留。
     * - 运行中离开必应：先短暂等待（窗口切换过渡期 / 系统弹窗），再周期自动拉起；
     *   重试 [recoverAttempts] 次仍回不来 → 抛异常终止本次运行（由 [run] 转成失败结果 + 通知），
     *   不再 setPaused 挂起等人。
     *
     * 每次重试前先 dismissDialogs + resolveChooser，避免卡在系统弹窗/分身选择器上。
     */
    private suspend fun gate() {
        // 手动暂停则先挂起（含取消检测）
        control.awaitIfPaused()

        // 短暂等待避免窗口切换过渡期误判
        idelay(300)

        // 看门狗：距上次有效活动过久（弹窗/分身选择器/系统卡片抢前台导致流程静默）→ 强制恢复一次
        val now = System.currentTimeMillis()
        val idleSec = (now - lastActivityAt.get()) / 1000
        if (idleSec > 60 && !ui.foregroundPackage().contains("bing")) {
            note("看门狗: 静默 ${idleSec}s（前台=${ui.foregroundPackage()}），尝试强制恢复")
            ui.dismissDialogs()
            ui.resolveChooser()
            ui.launch()
        }
        lastActivityAt.set(System.currentTimeMillis())

        // 快速检查：仍在必应则直接返回
        if (ui.foregroundPackage().contains("bing")) return

        var noted = false
        repeat(recoverAttempts) { i ->
            if (control.cancelled.value) return
            if (!noted) {
                note("已离开必应，正在自动拉回…")
                noted = true
            }
            ui.dismissDialogs()
            ui.resolveChooser()
            val relaunched = ui.launch()
            idelay(if (relaunched) recoverGapMs else (recoverGapMs / 2).coerceAtLeast(200))
            if (ui.foregroundPackage().contains("bing")) {
                note("已回到必应，继续执行")
                return
            }
        }
        throw IllegalStateException("无法回到必应（已自动重试 $recoverAttempts 次），终止运行")
    }

    /** 重试执行某个布尔操作，直到成功或次数耗尽；每次失败间隔 [gapMs] */
    private suspend fun retry(times: Int, gapMs: Long = 700, action: suspend () -> Boolean): Boolean {
        repeat(times) { i ->
            if (action()) return true
            if (i < times - 1) delay(gapMs)
        }
        return false
    }

    /** 确保当前在 Rewards 积分页，不在则按返回键直到回到积分页或超时 */
    private suspend fun ensureRewardsPage(): Boolean {
        if (isOnRewardsPage()) return true
        note("回到积分页…")
        repeat(5) {
            ui.back()
            idelay(800)
            if (isOnRewardsPage()) return true
        }
        // 兜底返回 false，不调用 doCheckIn 避免重进导致导航失控
        LogT.scan("ensureRewardsPage 超时，继续执行")
        return false
    }

    /**
     * 判断当前是否在 Rewards 积分页。
     *
     * 硬前提：必须确实在必应前台才判定。悬浮胶囊会把 rootInActiveWindow 劫持成
     * com.ripple.script，而脚本主页有「搜索 × / 阅读 ×」文案，OCR 兜底会把它误判成
     * 积分页——所以一旦确认不在必应前台，直接返回 false（不允许 OCR 判定）。
     * OCR 兜底仅在「必应前台但无障树读不到（分身/WebView）」的场景使用。
     */
    private suspend fun isOnRewardsPage(): Boolean {
        // 0) 前台门槛：不在必应前台（如已退到脚本应用/桌面），直接判否，杜绝误判
        if (!ui.foregroundPackage().contains("bing")) {
            LogT.scan("页面判定: 前台离开必应=${ui.foregroundPackage()}，非积分页")
            return false
        }
        // 1) 无障碍快检（主空间 WebView 可读时最快最准）。
        if (ui.screenTexts().any { it.contains("今日积分") || it.contains("搜索以赚取") || it.contains("阅读以赚取") }) {
            return true
        }
        // 2) 截屏 → 统一识别核心（分身/WebView 不可读场景的唯一可靠通道）
        val bmp = ui.captureScreen() ?: return false
        val snap = classifier.classify(bmp, ui.screenTexts())
        LogT.scan("页面判定: ${snap.pageType} (viaMl=${snap.viaMlModel})")
        return classifier.isRewardsHome(snap)
    }

    /**
     * 检测积分页是否显示「真棒」或「120/120」（所有任务已完成）。
     * 同 [isOnRewardsPage]，非必应前台不允许 OCR 判定（防把脚本主页文案误判为完成）。
     */
    private suspend fun isAllTasksDone(): Boolean {
        if (!ui.foregroundPackage().contains("bing")) return false
        val texts = ui.screenTexts()
        if (texts.any { it.contains("真棒") || it.contains("120/120") }) {
            LogT.scan("无障碍检测到全部完成: ${texts.filter { it.contains("真棒") || it.contains("120") }}")
            return true
        }
        val bmp = ui.captureScreen() ?: return false
        val snap = classifier.classify(bmp, texts)
        val done = classifier.isAllDone(snap)
        if (done) LogT.scan("OCR 检测到全部完成: ${snap.allText.take(200)}")
        return done
    }

    /**
     * 会话检测：判断必应当前是否处于登录态（账号由用户在必应内自行登录，本脚本不代劳）。
     *
     * 判定顺序：
     * 1) 无障碍文本命中已登录特征（积分卡片 / 账户入口）→ 已登录；
     * 2) 出现登录入口短文本且无已登录特征 → 未登录；
     * 3) 无障碍读不到（分身 / WebView）→ OCR 兜底走同样两步；
     * 4) 两条通道都拿不到证据 → 保守按已登录（继续跑），避免误停正常流程。
     *
     * 保守原则：只在「有明确未登录证据」时才判未登录。
     */
    private suspend fun detectSignedIn(): Boolean {
        val texts = ui.screenTexts()
        if (RewardsIntelligence.hasSignedInMarker(texts)) {
            LogT.scan("会话检测: 无障碍命中已登录标记")
            return true
        }
        val entry = texts.firstOrNull { RewardsIntelligence.isSignInEntryText(it) }
        if (entry != null) {
            LogT.scan("会话检测: 无障碍命中登录入口「$entry」，判定未登录")
            return false
        }

        val bmp = ui.captureScreen() ?: run {
            LogT.scan("会话检测: 无 OCR 通道，保守按已登录")
            return true
        }
        val snap = classifier.classify(bmp, texts)
        val ocrTexts = snap.lines.map { it.text } + snap.allText
        if (RewardsIntelligence.hasSignedInMarker(ocrTexts)) {
            LogT.scan("会话检测: OCR 命中已登录标记")
            return true
        }
        val ocrEntry = snap.lines.map { it.text }.firstOrNull { RewardsIntelligence.isSignInEntryText(it) }
        if (ocrEntry != null) {
            LogT.scan("会话检测: OCR 命中登录入口「$ocrEntry」，判定未登录")
            return false
        }
        LogT.scan("会话检测: 无明确证据，保守按已登录处理")
        return true
    }

    private suspend fun runInternal(): RewardsResult {
        note("准备：拉起 Bing 并等待就绪")
        if (!ensureBing()) throw IllegalStateException("Bing 未在前台")

        // Phase 1 · 会话检测：未登录则不执行任何签到动作，直接收尾并通知用户手动登录
        note("Phase 1 · 会话检测")
        if (!detectSignedIn()) {
            note("检测到未登录/会话过期，请在必应中登录后重试")
            return RewardsResult(false, "未登录：请在必应中登录后再运行", false, 0, 0, 0, log)
        }

        // 断点续跑：读今日已完成计数。自动化模式由积分卡片缺口实时算次数，不再叠加补偿；
        // 非自动化模式按剩余量继续，避免被杀后重跑时把已完成任务再做一遍。
        val saved = progress?.load()
        if (saved != null && saved.date == todayStr() &&
            (saved.searchedDone > 0 || saved.readDone > 0)
        ) {
            progressSearched = saved.searchedDone
            progressRead = saved.readDone
            progressDaily = saved.dailyDone
            if (!autoCount) {
                searchRuns = (searchRuns - saved.searchedDone).coerceAtLeast(0)
                readRuns = (readRuns - saved.readDone).coerceAtLeast(0)
                note("今日已进行：搜索 $progressSearched 次、阅读 $progressRead 篇，本次继续补完")
            }
        }

        note("Phase 3 · 每日签到")
        doCheckIn()

        LogT.daily("积分页文本: ${ui.screenTexts().joinToString(" | ").take(500)}")

        // 签到后检查是否已全部完成
        if (isAllTasksDone()) {
            note("检测到「真棒」，所有任务已完成！")
            return RewardsResult(true, "全部完成（签到后检测）", true, 0, 0, 0, log)
        }

        // 每日活动（签到后做，积分多）
        var dailySet = 0
        if (dailySetEnabled) {
            note("Phase 3.5 · 每日活动")
            dailySet = doDailySet()
            progressDaily = dailySet
            persistProgress()
            backToRewardsPage()

            // 每日活动后检查是否已全部完成
            if (isAllTasksDone()) {
                note("检测到「真棒」，所有任务已完成！")
                return RewardsResult(true, "全部完成（每日活动后检测）", true, 0, 0, dailySet, log)
            }
        } else {
            note("每日活动已关闭，跳过")
        }

        // 扫描「搜索 / 阅读」今日完成状态
        var task = if (searchEnabled || readEnabled) scanTaskStatus() else TaskStatus(false, false)

        // 自动化模式：用积分缺口预计算的次数覆盖手动次数（未解析到缺口时保持手动值）
        if (autoCount) {
            autoSearchNeeded?.let { searchRuns = it }
            autoReadNeeded?.let { readRuns = it }
            LogT.scan("执行次数: 搜索×$searchRuns 阅读×$readRuns（自动化=${autoCount}）")
        }

        var searched = 0
        if (!searchEnabled) {
            note("搜索已关闭，跳过搜索")
        } else if (task.searchDone) {
            note("搜索任务今日已完成，跳过搜索")
        } else {
            note("Phase 4 · 搜索 × $searchRuns")
            searched = doSearch()
            task = scanTaskStatus()

            // 搜索后检查是否已全部完成
            if (isAllTasksDone()) {
                note("检测到「真棒」，所有任务已完成！")
                return RewardsResult(true, "全部完成（搜索后检测）", true, searched, 0, dailySet, log)
            }
        }

        var read = 0
        if (!readEnabled) {
            note("阅读已关闭，跳过阅读")
        } else if (task.readDone) {
            note("阅读任务今日已完成，跳过阅读")
        } else {
            note("Phase 5 · 阅读 × $readRuns")
            read = doRead()
            task = scanTaskStatus()

            // 阅读后检查是否已全部完成
            if (isAllTasksDone()) {
                note("检测到「真棒」，所有任务已完成！")
                return RewardsResult(true, "全部完成（阅读后检测）", true, searched, read, dailySet, log)
            }
        }

        // 汇总：以积分页权威信号（真棒/120/OCR 全完成）判定是否全部完成。
        // 注意：不用「卡片 done 布尔」直接判（积分延迟到账会让卡片短暂未满，导致假装完成）
        val allDone = isAllTasksDone()
        val gap = buildList {
            if (searchEnabled && !task.searchDone) add("搜索未满")
            if (readEnabled && !task.readDone) add("阅读未满")
        }.joinToString("、").ifEmpty { "积分尚未确认达标" }
        if (allDone) {
            note("所有任务已全部完成，今日目标达成！")
            // 今日目标达成，清掉断点进度，避免下次运行还按旧进度补偿
            progress?.clear()
        } else {
            // 未满：真实告知缺口，面板不再显示「完成」
            note("Phase 6 · 收尾（$gap，本次已完成搜索 $searched 次、阅读 $read 篇）")
        }
        return RewardsResult(
            true,
            if (allDone) "全部完成"
            else if ((!searchEnabled || task.searchDone) && (!readEnabled || task.readDone)) "完成（积分未确认）"
            else "未完成（$gap）",
            true, searched, read, dailySet, log
        )
    }

    /**
     * 确保当前在 Rewards 积分页顶部。
     *
     * 防跑偏策略：
     * 1) 已在积分页 → 直接回顶，零成本返回；
     * 2) 阅读后停在文章/信息流 → 最多回退 2 层（每层 back 前都校验必应前台，
     *    底层 back 自带前台拦截，一旦退出必应立即拒绝），绝不死按 back；
     * 3) 仍回不去 → 走确定性重导航「应用 tab → Rewards 入口」，不依赖 back 栈，
     *    任何深链接页面都能稳定回到积分页，不会再退到脚本应用上滑动导致卡死。
     */
    private suspend fun backToRewardsPage() {
        gate()
        // 1) 已在本页则直接滚动回顶部
        if (isOnRewardsPage()) { scrollToTopForCards(); return }

        // 2) 轻退回退至多 1 层（阅读后可能停在信息流）；
        //    绝不连续 back：阅读文章页可能是独立任务栈，连退 2 层就会把必应整个
        //    退出、回到脚本应用 —— 一层退不回来立即交给确定性重导航兜底
        if (ui.foregroundPackage().contains("bing")) {
            ui.back()
            idelay(1000)
        }

        // 3) 返回无果 → 确定性重导航进入积分页（不依赖 back 栈，防越退越深）
        if (!isOnRewardsPage()) reenterRewardsPage()
        scrollToTopForCards()
    }

    /**
     * 确定性重导航回积分页：拉起必应（back 越界/阅读页为独立 Activity 后必应已掉前台时）→
     * 回到必应主页（文章页无底部 tab 时 back 一步）→ 应用 tab → Rewards 入口。
     * 绝不盲按 back（防越退越深，退到桌面/脚本应用死循环）。
     */
    private suspend fun reenterRewardsPage() {
        // 不在必应前台：先重新拉起必应（阅读页若是独立 Activity，back 一次就退掉了必应）
        if (!ui.foregroundPackage().contains("bing")) {
            if (!ui.launch()) {
                LogT.read("重导航拉起必应失败，交还控制权")
                return
            }
            idelay(2500)
        }
        // 在必应内但可能停在无底部 tab 的文章页：点「应用」失败就 back 一步回主页再试；
        // 绝不连按（最多 2 步），未点中即交还控制权
        var tab = false
        repeat(3) { attempt ->
            if (!ui.foregroundPackage().contains("bing")) return@repeat
            tab = retry(2, 600) { ui.clickText(APP_TAB) || ui.click(APP_TAB_X, APP_TAB_Y) }
            if (tab) return@repeat
            if (attempt < 2) {
                ui.back()
                idelay(1000)
            }
        }
        idelay(1500)
        LogT.read("重导航 应用tab=$tab")
        if (!tab) return
        val entry = retry(3, 600) { ui.clickText(REWARDS_ENTRY) || ui.click(REWARDS_ITEM_X, REWARDS_ITEM_Y) }
        idelay(2500)
        LogT.read("重导航 Rewards入口=$entry 回积分页=${isOnRewardsPage()}")
    }

    /** 积分页标签（签入/搜索以赚取）一般在顶部，向下滚动让顶部露出 */
    private suspend fun scrollToTopForCards() {
        repeat(3) {
            if (ui.screenTexts().any { it.contains("今日积分") || it.contains("搜索以赚取") || it.contains("签入") }) return
            ui.scrollDown()
            idelay(500)
        }
    }

    /**
     * 扫描积分页卡片，识别「搜索以赚取 / 阅读以赚取」今日是否已完成。
     * 卡片 content-desc 含「已赚取」且无「需要/剩余」缺口即视为完成。
     * 未扫到对应卡片（未加载/界面变动）时保守按未完成处理，照常执行。
     */
    private suspend fun scanTaskStatus(): TaskStatus {
        // 0) 确保在积分页顶部再扫：阅读/搜索后可能停在新闻流，导致卡片识别失败误判未完成
        backToRewardsPage()

        // 1) 无障碍读取卡片文本（主空间可用；仅信任真正的必应窗口，防止读到本应用悬浮文案）
        var searchDone: Boolean? = null
        var readDone: Boolean? = null
        var round = 0
        val onBing = ui.foregroundPackage().contains("bing")
        var searchCardText: String? = null
        var readCardText: String? = null
        while (round < 6 && (searchDone == null || readDone == null)) {
            if (onBing) {
                for (t in ui.screenTexts()) {
                    if (searchDone == null && t.contains("搜索以赚取")) {
                        searchDone = RewardsIntelligence.isTaskCompleted(t)
                        searchCardText = t
                        LogT.scan("搜索卡片=「$t」done=$searchDone")
                    }
                    if (readDone == null && t.contains("阅读以赚取")) {
                        readDone = RewardsIntelligence.isTaskCompleted(t)
                        readCardText = t
                        LogT.scan("阅读卡片=「$t」done=$readDone")
                    }
                }
            }
            if (searchDone != null && readDone != null) break
            ui.scrollUp()
            idelay(800)
            round++
        }

        // 2) 无障碍读不到（分身跨 user 隔离）→ 截屏走统一识别核心解析卡片
        if (searchDone == null || readDone == null) {
            val bmp = ui.captureScreen()
            if (bmp != null) {
                val snap = classifier.classify(bmp, ui.screenTexts())
                if (searchDone == null) searchDone = classifier.taskCardCompleted(snap, "搜索以赚取")
                if (readDone == null) readDone = classifier.taskCardCompleted(snap, "阅读以赚取")
            }
        }

        // 3) 自动化模式预计算：按卡片「已赚取 X 积分(需要 Y 积分)」缺口，反推还需执行几次
        //    （每次搜索/阅读固定 3 分）。未解析到缺口时保留手动次数兜底。
        if (autoCount) {
            autoSearchNeeded = searchCardText?.let { RewardsIntelligence.remainingOpsFromCard(it) }
            autoReadNeeded = readCardText?.let { RewardsIntelligence.remainingOpsFromCard(it) }
            LogT.scan("自动化预计算: 搜索还需=${autoSearchNeeded} 阅读还需=${autoReadNeeded}")
        }

        LogT.scan("扫描结束 searchDone=$searchDone readDone=$readDone")
        return TaskStatus(searchDone == true, readDone == true)
    }

    private data class TaskStatus(val searchDone: Boolean, val readDone: Boolean)

    /**
     * 确保必应在前台：不在则自动拉起；拉不起交给 [gate] 的自动拉回（含熔断）。
     *
     * 无人接管：不再挂起等待用户手动切到必应，拉不回直接返回 false，
     * 由 [runInternal] 抛异常终止本次运行并通知用户。
     */
    private suspend fun ensureBing(): Boolean {
        ui.dismissDialogs()
        if (ui.foregroundPackage().contains("bing")) return true

        if (!ui.launch()) {
            note("必应未在前台，正在自动拉起…")
            gate()
            return ui.foregroundPackage().contains("bing")
        }
        idelay(1500)
        if (ui.foregroundPackage().contains("bing")) return true
        gate()
        return ui.foregroundPackage().contains("bing")
    }

    private suspend fun doCheckIn(): Boolean {
        gate()
        // 到达 Rewards 积分页：应用 tab → Rewards 入口 → 进入积分页
        val tab = retry(2, 600) { ui.clickText(APP_TAB) || ui.click(APP_TAB_X, APP_TAB_Y) }
        idelay(1500)
        LogT.checkin("应用tab点击=$tab")
        if (!tab) {
            note("找不到「应用」tab")
            return false
        }
        val entry = retry(3, 600) { ui.clickText(REWARDS_ENTRY) || ui.click(REWARDS_ITEM_X, REWARDS_ITEM_Y) }
        idelay(2500)
        LogT.checkin("Rewards入口点击=$entry")
        if (!entry) {
            note("找不到「Rewards」入口")
            return false
        }
        val ok = ui.waitForText(CHECK_IN_MARKER, 4000)
        LogT.checkin("进入Rewards积分页=$ok")
        note(if (ok) "已进入 Rewards 积分页" else "已进入 Rewards 页")

        // dump 积分页 OCR 文本供调试
        dumpRewardsPage()

        // 进入积分页后，执行签入积分闭环：读前值 → 必点「签入」→ 等待结算 → 读后值对比到账。
        // 签入分独立于「今日积分 120」之外，只有真的点进「签入」横幅才会到账（可能延迟结算）。
        val before = totalPointsFromTexts(ui.screenTexts())
        val texts0 = ui.screenTexts()
        val signedCountdown = texts0.any { it.contains("小时") && it.contains("分") }  // 当日已签过才有的倒计时

        // 签到调用链：硬币按钮 → 签入行 → 文字点击
        val clicked = retry(1, 800) { clickCoinButton() }
            || retry(3, 800) { clickCheckInRow() }
            || retry(2, 600) {
                ui.clickText("签到") || ui.clickText("立即签到") || ui.clickText("每日签到") ||
                    ui.clickText("签入") || ui.clickText("立即签入") || ui.clickText("每日签入") ||
                    ui.clickTextAny("签入") || ui.clickTextAny("签到")
            }

        // 等签到动画/结算（出现「签到中」横幅则在结算完前保持等待，最多 8s）
        var settling = 0
        while (settling < 8 && ui.screenTexts().any { it.contains("签到中") }) {
            idelay(1000)
            settling++
        }
        idelay(2000)  // 再留结算余量，让积分到账落盘

        val after = totalPointsFromTexts(ui.screenTexts())
        val settledEvidence = ui.screenTexts().any { it.contains("签到中") || it.contains("签到成功") } ||
            ui.screenTexts().any { it.contains("小时") && it.contains("分") } || signedCountdown
        val gained = if (before != null && after != null) after - before else null
        when {
            // 1) 积分到账可观测：签到分真实入账（必应延迟结算，通常 3-8s 内反映）
            gained != null && gained > 0 -> {
                note("签入积分 +$gained 已到账（总积分 $before → $after）")
                LogT.checkin("签入到账验证: before=$before after=$after gained=$gained")
            }
            // 2) 未点中：靠倒计时/天数证明今日已签（当日仅一次，无重复可分）
            !clicked && settledEvidence -> {
                LogT.checkin("今日已签到（连续天数+签到倒计时，无签到按钮可点）")
                note("今日已签到")
            }
            // 3) 点到了但本次无新分：当日已签（此前点击已到账），点击无害
            settledEvidence -> {
                LogT.checkin("已点击签入，本次无新分到账（当日已签，之前已结算）")
                note(if (before == null) "今日已签到" else "今日已签到（总积分 $after）")
            }
            // 4) 无法观测到结算证据：保守记未确认，不断言成功
            else -> {
                LogT.checkin("签入点击已执行，但未观察到到账/倒计时证据，待下次确认")
                note("签入已点击，积分到账未确认")
            }
        }
        return ok || clicked || settledEvidence
    }

    /**
     * 精准点击连续签到硬币按钮（Day 1-Day 7）。
     *
     * 必应积分页「连续签到」下方有一排硬币按钮，每天往后移一位：
     *   Day 1 ✓   Day 2 ✓   Day 3 ⑩   Day 4 ⑩   ... Day 7 50✕
     *
     * 布局特征（无障碍树）：
     *   - Day N 标签是 click=false 的 TextView，Y≈1725~1781，均匀横向排列（间距≈160px）
     *   - 硬币在 Day 标签正上方约 70px（Day top - 70 ≈ 硬币中心Y）
     *   - 已完成的硬币显示 ✓ 勾号，未完成的显示分值（10/15/50），今日活跃的带黄色高亮边框
     *
     * 策略：
     *   1) 已签（有倒计时）→ 跳过硬币遍历，直接用 clickCheckInRow 点横幅（快捷路径）
     *   2) 未签 → 用 OCR 找硬币区中「第一个仍显示分值数字」的 Day = 今日活跃日，精准直跳
     *   3) 识别失败 → 从左到右顺序点击兜底；找不到 Day 标签 → 回退 clickCheckInRow
     */
    private suspend fun clickCoinButton(): Boolean {
        if (!isOnBing()) return false

        val texts = ui.screenTexts()
        val hasCountdown = texts.any { it.contains("小时") && it.contains("分") }
        var hasDayLabels = texts.any { it.matches(Regex("Day \\d+")) }

        // 【关键改动】即使有倒计时，也先尝试点今日硬币（确认真·签到）
        // 点硬币失败/识别不出时，再退 clickCheckInRow 点横幅占位
        if (!hasDayLabels) {
            LogT.checkin("clickCoinButton: 暂无Day标签 → 等待1.5s渲染")
            idelay(1500)
            hasDayLabels = ui.screenTexts().any { it.matches(Regex("Day \\d+")) }
            if (!hasDayLabels) {
                LogT.checkin("clickCoinButton: 仍无Day标签 → 横幅兜底")
                return clickCheckInRow()
            }
        }

        // Day 标签已渲染 → 点硬币！不论是否有倒计时都走一次真·硬币点击
        val dayLabels = ui.findAllText("Day ")
            .filter { (text, _) -> text.matches(Regex("^Day \\d+$")) }
            .sortedBy { (_, r) -> r.centerX() }

        if (dayLabels.isEmpty()) {
            LogT.checkin("clickCoinButton: findAllText无bounds → clickCoinButtonViaOcr")
            return clickCoinButtonViaOcr()
        }

        val safeTop = 200
        LogT.checkin("clickCoinButton: 找到${dayLabels.size}个Day: ${dayLabels.map { it.first }.joinToString()}" +
            if (hasCountdown) "（当前屏幕有倒计时，仍强制执行真·硬币点击确认）" else "（未签模式）")

        // === 智能直跳：今日活跃硬币 ===
        val todayIndex = findTodayCoinIndex(dayLabels.map { it.second })
        if (todayIndex in dayLabels.indices) {
            val (dayText, dayRect) = dayLabels[todayIndex]
            val coinY = (dayRect.top - 70).coerceAtLeast(safeTop)
            val coinX = dayRect.centerX()
            LogT.checkin("clickCoinButton: 【直跳今日】$dayText 硬币(金色圈+数字) @($coinX,$coinY) → 点击!")
            ui.click(coinX, coinY)
            idelay(1500)
            LogT.checkin("clickCoinButton: $dayText 硬币已提交(积分闭环随后验证)")
            return true
        }

        // === 直跳识别失败 → 顺序点击全部硬币（绝不漏，但只有今日高亮的真正生效）===
        LogT.checkin("clickCoinButton: 分值识别失败 → 依次点击全部Day硬币")
        for ((dayText, dayRect) in dayLabels) {
            val coinY = (dayRect.top - 70).coerceAtLeast(safeTop)
            val coinX = dayRect.centerX()
            if (coinY >= dayRect.top) continue
            LogT.checkin("clickCoinButton: 顺序点击 $dayText ($coinX,$coinY)")
            ui.click(coinX, coinY)
            idelay(500)
        }
        LogT.checkin("clickCoinButton: 所有硬币顺序点完(积分闭环随后验证)")
        return true
    }

    /**
     * 识别今日活跃硬币：按 Day 标签顺序，第一个有分值数字分配给它的 = 今日。
     * 策略：
     *   1) 取硬币区（Day 标签正上方纵向带宽 ±90px 范围）里的纯分值数字 5/10/15/30/50
     *   2) 每个分值数字 → 分配给最近的 Day 标签（最近邻，无固定阈值）
     *   3) 按 Day 1→Day 7 顺序找第一个「分配到数字」的 Day = 今日活跃硬币
     *      (已完成 Day 只显示 ✓ 勾号，OCR 不产出纯数字，因此没分配项)
     *
     * @param dayRects 已按 centerX 排序的 Day 标签 bounds 列表
     * @return 今日活跃 Day 的索引（0-based）；-1 表示识别失败
     */
    private suspend fun findTodayCoinIndex(dayRects: List<android.graphics.Rect>): Int {
        if (dayRects.isEmpty()) return -1
        val bmp = ui.captureScreen() ?: return -1
        val lines = classifier.recognizeLines(bmp)

        // 硬币分值带宽：以 Day 顶部上方 70px 为中心，±90px
        val coinRefY = dayRects.first().top - 70
        val coinBandTop = (coinRefY - 90).coerceAtLeast(0)
        val coinBandBottom = coinRefY + 90
        val validNumbers = setOf(5, 10, 15, 30, 50)

        val coinNumbers = lines.filter { l ->
            val t = l.text.trim()
            t.isNotEmpty() && t.all { it.isDigit() } && t.length in 1..3 &&
                (t.toIntOrNull() in validNumbers) &&
                l.bounds.centerY() in coinBandTop..coinBandBottom
        }

        LogT.checkin("findTodayCoinIndex: 分值候选(${coinNumbers.size}) Y∈[$coinBandTop,$coinBandBottom]: " +
            coinNumbers.joinToString { "${it.text}@(${it.bounds.centerX()},${it.bounds.centerY()})" })

        // 分配：每个数字给最近的 Day；记录每个 Day 分配到的最靠近的分值
        val assigned = arrayOfNulls<OcrBlock?>(dayRects.size)
        for (num in coinNumbers) {
            var bestIdx = -1
            var bestDist = Int.MAX_VALUE
            for ((i, rect) in dayRects.withIndex()) {
                val d = kotlin.math.abs(num.bounds.centerX() - rect.centerX())
                if (d < bestDist) { bestDist = d; bestIdx = i }
            }
            if (bestIdx >= 0) {
                // 同一个 Day 被分配多个时，只保留 X 距离最近的那个
                if (assigned[bestIdx] == null ||
                    kotlin.math.abs(assigned[bestIdx]!!.bounds.centerX() - dayRects[bestIdx].centerX()) > bestDist
                ) assigned[bestIdx] = num
            }
        }

        // 按顺序找第一个有分值的 Day
        var firstPending = -1
        for ((i, rect) in dayRects.withIndex()) {
            val a = assigned[i]
            if (a != null) {
                if (firstPending < 0) firstPending = i
                LogT.checkin("  Day[${i+1}] centerX=${rect.centerX()} → 分配分值「${a.text}」@X=${a.bounds.centerX()} (未完成·数字可见)")
            } else {
                LogT.checkin("  Day[${i+1}] centerX=${rect.centerX()} → 无分值 (已完成✓或OCR遗漏)")
            }
        }
        if (firstPending >= 0) LogT.checkin("findTodayCoinIndex: 今日=Day[${firstPending+1}]")
        else LogT.checkin("findTodayCoinIndex: 无法识别今日(全部无分值?)，返回-1")
        return firstPending
    }

    /**
     * OCR 兜底：findAllText 因 bounds 过滤找不到 Day 标签时，
     * 用 OCR 识别 Day N 行，定位硬币位置。
     */
    private suspend fun clickCoinButtonViaOcr(): Boolean {
        val bmp = ui.captureScreen() ?: return clickCheckInRow()
        val lines = classifier.recognizeLines(bmp)
        val dayLines = lines.filter { it.text.matches(Regex("Day \\d+")) }
            .sortedBy { it.bounds.centerX() }

        if (dayLines.isEmpty()) {
            // OCR 也没找到 Day → 最后横幅兜底（极端情况）
            LogT.checkin("clickCoinButtonViaOcr: OCR也未找到Day → 横幅兜底")
            return clickCheckInRow()
        }

        LogT.checkin("clickCoinButtonViaOcr: OCR找到${dayLines.size}个Day: ${dayLines.map { it.text }.joinToString()}")

        // === 智能直跳：今日活跃硬币 ===
        val todayIdx = findTodayCoinIndexFromLines(dayLines, lines)
        if (todayIdx in dayLines.indices) {
            val target = dayLines[todayIdx]
            val coinY = (target.bounds.top - 70).coerceAtLeast(200)
            val coinX = target.bounds.centerX()
            LogT.checkin("clickCoinButtonViaOcr: 【直跳今日】${target.text}(金色圈+数字) @($coinX,$coinY) → 点击!")
            ui.click(coinX, coinY)
            idelay(1500)
            LogT.checkin("clickCoinButtonViaOcr: ${target.text} 硬币已提交(积分闭环随后验证)")
            return true
        }

        // === 直跳失败 → 顺序点击全部硬币 ===
        LogT.checkin("clickCoinButtonViaOcr: 分值识别失败 → 依次点击全部Day硬币")
        for (line in dayLines) {
            val coinY = (line.bounds.top - 70).coerceAtLeast(200)
            val coinX = line.bounds.centerX()
            if (coinY >= line.bounds.top) continue
            LogT.checkin("clickCoinButtonViaOcr: 顺序点击 ${line.text} ($coinX,$coinY)")
            ui.click(coinX, coinY)
            idelay(500)
        }
        LogT.checkin("clickCoinButtonViaOcr: 所有硬币顺序点完(积分闭环随后验证)")
        return true  // 不回退横幅
    }

    /** 与 findTodayCoinIndex 相同逻辑（最近邻分配，无阈值，±90px 带宽），输入是 OCR 的 OcrBlock */
    private fun findTodayCoinIndexFromLines(
        dayLines: List<OcrBlock>,
        allLines: List<OcrBlock>
    ): Int {
        if (dayLines.isEmpty()) return -1
        val coinRefY = dayLines.first().bounds.top - 70
        val coinBandTop = (coinRefY - 90).coerceAtLeast(0)
        val coinBandBottom = coinRefY + 90
        val validNumbers = setOf(5, 10, 15, 30, 50)

        val coinNumbers = allLines.filter { l ->
            val t = l.text.trim()
            t.isNotEmpty() && t.all { it.isDigit() } && t.length in 1..3 &&
                (t.toIntOrNull() in validNumbers) &&
                l.bounds.centerY() in coinBandTop..coinBandBottom
        }

        LogT.checkin("findTodayCoinIndexFromLines: 分值候选(${coinNumbers.size}): " +
            coinNumbers.joinToString { "${it.text}@(${it.bounds.centerX()},${it.bounds.centerY()})" })

        // 每个数字→最近的Day
        val assigned = arrayOfNulls<OcrBlock?>(dayLines.size)
        for (num in coinNumbers) {
            var bestIdx = -1
            var bestDist = Int.MAX_VALUE
            for ((i, dl) in dayLines.withIndex()) {
                val d = kotlin.math.abs(num.bounds.centerX() - dl.bounds.centerX())
                if (d < bestDist) { bestDist = d; bestIdx = i }
            }
            if (bestIdx >= 0) {
                if (assigned[bestIdx] == null ||
                    kotlin.math.abs(assigned[bestIdx]!!.bounds.centerX() - dayLines[bestIdx].bounds.centerX()) > bestDist
                ) assigned[bestIdx] = num
            }
        }

        var firstPending = -1
        for (i in dayLines.indices) {
            if (assigned[i] != null) {
                if (firstPending < 0) firstPending = i
            }
        }
        if (firstPending >= 0) LogT.checkin("findTodayCoinIndexFromLines: 今日=Day[${firstPending+1}]")
        else LogT.checkin("findTodayCoinIndexFromLines: 无法识别今日，返回-1")
        return firstPending
    }

    /**
     * 精准点击「签入」行：从无障碍树找到「签入」和「连续」节点 bounds，
     * 推算整行可点击区域的中心位置。
     *
     * 必应积分页的「签入」是 WebView 内 list row，无障碍树里：
     *   - 「签入」文字节点 click=false，bounds=[98,1491][203,1568]（行左侧）
     *   - 右侧有积分数字（如「5」「10」），更右有倒计时/勾选标记
     *   - 整行都是可点击区域，但点击文字节点中心（X≈150）不够灵敏
     *
     * 策略：
     *   1) findText("签入") → Y 用它的 centerY，X 偏移到屏幕中间偏右
     *   2) 找不到签入 → 用 findText("连续") 推算：连续行下方 ≈120px 就是签入行
     *   3) 都找不到 → 返回 false 交给兜底
     */
    private suspend fun clickCheckInRow(): Boolean {
        if (!isOnBing()) return false
        val sw = ui.visibleWidth()
        val sh = ui.visibleHeight()

        // 1) 优先：通过「签入」文字定位
        val checkInRect = ui.findText("签入")
        if (checkInRect != null) {
            val x = ((checkInRect.right + sw) / 2).coerceIn(checkInRect.right + 10, sw - 10)
            val y = checkInRect.centerY().coerceIn(checkInRect.top + 10, checkInRect.bottom - 10)
            LogT.checkin("clickCheckInRow: 签入bounds=$checkInRect → 点击($x, $y)")
            ui.click(x, y)
            return true
        }

        // 2) 次优：通过「连续」行推算签入行位置（连续在签入上方约 100-150px）
        val streakRect = ui.findText("连续")
            ?: ui.findText("连续签到")
        if (streakRect != null) {
            // 签入行在连续区下方约 streak 高度 + 70-80px 的 padding
            val rowY = (streakRect.bottom + streakRect.height() * 0.6f + 50).toInt()
                .coerceIn(streakRect.bottom + 60, sh - 300)
            val x = sw / 2
            LogT.checkin("clickCheckInRow: 连续bounds=$streakRect → 推算签入行Y=$rowY → 点击($x, $rowY)")
            ui.click(x, rowY)
            return true
        }

        // 3) 兜底：尝试旧的签到横幅多点位
        LogT.checkin("clickCheckInRow: findText 失败，回退 clickCheckInBanner")
        return clickCheckInBanner()
    }

    /**
     * 签到横幅坐标兜底（1272x2772 等常见分辨率）。
     * 由 clickCheckInRow 内部调用或作为独立 fallback。
     *
     * 优化：原固定坐标 Y 值偏移（1445 实际落在「连续」区上方），
     * 现在以 1530 为签入行中心、1370 为连续区中心，覆盖更准。
     */
    private suspend fun clickCheckInBanner(): Boolean {
        if (!isOnBing()) return false
        val sw = ui.visibleWidth()
        // 签入行 Y ≈ 屏幕高度的 55-58% 处（不同分辨率自适应）
        val rowY = (sh() * 0.55f).toInt().coerceAtLeast(1400)
        val candidates = listOf(
            sw / 2 to rowY,              // 行中心
            (sw * 0.35f).toInt() to rowY, // 签入文字右侧
            (sw * 0.7f).toInt() to rowY,  // 积分数字右侧
            (sw * 0.85f).toInt() to rowY, // 倒计时/勾选区
        )
        for ((x, y) in candidates) {
            if (!isOnBing()) break
            ui.click(x, y)
            idelay(500)
            if (ui.screenTexts().any { it.contains("签到成功") || it.contains("恭喜") || it.contains("续签") }) {
                LogT.checkin("clickCheckInBanner 生效（检测到签到反馈）")
                return true
            }
        }
        LogT.checkin("clickCheckInBanner 已点击（无反馈）")
        return true
    }

    /** 获取屏幕高度（懒初始化，供 clickCheckInBanner 自适应计算用） */
    private suspend fun sh(): Int = ui.visibleHeight()

    /** 截图并 dump OCR 文本到 logcat（调试用，不影响流程） */
    private suspend fun dumpRewardsPage() {
        val bmp = ui.captureScreen() ?: return
        val snap = classifier.classify(bmp, ui.screenTexts())
        LogT.checkin("积分页 OCR: ${snap.allText.replace('\n', ' ').take(500)}")
    }

    /**
     * 从界面文本中提取当前总积分（如「14,005 | 积分」「13,913积分」）。
     * 无障碍文本中总积分是最常出现的「含逗号数字 + 积分」token；读不到返回 null（交给倒计时证据兜底）。
     */
    private fun totalPointsFromTexts(texts: List<String>): Int? {
        for (t in texts) {
            if (t.contains("积分")) {
                val m = Regex("(\\d{1,3}(?:,\\d{3})+|\\d{3,})").find(t) ?: continue
                val v = m.groupValues[1].replace(",", "").toIntOrNull()
                if (v != null && v in 100..500000) return v
            }
        }
        return null
    }

    private suspend fun doSearch(): Int {
        gate()
        var done = 0
        val pool = keywords().ifEmpty { DEFAULT_KEYWORDS }.distinct()
        if (pool.isEmpty()) {
            note("无可搜索的词，跳过搜索")
            return 0
        }

        // 每日随机种子：用日期字符串（如 2026-08-24）作为 seed，同一天顺序一致
        val dailySeed = java.time.LocalDate.now().toString().hashCode()
        val dailyRnd = java.util.Random(dailySeed.toLong())

        // 过滤已搜索的词
        val freshPool = pool.filter { it !in searchedWords }
        if (freshPool.isEmpty()) {
            note("所有关键词今日已搜索完毕，跳过搜索")
            return 0
        }

        val shuffled = freshPool.shuffled(dailyRnd)

        // 首次：点积分页顶部搜索栏 → 跳转搜索页
        if (!guidSearchBar()) {
            note("找不到必应搜索栏，跳过搜索")
            return 0
        }
        idelay(1200)

        repeat(searchRuns) { i ->
            gate()
            if (ui.screenTexts().any { t -> LIMIT_MARKERS.any { t.contains(it) } }) {
                note("检测到搜索已达上限，提前停止")
                return done
            }

            // 词池用完：重新洗牌（排除已搜索）
            val word = if (i < shuffled.size) {
                shuffled[i]
            } else {
                val remaining = pool.filter { it !in searchedWords }
                if (remaining.isEmpty()) {
                    note("关键词池已耗尽，停止搜索")
                    return done
                }
                val reshuffled = remaining.shuffled(java.util.Random(dailySeed.toLong() + i))
                reshuffled[(i - shuffled.size) % reshuffled.size]
            }

            if (!retry(2, 800) { ui.typeAndSubmit(word) }) {
                note("搜索输入失败，终止搜索")
                return done
            }
            searchedWords.add(word)
            idelay(1200)
            delay(searchGapMs)
            done++
            progressSearched++
            persistProgress()
            note("已搜索 $done/$searchRuns")

            if (done < searchRuns) {
                gate()
                if (!(ui.clickResourceId(RESULT_ADDRESS_BAR_ID) || ui.click(RESULT_ADDR_X, RESULT_ADDR_Y))) {
                    note("搜索结果页地址栏丢失，终止搜索")
                    return done
                }
                idelay(1200)
                if (ui.screenTexts().any { t -> LIMIT_MARKERS.any { t.contains(it) } }) {
                    note("搜索已达上限，提前停止")
                    return done
                }
            }
        }
        // 搜索完成后，主动回积分页（避免停在搜索页导致后续 backToRewardsPage 掉桌面）
        backToRewardsPage()
        return done
    }

    /** 点必应积分页顶部原生搜索栏，跳转到搜索页（输入框自动聚焦）；resource-id 失败则坐标兜底 */
    private suspend fun guidSearchBar(): Boolean =
        retry(3, 800) { ui.clickResourceId(SEARCH_BAR_ID) || ui.click(SEARCH_BAR_X, SEARCH_BAR_Y) }

    /**
     * 阅读任务：一次进入新闻流后连续读完目标篇数，整批完成后才回积分页确认积分，
     * 不足则自动续读。核心目标是「不频繁往返积分页」：
     *   进新闻流 → 连续点开多篇 → 全部读完 → 回积分页验证淡入的卡片分数 → 未达标续读。
     */
    private suspend fun doRead(): Int {
        var done = 0
        // 防死循环保险：最多执行 READ_MAX_OPS + 冗余篇（计分延迟/广告无效点击可能带来续读）
        val safetyCap = (if (autoCount) READ_MAX_OPS + 4 else readRuns).coerceAtLeast(1)

        /** 连续阅读一轮：在新闻流中连续点开并停留 [count] 篇，单篇读完 back 一步回新闻流，不回积分页 */
        suspend fun readContinuously(count: Int): Int {
            var n = 0
            while (n < count) {
                gate()
                if (!isOnBing()) return n
                // 在新闻流直接点下一篇；找不到标题（如已回到积分页/页面丢失）先确保回到新闻流再点
                var ok = retry(2, 800) { openArticleInFeed() }
                if (!ok && isOnBing()) {
                    ensureNewsFeed()
                    if (!isOnBing()) return n
                    ok = retry(2, 800) { openArticleInFeed() }
                }
                if (!ok) {
                    note("新闻流中找不到可阅读的新闻，终止阅读")
                    return n
                }
                LogT.read("第${done + n + 1}篇 进入文章正文=true")
                delay(readMs)  // 本篇停留阅读计时（Bing 按阅读时长计分）
                n++
                // 读完 back 一步回新闻流继续下一篇（护栏：非必应立即停，绝不越界）
                if (!isOnBing()) return n
                ui.back()
                idelay(1000)
            }
            return n
        }

        while (done < safetyCap) {
            gate()
            if (!isOnBing()) break
            // 决定本轮要读的篇数。
            // 自动化：按卡片缺口计算，但积分到账有延迟——按缺口读完可能仍显示未达标，
            // 若每次只读「当前所见缺口」就会小步续读 → 频繁回积分页。因此每轮按
            // 「缺口 + 冗余」一次读够，把中途确认次数压到最少（冗余由 safetyCap 与
            // 阅读日上限兜底，多读无害）。
            val need = if (autoCount) {
                val card = ui.screenTexts().firstOrNull { it.contains("阅读以赚取") }
                val base = when {
                    card == null -> 0
                    RewardsIntelligence.isTaskCompleted(card) -> 0   // 卡片已达标
                    else -> (RewardsIntelligence.remainingOpsFromCard(card) ?: 0).coerceAtLeast(0)
                }
                if (base > 0) (base + READ_BATCH_ABSORB).coerceAtMost(READ_MAX_OPS) else 0
            } else {
                (readRuns - done).coerceAtLeast(0)
            }
            if (need <= 0) {
                if (done > 0) note("阅读任务已达标，停止阅读")
                break
            }
            // 进入新闻流（若仍在积分页，先点「阅读以赚取」入口；已离开则直接读）
            ensureNewsFeed()
            if (!isOnBing()) break
            val batch = readContinuously(need)
            done += batch
            if (batch > 0) {
                progressRead += batch
                persistProgress()
            }
            if (batch <= 0) break
            // 整批读完 → 统一回积分页，等待卡片分数稳定后确认达标情况。
            // 积分到账有延迟（尤其分身环境），读完立刻读卡片常显示旧值，
            // 若据此误判"未达标"就会再进新闻流续读 → 反复往返。
            backToRewardsPage()
            if (!autoCount) break  // 非自动化：读完固定篇数即结束
            if (awaitCardSettle("阅读以赚取")) {
                note("阅读任务已达标，停止阅读")
                break
            }
            // 卡片已稳定且确未达标（存在广告等无效点击）→ 下一轮续读，
            // 续读完成后仍是整批回积分页，不会每篇往返
        }
        return done
    }

    /**
     * 回积分页后等待卡片分数稳定再做达标判定，避免积分延迟导致反复往返。
     * 轮询积分卡片：出现达标文案（已赚满/无缺口）即结束；连续 [stableAfter]
     * 轮读数一致且仍未达标，才判定为真实缺口（需要续读）。
     */
    private suspend fun awaitCardSettle(marker: String, stableAfter: Int = 2, maxRounds: Int = 6): Boolean {
        var last: String? = null
        var stable = 0
        for (round in 0 until maxRounds) {
            val card = ui.screenTexts().firstOrNull { it.contains(marker) }
            if (card != null && RewardsIntelligence.isTaskCompleted(card)) {
                LogT.read("积分确认: 卡片已达标(round=$round)")
                return true
            }
            if (card != null && card == last) {
                stable++
                if (stable >= stableAfter) {
                    LogT.read("积分确认: 卡片稳定且未达标，需续读")
                    return false
                }
            }
            last = card
            idelay(5000)
        }
        LogT.read("积分确认: 等待超时按未达标处理")
        return false
    }

    /**
     * 在新闻流中打开一篇未读文章正文（真正的计分页）。
     * 候选来自无障碍树的「新闻标题」节点，跳过已读标题（避免重复阅读不计分）。
     */
    private suspend fun openArticleInFeed(): Boolean {
        idelay(1200)  // 等新闻流渲染完成
        if (!isOnBing()) return false
        val cands = ui.newsTitleCandidates()
        LogT.read("新闻标题候选(${cands.size}): ${cands.take(4).map { it.first.take(18) }}")
        val target = cands.firstOrNull { (t, _) -> t !in readArticles }
            ?: cands.firstOrNull()  // 全部已读时回到第一条
        if (target == null) {
            LogT.read("新闻流中无标题候选")
            return false
        }
        val (title, r) = target
        readArticles += title
        // 只在必应前台才落点击；点标题中心（正文入口），防点到卡片空隙
        if (!isOnBing()) return false
        LogT.read("点击文章「${title.take(30)}」@(${r.centerX()}, ${r.centerY()})")
        ui.click(r.centerX(), r.centerY())
        // 点击后 1s 内应进入正文页（仍在必应内）；给加载留时间
        idelay(1500)
        val ok = isOnBing()
        LogT.read("文章点击后回到必应=$ok")
        if (!ok) return false
        // 快速自检：误点广告时进入的是落地页/验证拦截页（非文章正文），
        // 立即撤回重选，绝不空等阅读时长 + 浪费一次计分机会
        if (isAdLandingPage() || isToolPage()) {
            LogT.read("点击后识别为广告落地页/工具页，立即返回重选")
            ui.back()
            idelay(1000)
            return false
        }
        return true
    }

    /**
     * 当前屏幕是否为必应工具页（隐私声明 / InPrivate / 设置中心等，非新闻正文，
     * 点开不计分），命中标记词即认定，用于点击后快速撤回、不消耗阅读轮次。
     */
    private suspend fun isToolPage(): Boolean {
        val texts = ui.screenTexts()
        return texts.any { t -> TOOL_PAGE_MARKERS.any { m -> t.contains(m) } }
    }

    /**
     * 当前屏幕是否为广告落地页：截屏 → 统一识别核心（规则引擎广告标记密集判定优先，
     * 无法 OCR 出广告标记的验证码拦截页等由 kNN AD_PAGE 兜底）。
     */
    private suspend fun isAdLandingPage(): Boolean {
        val bmp = ui.captureScreen() ?: return false
        val snap = classifier.classify(bmp, ui.screenTexts())
        return snap.pageType == PageType.AD_PAGE
    }

    /** 是否仍处于必应前台（阅读/滚动前必须先确认，防止误操作脚本自身页面） */
    private suspend fun isOnBing(): Boolean = ui.foregroundPackage().contains("bing")

    /** 屏幕文本是否仍含积分页标记（阅读前必须离开积分页，否则会把推广卡片当新闻点） */
    private suspend fun hasRewardsMarkers(): Boolean {
        val texts = ui.screenTexts()
        return texts.any {
            it.contains("今日积分") || it.contains("搜索以赚取") ||
                it.contains("阅读以赚取") || it.contains("已赚取")
        }
    }

    /**
     * 确保已进入新闻流：若当前仍停在积分页（积分卡片标记可见），
     * 滚动找到「阅读以赚取」入口点击进入；已离开积分页则直接返回。
     * 返回是否处于可阅读的新闻流（必应前台且无积分标记）。
     */
    private suspend fun ensureNewsFeed(): Boolean {
        if (!isOnBing()) return false
        if (!hasRewardsMarkers()) return true   // 已在新闻流/正文
        // 仍在积分页：滚动找入口并点击（双通道，与 clickReadEntryViaOcr 一致）
        var entered = false
        var attempts = 0
        while (attempts < 4 && !entered && isOnBing() && hasRewardsMarkers()) {
            attempts++
            entered = clickReadEntryViaOcr()
            if (!entered && isOnBing()) ui.scrollUp()
            idelay(300)
        }
        if (entered) LogT.read("ensureNewsFeed: 已从积分页进入新闻流")
        else LogT.read("ensureNewsFeed: 未能离开积分页（可能已达阅读上限）")
        return entered
    }

    /**
     * 点击「阅读以赚取」入口，双通道：
     * 1) 无障碍树直点（主空间首选项）：WebView 卡片文字 OCR 经常读不到（文本渲染/编码问题），
     *    但 a11y 树对卡片有完整 content-desc（如「阅读以赚取, 已赚取 6 积分(需要 30 积分)」），
     *    直点卡片中心即可进入信息流，阅读计时从浏览开始。
     * 2) OCR 定位兜底（分身跨 user 隔离、a11y 读不到时的唯一通道）：
     *    截屏 → 定位「阅读以赚取」行（校验在可视区域内）→ 点行左半区；
     *    绝不点固定坐标（滑动后坐标偏移会点到广告）。
     */
    private suspend fun clickReadEntryViaOcr(): Boolean {
        // 1) 无障碍直点（仅在确认必应前台时使用，避免误点脚本自身悬浮胶囊文案；
        //    且只点完整落在可视区域内的卡片，防止点到屏幕外被裁剪的 WebView 节点）
        if (isOnBing()) {
            val viaA11y = ui.clickTextVisible("阅读以赚取")
            if (viaA11y) {
                LogT.read("无障碍树点击「阅读以赚取」成功")
                return settleAfterEntryClick()
            }
        }
        // 2) OCR 兜底
        val bmp = ui.captureScreen() ?: return false
        val snap = classifier.classify(bmp, ui.screenTexts())
        val hit = classifier.findLineByKeyword(snap.lines, "阅读以赚取")
        if (hit == null) {
            LogT.read("OCR 未识别到「阅读以赚取」行")
            return false
        }
        LogT.read("OCR 定位阅读入口: 「${hit.text.take(30)}」bounds=${hit.bounds}")
        // 先确认不在广告区，广告区只读不点
        if (classifier.isPointInAdZone(hit.bounds.centerX(), hit.bounds.centerY(), snap)) {
            LogT.read("阅读入口落在广告区，跳过本次点击")
            return false
        }
        if (hit.bounds.isEmpty || hit.bounds.height() <= 0) return false
        // 命中行必须在可视区域内：被裁切到屏幕外/贴边显示说明还没滚到位，交给上层继续滚动
        val screenH = snap.screenH.takeIf { it > 0 } ?: ui.visibleHeight()
        val screenW = snap.screenW.takeIf { it > 0 } ?: ui.visibleWidth()
        if (hit.bounds.top >= screenH || hit.bounds.top < 0 ||
            hit.bounds.bottom > screenH || hit.bounds.right > screenW || hit.bounds.left < 0
        ) {
            LogT.read("阅读入口不在可视区域 bounds=${hit.bounds} scr=${screenW}x$screenH，继续滚动")
            return false
        }
        // 卡片就是一整块可点击区域，点击卡片中心比偏左更稳：
        // 偏左容易点到卡片 padding 空隙 → 触发不到跳转，误点下面信息流跳去百度主页，零计分
        val x = hit.bounds.centerX()
        val y = hit.bounds.centerY()
        // 点击前再确认一次还在必应（防止上一轮误操作已顶出）
        if (!isOnBing()) {
            LogT.read("点击阅读入口前已离开必应，跳过")
            return false
        }
        if (!ui.click(x, hit.bounds.centerY())) return false
        LogT.read("OCR 点击阅读入口成功，结算进入状态")
        return settleAfterEntryClick()
    }

    /**
     * 点击「阅读以赚取」后的进入结算：必须确认真正进入了必应新闻流才算成功。
     *
     * 判据：前台回到必应 且 屏幕已离开积分页（积分卡片标记「今日积分/搜索以赚取/阅读以赚取」
     * 消失）——若点击落在卡片 padding 空隙上（历史 bug），点完仍在积分页，这里会等待超时
     * 返回 false，外层重试滚动后再点，绝不误判"已进入阅读"。
     *
     * 同时处理 ColorOS 分身选择器：主空间+分身必应并存时，点击会弹选择器，必须自动选目标实例。
     */
    private suspend fun settleAfterEntryClick(): Boolean {
        val deadline = System.currentTimeMillis() + 6000
        val rewardMarkers = listOf("今日积分", "搜索以赚取", "阅读以赚取")
        // 连续稳定帧数：只有「必应前台 + 无积分标记」持续达标才判进入成功，
        // 防止点击后选择器/广告页瞬间弹出时误判
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            val fp = ui.foregroundPackage()
            if (fp.contains("bing")) {
                val texts = ui.screenTexts()
                val outOfRewards = texts.isEmpty() || texts.none { t -> rewardMarkers.any { t.contains(it) } }
                if (outOfRewards) {
                    stable++
                    if (stable >= 2) {
                        LogT.read("进入结算成功（离开积分页）")
                        return true
                    }
                } else {
                    stable = 0
                }
            } else {
                stable = 0
            }
            if (fp.contains("multiapp")) {
                LogT.read("检测到分身选择器，自动选择目标实例")
                val ok = ui.resolveChooser()
                LogT.read("选择器处理完成 ok=$ok")
                idelay(1200)
            } else {
                idelay(300)
            }
        }
        LogT.read("点击阅读入口后未稳定离开积分页（前台=${ui.foregroundPackage()}），结算失败")
        return false
    }

    /**
     * 每日活动（Daily Set）自动化。
     *
     * 手机端每天的 3 个活动是「搜索型」任务：每个活动卡片尾部有一个蓝色的
     * 「赚取 N 积分」按钮，点击后自动打开该主题的 Bing 搜索页，停留数秒即计分完成。
     *
     * 流程：反复下拉积分页扫描未完成的「赚取」按钮（OCR 行定位 + 无障碍兜底）
     * → 点击（并确认真正跳转，未跳转则换下一个）→ 停留 [DAILY_DWELL_MS] → 返回积分页
     * → 重复直到 3 个完成或整页都找不到新按钮。
     *
     * 注意：每日活动卡片在积分页下方，必须下拉多屏才能全部露出；
     * 积分页是 WebView，优先用 OCR 行（含坐标）定位按钮。
     */
    private suspend fun doDailySet(): Int {
        // 主/分身共用「标题无关」网格方案：每日活动卡布局两端相同（1272x2772），
        // 标题每天变不依赖具体标题，以「点击后离开积分页标记」判定命中。
        return doDailySetMain()
    }

    /**
     * 每日活动（标题无关，主/分身共用）：
     * 现实证：活动卡点击后跳转必应浏览器（如「Concerts near me」搜索页），停留即 +10 计分。
     * 每日活动卡标题每天变化，不能依赖具体标题 → 用 OCR 取视口内候选文本行（含坐标），
     * 逐个点行右端内侧，以「点击后离开积分页标记」判定命中；每命中一张计一次完成。
     */
    private suspend fun doDailySetMain(): Int {
        gate()
        var completed = 0
        note("查找每日活动…")
        dumpRewardsPage()

        // 回顶建立确定起点
        repeat(3) { ui.scrollUp(); idelay(600) }
        idelay(800)

        // 连续点击未命中（未跳转）计数：超过阈值视为「已领完/无卡可领」，提前退出避免无意义重试
        var consecutiveMiss = 0
        val MAX_CONSECUTIVE_MISS = 6

        outer@ for (st in 1..3) {
            if (completed >= DAILY_SET_MAX || !isOnBing()) break
            ui.scrollDown()
            idelay(900)
            idelay(1000)

            val bmp = ui.captureScreen() ?: continue
            val lines = classifier.recognizeLines(bmp)
            // 候选行：中等高度、落在活动卡区，且非任务/日历/积分文案行（标题无关）
            val cands = lines.filter { l ->
                val t = l.text
                l.bounds.height() in 30..90 &&
                    l.bounds.top in 600..2350 && l.bounds.bottom <= 2650 &&
                    !t.contains("赚取") && !t.contains("Day ") && !t.contains("积分") &&
                    !t.contains("兑换") && !t.contains("签入") && !t.contains("小时")
            }
            LogT.daily("活动卡候选行(${cands.size}): ${cands.take(4).map { it.text.replace('\n', ' ').take(16) }}")
            for (l in cands) {
                if (completed >= DAILY_SET_MAX || !isOnBing()) break@outer
                if (consecutiveMiss >= MAX_CONSECUTIVE_MISS) {
                    LogT.daily("连续 $consecutiveMiss 次未命中，视为已领完，提前退出")
                    break@outer
                }
                val x = minOf(l.bounds.right - l.bounds.width() / 4, l.bounds.right - 20)
                    .coerceIn(l.bounds.left, l.bounds.right - 10)
                val y = l.bounds.centerY()
                if (isPointInAdZone(x, y)) continue
                note("每日活动 ${completed + 1}/$DAILY_SET_MAX：试点行「${l.text.take(18)}」@($x,$y)")
                if (clickDailyCard(x, y)) {
                    consecutiveMiss = 0
                    idelay(1500)
                    delay(dailySetMs)
                    completed++
                    note("每日活动完成（$completed/$DAILY_SET_MAX）")
                    var backCount = 0
                    while (backCount < 4 && !isOnRewardsPage() && ui.foregroundPackage().contains("bing")) {
                        backCount++
                        ui.back()
                        idelay(800)
                    }
                    idelay(1500)
                } else {
                    consecutiveMiss++
                }
            }
        }
        note(if (completed >= DAILY_SET_MAX) "每日活动完成（3/3）" else "每日活动完成（$completed/$DAILY_SET_MAX）")
        return completed
    }

    /** 点击活动卡固定点位并确认跳转：点击后积分页标记（今日积分/每日活动/签入）消失即成功 */
    private suspend fun clickDailyCard(x: Int, y: Int): Boolean {
        repeat(3) {
            if (!isOnBing()) return false
            ui.click(x, y)
            idelay(2000)
            val markers = ui.screenTexts().any {
                it.contains("今日积分") || it.contains("每日活动") || it.contains("签入")
            }
            if (!markers) return true
            LogT.daily("点击后仍在积分页（第${it + 1}次）")
        }
        return false
    }

    /**
     * 分身每日活动：导航到积分页 → 逐次尝试查找卡片 → 点 +10 → 等待 → 重导航回积分页。
     *
     * 策略：
     * - 不盲目滑动，逐次查找卡片，找不到才微调位置
     * - 无障碍树优先，OCR 兜底，最后用硬编码相对坐标
     */
    private suspend fun doDailySetClone(): Int {
        gate()
        var completed = 0
        val clickedTitles = mutableSetOf<String>()

        note("分身每日活动：定位 +10 按钮…")

        // 1) 先确保在积分页
        ensureOnRewardsPage()

        var i = 0
        while (i < DAILY_SET_MAX) {
            gate()

            // 2) 逐次尝试查找卡片，每次找不到就微调位置
            val btn = findCloneEarnButtonWithRetry(clickedTitles)
            if (btn == null) {
                note(if (i == 0) "分身：未找到每日活动按钮" else "每日活动已完成（$completed）")
                return completed
            }

            note("每日活动 ${i + 1}/$DAILY_SET_MAX：点击「${btn.title}」(${btn.clickX}, ${btn.clickY})")
            LogT.daily("分身+10 #${i+1}: title=「${btn.title}」 bounds=${btn.bounds}")

            // 点击前广告区校验：命中广告直接跳过该项，避免误点广告
            if (isPointInAdZone(btn.clickX, btn.clickY)) {
                LogT.daily("+10 按钮落在广告区，跳过该项")
                clickedTitles += btn.title
                i++
                continue
            }

            ui.click(btn.clickX, btn.clickY)
            idelay(1500)
            delay(dailySetMs)

            clickedTitles += btn.title
            completed++
            note("每日活动完成（$completed/$DAILY_SET_MAX）")

            // 3) 重新导航回积分页
            navigateBackToRewards()
            i++
        }
        return completed
    }

    /** 确保在积分页（只导航，不滑动） */
    private suspend fun ensureOnRewardsPage() {
        if (isOnRewardsPage()) {
            LogT.daily("已在积分页")
            return
        }
        LogT.daily("不在积分页，导航…")
        val tab = retry(2, 600) { ui.clickText(APP_TAB) || ui.click(APP_TAB_X, APP_TAB_Y) }
        idelay(1500)
        val entry = retry(3, 600) { ui.clickText(REWARDS_ENTRY) || ui.click(REWARDS_ITEM_X, REWARDS_ITEM_Y) }
        idelay(3000)
    }

    /** 逐次尝试查找卡片：找不到就微调位置再试 */
    private suspend fun findCloneEarnButtonWithRetry(clickedTitles: Set<String>): CloneEarnButton? {
        // 第一次：直接查找
        var btn = findCloneEarnButton(clickedTitles)
        if (btn != null) return btn

        // 第二次：下滑一点再找
        LogT.daily("第一次没找到，下滑一点…")
        ui.scrollDown()
        idelay(800)
        btn = findCloneEarnButton(clickedTitles)
        if (btn != null) return btn

        // 第三次：再下滑一点
        LogT.daily("第二次没找到，再下滑一点…")
        ui.scrollDown()
        idelay(800)
        btn = findCloneEarnButton(clickedTitles)
        if (btn != null) return btn

        // 第四次：用硬编码相对坐标兜底
        LogT.daily("无障碍树+OCR 都找不到，尝试硬编码坐标…")
        return findCloneEarnButtonByHardcodedCoord(clickedTitles)
    }

    /** 硬编码相对坐标兜底：动态提取卡片标题 + 按位置比例定位，不再写死标题 */
    private suspend fun findCloneEarnButtonByHardcodedCoord(clickedTitles: Set<String>): CloneEarnButton? {
        val abi = ui as? AccessibilityBingUi ?: return null
        val m = abi.service.resources.displayMetrics
        val sw = m.widthPixels
        val sh = m.heightPixels

        // 1) 先从无障碍树动态提取所有「赚取」卡片标题和位置
        val allEarn = ui.findAllDescWithText("赚取")
            .filter { (_, r) -> !r.isEmpty }
            .sortedBy { (_, r) -> r.top }

        LogT.daily("硬编码兜底：无障碍树找到 ${allEarn.size} 个卡片")
        val remaining = allEarn.filter { (text, _) ->
            val title = text.substringBefore(",").trim()
            title.isNotEmpty() && title !in clickedTitles
        }

        if (remaining.isNotEmpty()) {
            val (text, rect) = remaining.first()
            val title = text.substringBefore(",").trim()
            val clickX = (rect.right - 60).coerceIn(rect.left + 20, rect.right)
            val clickY = rect.centerY()
            LogT.daily("硬编码动态: 「${title}」 → ($clickX, $clickY)")
            return CloneEarnButton(title, clickX, clickY, rect)
        }

        // 2) 无障碍树读不到 → OCR 动态提取 +10 行
        val bmp = ui.captureScreen()
        if (bmp != null) {
            val lines = classifier.recognizeLines(bmp)
            val ocrHit = lines.firstOrNull { l ->
                (l.text.contains("+10") || l.text.contains("+ 10")) &&
                    clickedTitles.none { t -> l.text.contains(t.substringBefore(",").trim()) }
            }
            if (ocrHit != null) {
                val title = ocrHit.text.take(60)
                val clickX = (ocrHit.bounds.right - 30).coerceIn(ocrHit.bounds.left + 10, ocrHit.bounds.right)
                val clickY = ocrHit.bounds.centerY()
                LogT.daily("OCR 动态: 「${title}」 → ($clickX, $clickY)")
                return CloneEarnButton(title, clickX, clickY, ocrHit.bounds)
            }
        }

        // 3) 终极兜底：按位置比例（22%/42%/62%）点击，不用标题追踪
        val posList = listOf(
            "card_1_${System.currentTimeMillis()}" to sh * 0.22f,
            "card_2_${System.currentTimeMillis()}" to sh * 0.42f,
            "card_3_${System.currentTimeMillis()}" to sh * 0.62f,
        )
        val remainingPos = posList.filter { (title, _) -> title !in clickedTitles }
        if (remainingPos.isEmpty()) {
            LogT.daily("硬编码兜底：所有卡片位置都已点击")
            return null
        }
        val (title, yRatio) = remainingPos.first()
        val clickX = (sw * 0.82f).toInt()
        val clickY = yRatio.toInt()
        LogT.daily("位置兜底: 「${title}」 → ($clickX, $clickY)")
        return CloneEarnButton(title, clickX, clickY, null)
    }

    /** 找分身未点击过的「赚取」按钮：用卡片标题追踪，OCR 兜底 */
    private suspend fun findCloneEarnButton(clickedTitles: Set<String>): CloneEarnButton? {
        // 1) 无障碍树：拿全部「赚取」卡片，提取标题（content-desc 首段），跳过已点击
        val allEarn = ui.findAllDescWithText("赚取")
            .filter { (_, r) -> !r.isEmpty }
            .sortedBy { (_, r) -> r.top }

        LogT.daily("无障碍找到 ${allEarn.size} 个赚取卡片:")
        allEarn.forEachIndexed { idx, (text, r) ->
            val title = text.substringBefore(",").trim()
            LogT.daily("  [$idx] title=「${title}」 top=${r.top}")
        }

        val target = allEarn.firstOrNull { (text, _) ->
            val title = text.substringBefore(",").trim()
            title.isNotEmpty() && title !in clickedTitles
        }

        if (target != null) {
            val (text, rect) = target
            val title = text.substringBefore(",").trim()
            val clickX = (rect.right - 60).coerceIn(rect.left + 20, rect.right)
            val clickY = rect.centerY()
            LogT.daily("选中卡片: 「${title}」 → 点击 ($clickX, $clickY)")
            return CloneEarnButton(title, clickX, clickY, rect)
        }

        // 2) OCR 兜底
        val bmp = ui.captureScreen() ?: return null
        val lines = classifier.recognizeLines(bmp)

        LogT.daily("OCR 全部 ${lines.size} 行:")
        lines.forEachIndexed { idx, l ->
            LogT.daily("  [$idx] \"${l.text.replace('\n', ' ').take(80)}\" bounds=${l.bounds}")
        }

        val hit = lines.firstOrNull { l ->
            (l.text.contains("+10") || l.text.contains("+ 10")) &&
                clickedTitles.none { t -> l.text.contains(t) }
        }
        if (hit == null) {
            LogT.daily("OCR 未找到未点击的 +10 行")
            return null
        }

        val clickX = (hit.bounds.right - 30).coerceIn(hit.bounds.left + 10, hit.bounds.right)
        val clickY = hit.bounds.centerY()
        LogT.daily("OCR 定位 +10: 「${hit.text}」 → ($clickX, $clickY)")
        return CloneEarnButton(hit.text, clickX, clickY, hit.bounds)
    }

    /** 重新导航回积分页（搜索页 back 回不到积分页，必须走应用 tab → Rewards） */
    private suspend fun navigateBackToRewards() {
        repeat(2) {
            ui.back()
            idelay(800)
        }
        val tab = retry(2, 600) { ui.clickText(APP_TAB) || ui.click(APP_TAB_X, APP_TAB_Y) }
        idelay(1500)
        val entry = retry(3, 600) { ui.clickText(REWARDS_ENTRY) || ui.click(REWARDS_ITEM_X, REWARDS_ITEM_Y) }
        idelay(3000)
        LogT.daily("已导航回积分页")
    }

    private data class CloneEarnButton(
        val title: String,
        val clickX: Int,
        val clickY: Int,
        val bounds: Rect? = null
    )

    private data class EarnButton(val text: String, val bounds: Rect?)

    /** 已尝试过的「赚取」按钮文本，避免死循环重复点击同一个 */
    private val doneEarn = mutableSetOf<String>()

    /** 滚动多屏扫描「赚取 N 积分」按钮：每屏 OCR 行定位优先，无障碍文本兜底 */
    private suspend fun scanForEarnButton(maxScrolls: Int): EarnButton? {
        repeat(maxScrolls + 1) { s ->
            // 1) 无障碍通道（快）：每日活动卡 desc 是整卡长文本（如「…, 赚取 10 积分」），
            //    长度上限放宽到整卡级别，避免把活动卡当噪声过滤掉
            val t = ui.screenTexts().firstOrNull {
                it.contains("赚取") && !it.contains("已赚取") && it.length <= 150 &&
                    doneEarn.none { d -> it.contains(d) || d.contains(it) }
            }
            if (t != null) {
                doneEarn += t
                LogT.daily("无障碍定位: 「${t.take(40)}」")
                return EarnButton(t, null)
            }
            // 2) OCR 行定位（分身 WebView 主通道）
            val bmp = ui.captureScreen()
            if (bmp != null) {
                val lines = classifier.recognizeLines(bmp)
                val hit = lines.firstOrNull { l ->
                    l.text.contains("赚取") && !l.text.contains("已赚取") &&
                        doneEarn.none { d -> l.text.contains(d) || d.contains(l.text) }
                }
                if (hit != null) {
                    doneEarn += hit.text
                    LogT.daily("OCR[$s]定位: 「${hit.text.replace('\n', ' ').take(60)}」")
                    return EarnButton(hit.text, hit.bounds)
                }
            }
            // 当前屏没有 → 下拉一屏再扫
            if (s < maxScrolls) {
                ui.scrollDown()
                idelay(800)
            }
        }
        LogT.daily("滚动 $maxScrolls 屏仍未发现未被尝试的「赚取」按钮")
        return null
    }

    /** 点击「赚取」按钮并确认真正跳转（离开积分页标记）；最多两次，未跳转返回 false */
    private suspend fun clickEarnAndWaitJump(btn: EarnButton): Boolean {
        repeat(2) { attempt ->
            val clicked = clickEarnButton(btn)
            // 跳转判定：积分页标记（今日积分/每日活动/签到）消失即认为已进入搜索页
            val markers = ui.screenTexts().any { it.contains("今日积分") || it.contains("每日活动") || it.contains("签入") }
            if (!markers) return true
            LogT.daily("点击后仍在积分页（第${attempt + 1}次），重试或换下一项")
        }
        return false
    }

    /** 点击「赚取」按钮：活动卡 a11y bounds 常为 0 高/贴底（WebView 虚拟坐标），
     *  需滚动把卡片带入视口后取有效坐标，再点行右端「赚取」按钮区。 */
    private suspend fun clickEarnButton(btn: EarnButton): Boolean {
        var b = btn.bounds
        var scrolls = 0
        val screenH = ui.visibleHeight()
        while ((b == null || b.isEmpty || b.height() <= 10 || b.bottom >= screenH || b.top < 0) && scrolls < 5) {
            scrolls++
            // 1) a11y 有效坐标：desc 含「赚取」且非「已赚取」、完整落在视口内的节点
            //    （desc 实际含全角空格等变体，「赚取 10 积分」精确子串常匹配不上 → 用「赚取」宽匹配）
            val earnNodes = ui.findAllDescWithText("赚取").filter { (t, r) ->
                !t.contains("已赚取") && r.height() > 10 && r.bottom <= screenH && r.top >= 0
            }
            LogT.daily("赚取卡节点(${earnNodes.size}): ${earnNodes.take(3).map { (t, r) -> "${t.take(16)}→$r" }}")
            b = earnNodes.firstOrNull()?.second
            if (b != null) {
                LogT.daily("a11y 定位赚取卡(scroll$scrolls): $b")
                break
            }
            // 2) 滚动一屏让活动卡进入视口
            if (scrolls <= 4) {
                ui.scrollDown()
                idelay(900)
            }
            // 3) OCR 行定位：活动卡英文长行 OCR 常吞掉「赚取」，但英文标题头（Upcoming/Hobart/…）
            //    识别稳定 → 用标题头匹配；中文「赚取」行命中也可
            val bmp = ui.captureScreen() ?: continue
            val lines = classifier.recognizeLines(bmp)
            val head = btn.text.take(20).trim()
            val hit = lines.firstOrNull { l ->
                l.bounds.height() > 0 && (
                    (l.text.contains("赚取") && !l.text.contains("已赚取")) ||
                        l.text.startsWith(head, ignoreCase = true)
                    )
            }
            if (hit != null) {
                b = hit.bounds
                LogT.daily("OCR 定位赚取卡(scroll$scrolls): 「${hit.text.replace('\n', ' ').take(40)}」bounds=$b")
                break
            }
            LogT.daily("OCR 中段(scroll$scrolls): ${lines.slice(12..minOf(lines.size - 1, 36)).joinToString(" | ") { it.text.replace('\n', ' ').take(22) }}")
        }
        if (b != null && !b.isEmpty && b.height() > 10) {
            // 「赚取」按钮在卡片行尾：点行右端内侧（right - 宽度的 1/4），避开页面边缘
            val x = minOf(b.right - b.width() / 4, b.right - 20).coerceIn(b.left, b.right - 10)
            if (isPointInAdZone(x, b.centerY())) {
                LogT.daily("「赚取」坐标落在广告区，跳过")
                return false
            }
            LogT.daily("坐标点击($x, ${b.centerY()}) 卡宽=${b.width()}")
            return ui.click(x, b.centerY())
        }
        return retry(2, 600) { ui.clickText(btn.text) }
    }

    /**
     * 点击前广告区校验：截屏经统一识别核心提取 adZones，目标点命中即拦截。
     * 每次截屏+OCR 有开销，只在「将要执行坐标点击」的关键路径调用。
     */
    private suspend fun isPointInAdZone(x: Int, y: Int): Boolean {
        val bmp = ui.captureScreen() ?: return false
        val snap = classifier.classify(bmp, ui.screenTexts())
        if (snap.adZones.isEmpty()) return false
        val hit = classifier.isPointInAdZone(x, y, snap)
        if (hit) LogT.daily("广告区拦截: ($x,$y) zones=${snap.adZones}")
        return hit
    }

    private object LogT {
        fun checkin(msg: String) {
            runCatching { android.util.Log.i("RippleReward", "签到: $msg") }
        }

        fun read(msg: String) {
            runCatching { android.util.Log.i("RippleReward", "阅读: $msg") }
        }

        fun scan(msg: String) {
            runCatching { android.util.Log.i("RippleReward", "检测: $msg") }
        }

        fun daily(msg: String) {
            runCatching { android.util.Log.i("RippleReward", "每日活动: $msg") }
        }

        fun note(msg: String) {
            runCatching { android.util.Log.i("RippleReward", "进度: $msg") }
        }
    }

    companion object {
        /** 必应工具页特征词（与新闻正文几乎不可能撞词，命中即快速撤回，不耗阅读轮次） */
        private val TOOL_PAGE_MARKERS = listOf(
            "隐私声明", "InPrivate", "设置中心", "帮助与反馈", "关于此搜索", "必应浏览设置",
        )
        /** 每日活动卡英文标题头（OCR 对其识别稳定，用于定位活动卡行） */
        private val dailyCardHeads = listOf("Upcoming", "Hobart", "Cheesemaking")

        // —— 界面文案常量 ——
        const val APP_TAB = "应用"            // Bing 底部「应用」页签（原生）
        const val REWARDS_ENTRY = "Rewards"   // 应用页中的 Rewards 入口（原生）
        const val CHECK_IN_MARKER = "今日积分" // Rewards 积分页标记（原生）
        /** 阅读批次冗余：每轮在缺口基础上多读的篇数，吸收积分到账延迟，减少回积分页次数 */
        const val READ_BATCH_ABSORB = 4
        /** 阅读日上限篇数（30 分 ÷ 3 分/篇 = 10，加冗余封顶 20 防越界） */
        const val READ_MAX_OPS = 20
        // 真机标定坐标（1272x2772）：
        const val SEARCH_BAR_ID = "sa_template_header_address_bar" // 必应顶部原生搜索栏 resource-id 子串
        // 搜索结果页（BrowserActivity）顶部地址栏 resource-id 子串，点击回到搜索页继续下一词
        const val RESULT_ADDRESS_BAR_ID = "iab_header_address_bar"
        // 搜索栏/地址栏真机标定坐标（1272x2772，容器中心），供 resource-id 兜底
        val SEARCH_BAR_X = 559
        val SEARCH_BAR_Y = 225
        val RESULT_ADDR_X = 566
        val RESULT_ADDR_Y = 253
        val APP_TAB_X = 1076
        val APP_TAB_Y = 2618
        val REWARDS_ITEM_X = 781
        val REWARDS_ITEM_Y = 1260
        // 阅读赚取：Rewards 页内「阅读以赚取」入口（OCR 文本定位，不再用固定坐标防点广告）
        const val READ_ENTRY_TEXT = "阅读以赚取"
        const val READ_MS = 12_000L
        const val SEARCH_GAP_MS = 3_200L

        // 搜索已达上限的识别关键词
        val LIMIT_MARKERS = listOf("已达上限", "No more", "达到每日")

        // 每日活动（Daily Set）：每天 3 个「赚取 N 积分」按钮，点击后自动搜索该主题
        // 无人接管：离开必应后的自动拉回策略（15 次 × 4s ≈ 60s 熔断窗口）
        const val BING_RECOVER_ATTEMPTS = 15
        const val BING_RECOVER_GAP_MS = 4_000L

        const val DAILY_SET_MAX = 3
        const val DAILY_DWELL_MS = 15_000L  // 每日活动点击后停留计分时长（秒），可被 ScriptParams.dailySetSeconds 覆盖
        const val SCROLL_ATTEMPTS = 4      // 每日活动多屏下拉查找最大滚动次数

        // 少样本安全搜索词表；Task 2 SearchKeywords（assets 词库）接入后替换
        val DEFAULT_KEYWORDS = listOf(
            "mountain", "river", "library", "science",
            "football", "apple", "coffee", "train",
        )
    }
}