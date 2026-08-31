package com.ripple.script.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ripple.script.data.RewardsStore
import com.ripple.script.data.ScriptParams
import com.ripple.script.rewards.RunControl
import com.ripple.script.rewards.RewardsController
import com.ripple.script.ui.theme.BadgeBlue
import com.ripple.script.ui.theme.BadgeGreen
import com.ripple.script.ui.theme.BadgeOrange
import com.ripple.script.ui.theme.BadgePurple
import com.ripple.script.ui.theme.GradientHeroBlue
import com.ripple.script.ui.theme.GradientHeroPurple
import com.ripple.script.ui.theme.WarningOrange
import com.ripple.script.ui.theme.bounceClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 自适应响应式首页（智能运行中心）· 只保留脚本相关内容。
 *
 * 结构：
 *   1) 双脚本并排正方形卡片：主应用 / 分身应用（自适应卡片 + 渐变启动按钮 + 跳转到二级设置）
 *   2) 运行控制（暂停 / 停止，仅运行中显示）
 *
 * 今日运行总览 / 运行日志 / 结果时间轴 / 统计分析 → 独立「运行日志」页（RewardsLogsScreen）。
 */
@Composable
fun RewardsHomeScreen(
    store: RewardsStore,
    phaseLog: PhaseLog,
    onOpenScript: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    var running by remember { mutableStateOf(false) }
    var runningClone by remember { mutableStateOf(false) }
    val control = remember { RunControl() }
    val paused by control.paused.collectAsState()
    val mainScript = remember { store.loadScript(false) }
    val cloneScript = remember { store.loadScript(true) }

    val startRun: (Boolean) -> Unit = { isClone ->
        if (!running) {
            running = true
            runningClone = isClone
            control.setPaused(false)
            phaseLog.clear()
            scope.launch {
                try {
                    RewardsController.run(context, store, phaseLog::append, phaseLog::append, { }, control, isClone)
                } catch (e: Exception) {
                    phaseLog.append("异常: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    running = false
                    control.setPaused(false)
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        // —— 1. 双脚本并排正方形卡 ——
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SquareScriptCard(
                    modifier = Modifier.weight(1f),
                    title = "主应用",
                    subtitle = "系统用户 · user 0",
                    accent = GradientHeroBlue,
                    icon = Icons.Filled.Home,
                    script = mainScript,
                    isRunning = running && !runningClone,
                    onRun = { startRun(false) },
                    onSetting = { onOpenScript(false) }
                )
                SquareScriptCard(
                    modifier = Modifier.weight(1f),
                    title = "分身应用",
                    subtitle = "分身 · user 999",
                    accent = GradientHeroPurple,
                    icon = Icons.Filled.Person,
                    script = cloneScript,
                    isRunning = running && runningClone,
                    onRun = { startRun(true) },
                    onSetting = { onOpenScript(true) }
                )
            }
        }

        // —— 2. 运行控制 ——
        if (running) {
            item {
                RunningControls(
                    paused = paused,
                    onTogglePause = { control.setPaused(!paused) },
                    onStop = { control.requestStop() },
                    runningClone = runningClone
                )
            }
        }

        // 底部留白
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ==============================================================
//  1) 并排正方形脚本卡：主应用 / 分身应用（精简版）
// ==============================================================
@Composable
private fun SquareScriptCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    accent: Brush,
    icon: ImageVector,
    script: ScriptParams,
    isRunning: Boolean,
    onRun: () -> Unit,
    onSetting: () -> Unit
) {
    // 先定正方形尺寸（fillMaxWidth 拿 Row 分给的精确宽度，aspectRatio 算出等高）
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .bounceClick(onClick = onSetting),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 顶行：渐变图标 + 标题 + 状态（无冗余齿轮）
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            // 用状态徽章代替长副标题
                            when {
                                isRunning -> "运行中"
                                script.autoCount -> "智能模式"
                                else -> "手动模式"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                isRunning -> BadgeGreen
                                script.autoCount -> BadgeBlue
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                // 中部：模块清单（2×2 胶囊）
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            ModuleTag(on = script.checkInEnabled, label = "签入", accent = BadgeOrange)
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            ModuleTag(
                                on = script.searchEnabled,
                                label = "搜索" + (if (script.autoCount) "AUTO" else "×${script.searchCount}"),
                                accent = BadgeBlue
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            ModuleTag(
                                on = script.readEnabled,
                                label = "阅读" + (if (script.autoCount) "AUTO" else "×${script.readCount}"),
                                accent = BadgePurple
                            )
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            ModuleTag(on = script.dailySetEnabled, label = "活动", accent = BadgeGreen)
                        }
                    }
                }

                // 底部主按钮：渐变「立即运行」
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent)
                        .bounceClick(onClick = onRun),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isRunning) "运行中…" else "立即运行",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleTag(on: Boolean, label: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .then(
                if (on) Modifier.background(accent.copy(alpha = 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
                else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = (if (on) "● " else "○ ") + label,
            color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

// ==============================================================
//  共享阶段日志：首页产生 → 日志页实时展示（跨 tab 同步）
// ==============================================================
class PhaseLog {
    private val _phases = MutableStateFlow<List<Pair<String, BadgeKind>>>(emptyList())
    val phases: StateFlow<List<Pair<String, BadgeKind>>> = _phases.asStateFlow()
    fun append(raw: String) {
        _phases.value = (_phases.value + listOf(raw to BadgeKind.of(raw))).takeLast(60)
    }
    fun clear() { _phases.value = emptyList() }
}

// ==============================================================
//  4) 运行控制：暂停 / 停止
// ==============================================================
@Composable
private fun RunningControls(
    paused: Boolean,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    runningClone: Boolean
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (paused) WarningOrange else BadgeGreen
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (paused) "已暂停 · ${if (runningClone) "分身" else "主应用"}" else "执行中 · ${if (runningClone) "分身" else "主应用"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (paused) WarningOrange else MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                Modifier.fillMaxWidth().height(44.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onTogglePause,
                    Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (paused) "继续执行" else "暂停", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onStop,
                    Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("停止", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ==============================================================
//  公共枚举：阶段日志类别（首页产生 → 日志页着色展示）
// ==============================================================
enum class BadgeKind { SignIn, Search, Read, Daily, Progress, Warning, Info;
    companion object {
        fun of(text: String): BadgeKind = when {
            text.contains("签到") || text.contains("签入") -> SignIn
            text.contains("阅读") || text.contains("文章") -> Read
            text.contains("搜索") -> Search
            text.contains("每日活动") || text.contains("活动卡") || text.contains("活动") -> Daily
            text.contains("异常") || text.contains("失败") || text.contains("错误") -> Warning
            text.contains("Phase") || text.contains("阶段") || text.contains("进度") || text.contains("准备") -> Progress
            else -> Info
        }
    }
}
