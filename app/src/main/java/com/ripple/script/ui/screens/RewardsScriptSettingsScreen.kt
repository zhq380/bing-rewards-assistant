package com.ripple.script.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripple.script.data.RewardsStore
import com.ripple.script.data.ScriptParams
import com.ripple.script.rewards.BingInstanceResolver
import com.ripple.script.ui.theme.BadgeBlue
import com.ripple.script.ui.theme.BadgeGreen
import com.ripple.script.ui.theme.BadgeOrange
import com.ripple.script.ui.theme.BadgePurple
import com.ripple.script.ui.theme.GradientHeroBlue
import com.ripple.script.ui.theme.GradientHeroGreen
import com.ripple.script.ui.theme.GradientHeroOrange
import com.ripple.script.ui.theme.GradientHeroPurple
import com.ripple.script.ui.theme.SuccessGreen
import com.ripple.script.ui.theme.bounceClick

/**
 * 单个脚本（主应用 / 分身应用）的细节设置页 · 自适应响应式风格。
 *
 * 分组：
 *   1. Hero 概览卡（实例信息 + 模块开关总览 · 进度环）
 *   2. 签到（checkIn 组）
 *   3. 搜索（search 组）
 *   4. 阅读（read 组）
 *   5. 每日活动（daily 组）
 *   6. 稳定性 / 高级参数（看门狗 · 渲染等待 · 断点续跑）
 *   7. 必应入口（自动跳转 · 实例选择）
 *   8. 底部保存胶囊按钮（渐变背景 · 自适应大按钮）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardsScriptSettingsScreen(
    store: RewardsStore,
    isClone: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val initial = remember { store.loadScript(isClone) }

    // ===== 可编辑状态（按 ScriptParams 字段拆分）=====
    // —— 基础计数 ——
    var searchCount by remember { mutableStateOf(initial.searchCount) }
    var readCount by remember { mutableStateOf(initial.readCount) }
    var readSeconds by remember { mutableStateOf(initial.readSeconds) }
    var searchGapSeconds by remember { mutableStateOf(initial.searchGapSeconds) }
    var dailySetSeconds by remember { mutableStateOf(initial.dailySetSeconds) }

    // —— 模块总开关 ——
    var checkInEnabled by remember { mutableStateOf(initial.checkInEnabled) }
    var searchEnabled by remember { mutableStateOf(initial.searchEnabled) }
    var readEnabled by remember { mutableStateOf(initial.readEnabled) }
    var dailySetEnabled by remember { mutableStateOf(initial.dailySetEnabled) }
    var autoCount by remember { mutableStateOf(initial.autoCount) }

    // —— 签到（checkin 组）——
    var checkinCoinOnly by remember { mutableStateOf(initial.checkinCoinOnly) }
    var checkinPreferOcr by remember { mutableStateOf(initial.checkinPreferOcr) }
    var checkinSequentialFallback by remember { mutableStateOf(initial.checkinSequentialFallback) }
    var checkinVerifySeconds by remember { mutableStateOf(initial.checkinVerifySeconds) }

    // —— 搜索（search 组）——
    var searchUseRandomWords by remember { mutableStateOf(initial.searchUseRandomWords) }
    var searchMixEnglish by remember { mutableStateOf(initial.searchMixEnglish) }
    var searchBackoffCount by remember { mutableStateOf(initial.searchBackoffCount) }

    // —— 阅读（read 组）——
    var readContinuousBatch by remember { mutableStateOf(initial.readContinuousBatch) }
    var readWatchdogSeconds by remember { mutableStateOf(initial.readWatchdogSeconds) }
    var readReturnRewardsAfter by remember { mutableStateOf(initial.readReturnRewardsAfter) }
    var readAutoExtend by remember { mutableStateOf(initial.readAutoExtend) }

    // —— 每日活动（daily 组）——
    var dailyMissThreshold by remember { mutableStateOf(initial.dailyMissThreshold) }
    var dailyClickRetries by remember { mutableStateOf(initial.dailyClickRetries) }

    // —— 稳定性（watchdog 组）——
    var watchdogIdleSeconds by remember { mutableStateOf(initial.watchdogIdleSeconds) }
    var instancePickerTimeoutSec by remember { mutableStateOf(initial.instancePickerTimeoutSec) }
    var webViewRenderMs by remember { mutableStateOf(initial.webViewRenderMs) }
    var recoverAfterFrozenSec by remember { mutableStateOf(initial.recoverAfterFrozenSec) }
    var autoResumeOnBreak by remember { mutableStateOf(initial.autoResumeOnBreak) }
    var verboseLogcat by remember { mutableStateOf(initial.verboseLogcat) }

    // —— 必应入口 ——
    val instances = remember { BingInstanceResolver.list(context) }
    var autoLaunch by remember { mutableStateOf(initial.autoLaunch) }
    var bingSerial by remember { mutableStateOf(initial.bingTargetSerial) }

    // 模块启用数量（概览环用）
    val enabledModules = listOf(checkInEnabled, searchEnabled, readEnabled, dailySetEnabled).count { it }

    val collect: () -> ScriptParams = {
        ScriptParams(
            searchCount = searchCount,
            readCount = readCount,
            readSeconds = readSeconds,
            searchGapSeconds = searchGapSeconds,
            dailySetSeconds = dailySetSeconds,
            searchEnabled = searchEnabled,
            readEnabled = readEnabled,
            dailySetEnabled = dailySetEnabled,
            checkInEnabled = checkInEnabled,
            autoCount = autoCount,
            autoLaunch = autoLaunch,
            bingTargetSerial = bingSerial,
            checkinCoinOnly = checkinCoinOnly,
            checkinPreferOcr = checkinPreferOcr,
            checkinSequentialFallback = checkinSequentialFallback,
            checkinVerifySeconds = checkinVerifySeconds,
            searchUseRandomWords = searchUseRandomWords,
            searchMixEnglish = searchMixEnglish,
            searchBackoffCount = searchBackoffCount,
            readContinuousBatch = readContinuousBatch,
            readWatchdogSeconds = readWatchdogSeconds,
            readReturnRewardsAfter = readReturnRewardsAfter,
            readAutoExtend = readAutoExtend,
            dailyMissThreshold = dailyMissThreshold,
            dailyClickRetries = dailyClickRetries,
            watchdogIdleSeconds = watchdogIdleSeconds,
            instancePickerTimeoutSec = instancePickerTimeoutSec,
            webViewRenderMs = webViewRenderMs,
            recoverAfterFrozenSec = recoverAfterFrozenSec,
            autoResumeOnBreak = autoResumeOnBreak,
            verboseLogcat = verboseLogcat
        )
    }

    val save: () -> Unit = {
        store.saveScript(isClone, collect())
        Toast.makeText(context, "已保存到「${if (isClone) "分身" else "主应用"}」配置", Toast.LENGTH_SHORT).show()
    }

    val accent = if (isClone) GradientHeroPurple else GradientHeroBlue
    val accentLabel = if (isClone) "分身应用脚本" else "主应用脚本"
    val subLabel = if (isClone) "应用分身（user 999）" else "系统用户（user 0）"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // —— 1) Hero 概览卡 ——
        item {
            HeroSummaryCard(
                accent = accent,
                title = accentLabel,
                subtitle = subLabel,
                enabledModules = enabledModules,
                totalModules = 4,
                mode = if (autoCount) "智能模式" else "手动模式"
            )
        }

        // —— 2) 签到组 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.CalendarToday,
                title = "连续签到",
                subtitle = "硬币定位 · 积分到账验证",
                accent = BadgeOrange,
                accentBrush = GradientHeroOrange
            ) {
                PrimarySwitch(
                    title = "启用签入",
                    desc = "点击连续栏目下当日硬币按钮，到账 +5 积分",
                    checked = checkInEnabled,
                    onChecked = { checkInEnabled = it }
                )
                DividerLine()
                PrimarySwitch(
                    title = "仅硬币直点",
                    desc = "禁止回退到「签入」横幅，避免误点到兑换页",
                    checked = checkinCoinOnly,
                    onChecked = { checkinCoinOnly = it },
                    accent = BadgeOrange
                )
                PrimarySwitch(
                    title = "OCR 智能直跳",
                    desc = "通过分值数字识别「今日」硬币，从 Day 1 顺序点",
                    checked = checkinPreferOcr,
                    onChecked = { checkinPreferOcr = it },
                    accent = BadgeOrange
                )
                PrimarySwitch(
                    title = "直跳失败 · 顺序兜底",
                    desc = "无法识别时按 Day 顺序逐一点击直到命中",
                    checked = checkinSequentialFallback,
                    onChecked = { checkinSequentialFallback = it },
                    accent = BadgeOrange
                )
                DividerLine()
                IntStepper(
                    title = "签到积分验证窗口",
                    suffix = "秒",
                    hint = "点击硬币后等待到账的结算时长",
                    value = checkinVerifySeconds,
                    onValue = { checkinVerifySeconds = it.coerceIn(2, 30) },
                    range = 2..30,
                    step = 1
                )
            }
        }

        // —— 3) 搜索组 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.Search,
                title = "必应搜索",
                subtitle = "关键词 · 重试 · 间隔",
                accent = BadgeBlue,
                accentBrush = GradientHeroBlue
            ) {
                PrimarySwitch(
                    title = "启用搜索模块",
                    desc = "每条 3 积分（桌面端 + 移动端）",
                    checked = searchEnabled,
                    onChecked = { searchEnabled = it }
                )
                DividerLine()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IntFieldBlock(
                        modifier = Modifier.weight(1f),
                        title = "搜索次数",
                        value = searchCount,
                        suffix = "次",
                        onValue = { searchCount = it.coerceIn(1, 999) },
                        accent = BadgeBlue,
                        enabled = !autoCount && searchEnabled,
                        autoHint = if (autoCount) "智能模式下自动计算" else null
                    )
                    IntFieldBlock(
                        modifier = Modifier.weight(1f),
                        title = "搜索停留",
                        value = searchGapSeconds,
                        suffix = "秒",
                        onValue = { searchGapSeconds = it.coerceIn(1, 30) },
                        accent = BadgeBlue,
                        enabled = searchEnabled
                    )
                }
                DividerLine()
                PrimarySwitch(
                    title = "词库随机关键词",
                    desc = "从内置 1000 词（中英各半）抽取，避免重复搜索",
                    checked = searchUseRandomWords,
                    onChecked = { searchUseRandomWords = it },
                    accent = BadgeBlue
                )
                PrimarySwitch(
                    title = "中英混合",
                    desc = "关闭时只用中文；开启时与英文按 1:1 混合",
                    checked = searchMixEnglish,
                    onChecked = { searchMixEnglish = it },
                    accent = BadgeBlue
                )
                IntStepper(
                    title = "单条搜索重试次数",
                    suffix = "次",
                    hint = "跳转失败 / 页面不对时自动回退重试",
                    value = searchBackoffCount,
                    onValue = { searchBackoffCount = it.coerceIn(0, 6) },
                    range = 0..6,
                    step = 1
                )
                DividerLine()
                AutoCountToggle(autoCount, { autoCount = it }, enabled = searchEnabled || readEnabled)
            }
        }

        // —— 4) 阅读组 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.MenuBook,
                title = "文章阅读",
                subtitle = "连续阅读 · 看门狗 · 断点",
                accent = BadgePurple,
                accentBrush = GradientHeroPurple
            ) {
                PrimarySwitch(
                    title = "启用阅读模块",
                    desc = "阅读新闻流赚取积分，广告区会自动识别并跳过",
                    checked = readEnabled,
                    onChecked = { readEnabled = it }
                )
                DividerLine()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IntFieldBlock(
                        modifier = Modifier.weight(1f),
                        title = "阅读篇数",
                        value = readCount,
                        suffix = "篇",
                        onValue = { readCount = it.coerceIn(1, 80) },
                        accent = BadgePurple,
                        enabled = !autoCount && readEnabled,
                        autoHint = if (autoCount) "智能模式下自动计算" else null
                    )
                    IntFieldBlock(
                        modifier = Modifier.weight(1f),
                        title = "单篇停留",
                        value = readSeconds,
                        suffix = "秒",
                        onValue = { readSeconds = it.coerceIn(3, 60) },
                        accent = BadgePurple,
                        enabled = readEnabled
                    )
                }
                DividerLine()
                IntStepper(
                    title = "连续阅读批次（吸收延迟）",
                    suffix = "篇",
                    hint = "一次进入新闻流读满 N 篇才回积分页，减少来回跳转",
                    value = readContinuousBatch,
                    onValue = { readContinuousBatch = it.coerceIn(1, 12) },
                    range = 1..12,
                    step = 1
                )
                IntStepper(
                    title = "每 N 篇回一次积分页",
                    suffix = "篇",
                    hint = "0 表示全部读完再回积分页",
                    value = readReturnRewardsAfter,
                    onValue = { readReturnRewardsAfter = it.coerceIn(0, 20) },
                    range = 0..20,
                    step = 1
                )
                IntStepper(
                    title = "单篇看门狗超时",
                    suffix = "秒",
                    hint = "长时间未进入文章页时触发恢复机制",
                    value = readWatchdogSeconds,
                    onValue = { readWatchdogSeconds = it.coerceIn(15, 240) },
                    range = 15..240,
                    step = 5
                )
                PrimarySwitch(
                    title = "计分延迟 · 自动续读",
                    desc = "若读完仍未满额，自动追加阅读以吸收延迟到账",
                    checked = readAutoExtend,
                    onChecked = { readAutoExtend = it },
                    accent = BadgePurple
                )
            }
        }

        // —— 5) 每日活动组 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.CopyAll,
                title = "每日活动卡",
                subtitle = "定位 · 重试 · 未命中提前退出",
                accent = BadgeGreen,
                accentBrush = GradientHeroGreen
            ) {
                PrimarySwitch(
                    title = "启用每日活动",
                    desc = "点击积分页「赚取 +10」活动卡，每张 +10 积分",
                    checked = dailySetEnabled,
                    onChecked = { dailySetEnabled = it }
                )
                DividerLine()
                IntStepper(
                    title = "单张卡停留计分",
                    suffix = "秒",
                    hint = "分身 WebView 场景建议 ≥ 15s 确保计分",
                    value = dailySetSeconds,
                    onValue = { dailySetSeconds = it.coerceIn(5, 30) },
                    range = 5..30,
                    step = 1
                )
                IntStepper(
                    title = "连续未命中提前退出阈值",
                    suffix = "次",
                    hint = "已领完活动卡时避免长时间无效重试",
                    value = dailyMissThreshold,
                    onValue = { dailyMissThreshold = it.coerceIn(1, 20) },
                    range = 1..20,
                    step = 1
                )
                IntStepper(
                    title = "单张卡点击重试次数",
                    suffix = "次",
                    hint = "单张活动卡没反应时最多重复点击的次数",
                    value = dailyClickRetries,
                    onValue = { dailyClickRetries = it.coerceIn(1, 8) },
                    range = 1..8,
                    step = 1
                )
            }
        }

        // —— 6) 稳定性 / 高级参数组 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.Sensors,
                title = "稳定性 · 看门狗",
                subtitle = "自愈 · 渲染等待 · 断点续跑",
                accent = BadgeOrange,
                accentBrush = GradientHeroOrange
            ) {
                IntStepper(
                    title = "无活动强制恢复",
                    suffix = "秒",
                    hint = "超过阈值未产生阶段日志时，看门狗自动恢复前台",
                    value = watchdogIdleSeconds,
                    onValue = { watchdogIdleSeconds = it.coerceIn(20, 240) },
                    range = 20..240,
                    step = 5
                )
                IntStepper(
                    title = "分身选择器等待",
                    suffix = "秒",
                    hint = "最多等待分身选择器，避免无限卡死",
                    value = instancePickerTimeoutSec,
                    onValue = { instancePickerTimeoutSec = it.coerceIn(3, 20) },
                    range = 3..20,
                    step = 1
                )
                IntStepper(
                    title = "WebView 渲染等待",
                    suffix = "毫秒",
                    hint = "积分页 Day 标签 / 活动卡异步渲染最大等待",
                    value = webViewRenderMs.toInt(),
                    onValue = { webViewRenderMs = it.coerceIn(500, 8000).toLong() },
                    range = 500..8000,
                    step = 250
                )
                IntStepper(
                    title = "掉桌面强制重启",
                    suffix = "秒",
                    hint = "回退/切换后 N 秒不在必应，就强制启动必应",
                    value = recoverAfterFrozenSec,
                    onValue = { recoverAfterFrozenSec = it.coerceIn(3, 60) },
                    range = 3..60,
                    step = 1
                )
                DividerLine()
                PrimarySwitch(
                    title = "中断后断点续跑",
                    desc = "进程 / 脚本中断后，重开时从已完成的部分继续",
                    checked = autoResumeOnBreak,
                    onChecked = { autoResumeOnBreak = it },
                    accent = BadgeOrange
                )
                PrimarySwitch(
                    title = "详细 Logcat 日志",
                    desc = "开启后输出更详细的阶段、节点和判定，便于调试",
                    checked = verboseLogcat,
                    onChecked = { verboseLogcat = it },
                    accent = BadgeOrange
                )
            }
        }

        // —— 7) 必应入口 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.PlayArrow,
                title = "必应入口",
                subtitle = "自动跳转 · 目标实例",
                accent = BadgeBlue,
                accentBrush = GradientHeroBlue
            ) {
                PrimarySwitch(
                    title = "自动跳转必应",
                    desc = "关闭后脚本仅在必应已在前台时运行",
                    checked = autoLaunch,
                    onChecked = { autoLaunch = it },
                    accent = BadgeBlue
                )
                DividerLine()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = null,
                        tint = BadgeBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "跳转实例（识别到 ${instances.size} 个）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (instances.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(androidx.compose.ui.graphics.Color(0xFFFDECEA))
                            .padding(12.dp)
                    ) {
                        Text(
                            "未识别到必应实例，请确认已安装 Microsoft Bing（中国版）",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    instances.forEach { inst ->
                        InstanceRow(
                            label = inst.label,
                            serial = inst.serial,
                            selected = bingSerial == inst.serial,
                            onClick = { bingSerial = inst.serial }
                        )
                    }
                }
            }
        }

        // 底部留白
        item { Spacer(Modifier.height(6.dp)) }
    }

    // ===== 悬浮保存按钮（自适应底部大胶囊）=====
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(accent)
                .bounceClick(onClick = save),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.SettingsSuggest,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "保存「$accentLabel」设置",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

// ==============================================================
//  Hero 概览卡：渐变 + 进度环 + 模式徽章
// ==============================================================
@Composable
private fun HeroSummaryCard(
    accent: Brush,
    title: String,
    subtitle: String,
    enabledModules: Int,
    totalModules: Int,
    mode: String
) {
    val pct = if (totalModules == 0) 0f else (enabledModules.toFloat() / totalModules).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(accent)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingProgress(size = 86.dp, progress = pct, accent = Color.White)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhitePill(Icons.Filled.AutoAwesome, mode)
                    WhitePill(
                        Icons.Filled.BugReport,
                        "$enabledModules / $totalModules 模块"
                    )
                }
            }
        }
    }
}

@Composable
private fun WhitePill(icon: ImageVector, text: String) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun RingProgress(size: Dp, progress: Float, accent: Color) {
    val density = LocalDensity.current
    val pxf = with(density) { size.toPx() }
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(),
        label = "ring-$size"
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = pxf
            val stroke = 11.dp.toPx()
            val r = (w - stroke) / 2f
            drawCircle(
                color = Color.White.copy(alpha = 0.24f),
                radius = r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFFFFE08A),
                        Color.White,
                        Color(0xFFC7D6FF)
                    )
                ),
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(animated * 100).toInt()}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Text(
                "开启率",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// ==============================================================
//  Settings Group Card：色点标题 + 自适应圆角卡片
// ==============================================================
@Composable
private fun SettingsGroupCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    accentBrush: Brush,
    content: @Composable () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 标题头
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BadgeDot(accent)
            }
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun BadgeDot(accent: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(accent)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.35f))
        )
    }
}

// ==============================================================
//  Primary Switch：左标题/描述 + 右开关（自适应风）
// ==============================================================
@Composable
private fun PrimarySwitch(
    title: String,
    desc: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    accent: Color = SuccessGreen
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent,
                uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedTrackColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
private fun DividerLine() {
    Spacer(Modifier.height(2.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
    Spacer(Modifier.height(2.dp))
}

// ==============================================================
//  IntFieldBlock：数字输入小方块（左上标题、底部 suffix）
// ==============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntFieldBlock(
    modifier: Modifier = Modifier,
    title: String,
    value: Int,
    suffix: String,
    onValue: (Int) -> Unit,
    accent: Color,
    enabled: Boolean = true,
    autoHint: String? = null
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { raw ->
                val n = raw.filter(Char::isDigit).toIntOrNull()
                if (n != null) onValue(n)
                else if (raw.isEmpty()) onValue(0)
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            trailingIcon = {
                Text(
                    suffix,
                    color = accent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 12.dp)
                )
            },
            shape = RoundedCornerShape(16.dp)
        )
        if (autoHint != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(6.dp)) { drawCircle(accent) }
                Text(
                    autoHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ==============================================================
//  IntStepper：数值条（左标题、右加减）
// ==============================================================
@Composable
private fun IntStepper(
    title: String,
    suffix: String,
    hint: String,
    value: Int,
    onValue: (Int) -> Unit,
    range: IntRange,
    step: Int
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            StepperControl(
                value = value,
                suffix = suffix,
                onValue = onValue,
                range = range,
                step = step
            )
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepperControl(
    value: Int,
    suffix: String,
    onValue: (Int) -> Unit,
    range: IntRange,
    step: Int
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val minusEnabled = value - step >= range.first
        val plusEnabled = value + step <= range.last
        StepperChip("−", enabled = minusEnabled, onClick = { onValue(value - step) })
        Box(
            Modifier
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "$value",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    suffix,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        StepperChip("+", enabled = plusEnabled, onClick = { onValue(value + step) })
    }
}

@Composable
private fun StepperChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .then(
                if (enabled) Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .bounceClick(onClick = onClick)
                else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}

// ==============================================================
//  AutoCount Toggle（智能次数）
// ==============================================================
@Composable
private fun AutoCountToggle(
    autoCount: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (autoCount) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (autoCount) GradientHeroBlue
                    else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = if (autoCount) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "智能次数 · 缺口自动计算",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (autoCount) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "读取积分卡片剩余缺口，自动设定搜索/阅读次数；忽略手动次数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = autoCount,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BadgeBlue
            )
        )
    }
}

// ==============================================================
//  Instance Row（必应实例单选）
// ==============================================================
@Composable
private fun InstanceRow(
    label: String,
    serial: Long,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                else Modifier.background(MaterialTheme.colorScheme.surface)
            )
            .bounceClick(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "userHandle / serial = $serial",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "当前选中",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
