package com.ripple.script.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripple.script.data.RewardsStore
import com.ripple.script.data.RunRecord
import com.ripple.script.ui.theme.BadgeBlue
import com.ripple.script.ui.theme.BadgeGreen
import com.ripple.script.ui.theme.BadgeOrange
import com.ripple.script.ui.theme.BadgePurple
import com.ripple.script.ui.theme.GradientHeroBlue
import com.ripple.script.ui.theme.GradientHeroOrange
import com.ripple.script.ui.theme.GradientHeroPurple
import com.ripple.script.ui.theme.SuccessContainer
import com.ripple.script.ui.theme.SuccessGreen
import com.ripple.script.ui.theme.WarningContainer
import com.ripple.script.ui.theme.WarningOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志页 · 自适应响应式风格。
 *
 * 1) 今日运行总览（Hero 状态卡：渐变 + 完成率环 + 4 统计胶囊）
 * 2) 执行阶段流（实时，来自共享 PhaseLog）
 * 3) 今日 / 昨日 结果时间轴
 * 4) 近 7 日统计分析
 *
 * 首页「运行中心」只保留脚本相关，总览 / 日志 / 统计类信息全部集中在此页。
 */
@Composable
fun RewardsLogsScreen(store: RewardsStore, phaseLog: PhaseLog) {
    val phases by phaseLog.phases.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // —— 1. 今日运行总览 ——
        item { HeroStateCard(store) }

        // —— 2. 执行阶段流 ——
        item { SectionChipRow(Icons.Filled.TrackChanges, "执行阶段 · 实时流") }
        if (phases.isNotEmpty()) {
            items(phases.takeLast(18)) { (phase, kind) ->
                PhaseBadgeRow(text = phase, kind = kind)
            }
        } else {
            item {
                EmptyHint(
                    text = "还没有阶段记录。去「运行」页点击「立即运行」开始签入",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // —— 3. 结果时间轴 ——
        item { SectionChipRow(Icons.Filled.TaskAlt, "最近完成记录 · 时间轴") }
        item { ResultTimeline(store = store) }

        // —— 4. 统计分析 ——
        item { SectionChipRow(Icons.Filled.History, "运行数据 · 近 7 日") }
        item { StatisticsCard(store = store) }

        // 底部留白
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ==============================================================
//  1) Hero 状态卡：流体渐变背景 + 圆环完成率 + 4 统计胶囊
// ==============================================================
@Composable
private fun HeroStateCard(store: RewardsStore) {
    var rate by remember { mutableStateOf(0f) }
    var total by remember { mutableStateOf(0) }
    var sec by remember { mutableStateOf(0L) }
    var doneToday by remember { mutableStateOf<RunRecord?>(null) }

    LaunchedEffect(Unit) {
        rate = store.completionRate()
        total = store.totalEstimatedPoints()
        sec = store.averageDurationSec()
        val today = java.time.LocalDate.now().toString()
        doneToday = store.loadRecords().lastOrNull { it.date == today }
    }

    val ringSize = 84.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(GradientHeroBlue)
            .padding(14.dp)
    ) {
        Column {
            // 顶部标题
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "今日运行总览",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = SimpleDateFormat("MM 月 dd 日 EEEE", Locale.CHINA).format(Date()),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (doneToday?.success == true) "今日已完成 ✓" else "今日待运行",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左：完成率圆环
                CircularProgress(size = ringSize, progress = rate)
                Spacer(Modifier.width(12.dp))
                // 右：4 个统计胶囊
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            StatPill(
                                label = "完成率",
                                value = "${(rate * 100).toInt()}%",
                                icon = Icons.Filled.CheckCircle
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            StatPill(
                                label = "累计积分",
                                value = "+$total",
                                icon = Icons.Filled.CopyAll
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            StatPill(
                                label = "平均耗时",
                                value = "${sec}s",
                                icon = Icons.Filled.TrackChanges
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            StatPill(
                                label = "今日状态",
                                value = if (doneToday?.success == true) "已达标" else "待启动",
                                icon = Icons.Filled.TipsAndUpdates
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CircularProgress(size: Dp, progress: Float) {
    val density = LocalDensity.current
    val pxf = with(density) { size.toPx() }
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(),
        label = "hero-ring"
    )
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = pxf
            val stroke = 14.dp.toPx()
            val radius = (w - stroke) / 2f
            // 背景环
            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = radius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            // 前景弧
            drawArc(
                brush = Brush.sweepGradient(listOf(Color(0xFFFFE08A), Color.White, Color(0xFFC7D6FF))),
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
                text = "${(animated * 100).toInt()}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Text(
                text = "完成率",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Column {
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(label, color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ==============================================================
//  阶段流日志行：按类型着色
// ==============================================================
@Composable
private fun PhaseBadgeRow(text: String, kind: BadgeKind) {
    val (color, icon) = when (kind) {
        BadgeKind.SignIn -> BadgeOrange to Icons.Filled.TipsAndUpdates
        BadgeKind.Search -> BadgeBlue to Icons.Filled.TrackChanges
        BadgeKind.Read -> BadgePurple to Icons.Filled.CopyAll
        BadgeKind.Daily -> BadgeGreen to Icons.Filled.TaskAlt
        BadgeKind.Progress -> BadgeBlue to Icons.Filled.History
        BadgeKind.Warning -> BadgeOrange to Icons.Default.CheckCircle
        BadgeKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Filled.TipsAndUpdates
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==============================================================
//  结果时间轴：今日 / 昨日
// ==============================================================
@Composable
private fun ResultTimeline(store: RewardsStore) {
    val today = java.time.LocalDate.now().toString()
    val yesterday = java.time.LocalDate.now().minusDays(1).toString()
    val todayRec = store.loadRecords().lastOrNull { it.date == today }
    val yestRec = store.loadRecords().lastOrNull { it.date == yesterday }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TimelineDay(
            dayLabel = "今天",
            subLabel = SimpleDateFormat("MM.dd", Locale.CHINA).format(Date()),
            record = todayRec,
            accent = GradientHeroBlue,
            first = true
        )
        TimelineDay(
            dayLabel = "昨天",
            subLabel = SimpleDateFormat("MM.dd", Locale.CHINA).format(Date(System.currentTimeMillis() - 86400_000)),
            record = yestRec,
            accent = GradientHeroPurple,
            first = false
        )
    }
}

@Composable
private fun TimelineDay(
    dayLabel: String,
    subLabel: String,
    record: RunRecord?,
    accent: Brush,
    first: Boolean
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 时间轴左侧
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(52.dp)
        ) {
            Text(
                dayLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .then(
                        if (record != null) Modifier.background(
                            if (record.success) SuccessGreen else WarningOrange
                        ) else Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                    )
            )
            if (!first) {
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        // 右侧结果卡
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            if (record == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        "暂无运行记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(
                                    if (record.success) SuccessContainer else WarningContainer
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                if (record.success) "✅ 完成" else "⚠️ 未完成",
                                color = if (record.success) SuccessGreen else WarningOrange,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            android.text.format.DateFormat.format("HH:mm", record.timestamp).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        record.message.ifBlank { if (record.success) "所有目标积分已到账" else "任务中断" },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetricMini("签入", if (record.signedIn) "✓" else "—", BadgeOrange)
                        MetricMini("搜索", "${record.searched}", BadgeBlue)
                        MetricMini("阅读", "${record.read}", BadgePurple)
                        MetricMini("活动", "${record.dailySet}", BadgeGreen)
                        if (record.durationMs > 0) MetricMini(
                            "耗时",
                            "${record.durationMs / 1000}s",
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricMini(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}

// ==============================================================
//  统计卡：完成率 / 累计 / 平均 + 7 日趋势
// ==============================================================
@Composable
private fun StatisticsCard(store: RewardsStore) {
    var weekly by remember { mutableStateOf(emptyList<Pair<String, Int>>()) }
    var rate by remember { mutableStateOf(0f) }
    var avgSec by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        weekly = store.loadWeeklyPoints()
        rate = store.completionRate()
        avgSec = store.averageDurationSec()
        total = store.totalEstimatedPoints()
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatBlock("完成率", "${(rate * 100).toInt()}%", BadgeGreen)
                StatBlock("累计积分", "+$total", BadgeBlue)
                StatBlock("平均耗时", "${avgSec}s", BadgePurple)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionLineDot(BadgeOrange)
                Text(
                    "7 日积分趋势",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            WeeklyBarChart(weekly)
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WeeklyBarChart(data: List<Pair<String, Int>>) {
    val maxVal = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val barAccent = GradientHeroOrange
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEach { (date, points) ->
                val hPct = (points.toFloat() / maxVal).coerceIn(0f, 1f)
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (points > 0) {
                        Text(
                            "+$points",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = BadgeOrange
                        )
                    } else {
                        Spacer(Modifier.height(14.dp))
                    }
                    Box(
                        Modifier
                            .fillMaxHeight(0.8f)
                            .width(14.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .align(Alignment.BottomCenter)
                                .clip(RoundedCornerShape(6.dp))
                                .background(bgColor)
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(hPct)
                                .align(Alignment.BottomCenter)
                                .clip(RoundedCornerShape(6.dp))
                                .background(barAccent)
                        )
                    }
                    Text(
                        date.takeLast(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
            }
        }
    }
}

// ==============================================================
//  公共小组件：Chip 分组标题 / 空态 / 小圆点
// ==============================================================
@Composable
private fun SectionChipRow(icon: ImageVector, title: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SectionLineDot(MaterialTheme.colorScheme.primary)
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SectionLineDot(color: Color) {
    Box(
        Modifier
            .size(18.dp, 4.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun EmptyHint(text: String, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp)
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.bodySmall
        )
    }
}