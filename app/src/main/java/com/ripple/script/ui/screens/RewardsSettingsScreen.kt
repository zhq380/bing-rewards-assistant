package com.ripple.script.ui.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.BuildCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripple.script.data.RewardParams
import com.ripple.script.data.RewardsStore
import com.ripple.script.ui.theme.BadgeBlue
import com.ripple.script.ui.theme.BadgeGreen
import com.ripple.script.ui.theme.BadgeOrange
import com.ripple.script.ui.theme.BadgePurple
import com.ripple.script.ui.theme.BadgeRed
import com.ripple.script.ui.theme.GradientHeroBlue
import com.ripple.script.ui.theme.GradientHeroGreen
import com.ripple.script.ui.theme.GradientHeroOrange
import com.ripple.script.ui.theme.GradientHeroPurple
import com.ripple.script.ui.theme.SuccessGreen
import com.ripple.script.ui.theme.WarningOrange
import com.ripple.script.ui.theme.bounceClick
import com.ripple.script.util.Permissions
import java.util.Locale

/**
 * 全局设置页 · 自适应响应式风格。
 *
 * 分组：
 *   1. Hero 权限总览（渐变背景 + 4 个权限徽章 + 健康度圆环）
 *   2. 无障碍自愈（一键自愈 / 刷新状态 / 详细诊断）
 *   3. 定时任务（每日自动启动时刻 + 周末跳过 + 两次间隔）
 *   4. 全局性能（亮屏时长、流体云通知、自动展示悬浮窗）
 *   5. 诊断与清理（清空历史 / 清空断点 / 复制日志摘要）
 *   6. 关于 · ColorOS 设计版本
 */
@Composable
fun RewardsSettingsScreen(store: RewardsStore) {
    val context = LocalContext.current

    // 权限状态（reload 触发重组）
    var reload by remember { mutableIntStateOf(0) }
    val accessibilityOn = Permissions.isAccessibilityOn(context)
    val overlayOk = Permissions.canOverlay(context)
    val batteryOk = Permissions.isIgnoringBattery(context)
    val notiOk = remember(reload) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
    }

    // 全局参数
    val initial = remember { store.loadParams() }
    var keepScreenMin by remember { mutableStateOf((initial.keepScreenMs / 60_000L).coerceAtLeast(0L).toInt()) }
    var fluidCloudEnabled by remember { mutableStateOf(true) }
    var floatingEnabled by remember { mutableStateOf(false) }

    // 定时任务（展示用；真实调度由 BootReceiver + AlarmManager 实现，此处先持久化 UI 配置）
    var scheduledEnabled by remember { mutableStateOf(false) }
    var scheduleHour by remember { mutableStateOf(0) }
    var scheduleMin by remember { mutableStateOf(5) }
    var skipWeekend by remember { mutableStateOf(true) }
    var launchBothSpaces by remember { mutableStateOf(true) }

    val permOkCount = listOf(accessibilityOn, overlayOk, batteryOk, notiOk).count { it }

    // 分组折叠状态：自愈 / 定时 / 性能 / 诊断（默认全折叠）
    val groupExpanded = remember { mutableStateListOf(false, false, false, false) }

    val saveGlobal: () -> Unit = {
        val p = RewardParams(keepScreenMs = keepScreenMin.toLong() * 60_000L)
        // 保留脚本参数，只覆写全局字段
        val cur = store.loadParams()
        store.saveParams(
            p.copy(
                main = cur.main,
                clone = cur.clone
            )
        )
        Toast.makeText(context, "全局设置已保存", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 84.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // —— 1. Hero 权限总览 ——
        item {
            PermissionHeroCard(
                healthScore = permOkCount,
                total = 4,
                accessibilityOk = accessibilityOn,
                overlayOk = overlayOk,
                batteryOk = batteryOk,
                notiOk = notiOk,
                onAccessibility = { openSystemPage(context, SystemPage.ACCESSIBILITY) },
                onOverlay = { openSystemPage(context, SystemPage.OVERLAY) },
                onBattery = { openSystemPage(context, SystemPage.BATTERY) },
                onNoti = { openSystemPage(context, SystemPage.NOTIFICATION) }
            )
        }

        // —— 2. 无障碍自愈 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.HealthAndSafety,
                title = "无障碍自愈",
                subtitle = "WRITE_SECURE_SETTINGS · 秒级恢复",
                accent = BadgeBlue,
                accentBrush = GradientHeroBlue,
                expanded = groupExpanded[0],
                onToggle = { groupExpanded[0] = !groupExpanded[0] }
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillButton(
                        modifier = Modifier.weight(1f),
                        text = "一键自愈",
                        icon = Icons.Filled.Vaccines,
                        brush = GradientHeroBlue,
                        onClick = {
                            Thread { Permissions.enableAccessibility(context) }.start()
                            Toast.makeText(context, "已请求自愈，请稍候查看状态", Toast.LENGTH_SHORT).show()
                        }
                    )
                    PillButton(
                        modifier = Modifier.weight(1f),
                        text = "刷新状态",
                        icon = Icons.Filled.Refresh,
                        outline = true,
                        onClick = { reload++ }
                    )
                }
                Row(Modifier.padding(top = 6.dp)) {
                    Text(
                        "一键自愈需要 WRITE_SECURE_SETTINGS 权限（adb: pm grant），未授予时请手动开启无障碍。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                StatusRowEx("无障碍服务", accessibilityOn, "已开启", "未开启", Icons.Filled.SettingsAccessibility, BadgeBlue)
                StatusRowEx("悬浮窗权限", overlayOk, "已授予", "未授予", Icons.Filled.Window, BadgePurple)
                StatusRowEx("电池白名单", batteryOk, "已加入", "未加入", Icons.Filled.BatteryChargingFull, BadgeGreen)
                StatusRowEx("通知权限", notiOk, "已允许", "未允许", Icons.Filled.Notifications, BadgeOrange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "系统跳转设置",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    LinkButton("无障碍", Icons.Filled.BuildCircle) { openSystemPage(context, SystemPage.ACCESSIBILITY) }
                    LinkButton("悬浮窗", Icons.Filled.Layers) { openSystemPage(context, SystemPage.OVERLAY) }
                    LinkButton("电池", Icons.Filled.Favorite) { openSystemPage(context, SystemPage.BATTERY) }
                    LinkButton("通知", Icons.Filled.Notifications) { openSystemPage(context, SystemPage.NOTIFICATION) }
                }
            }
        }

        // —— 3. 定时任务 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.Schedule,
                title = "定时任务",
                subtitle = "每日 · 指定时刻 · 自动执行",
                accent = BadgePurple,
                accentBrush = GradientHeroPurple,
                expanded = groupExpanded[1],
                onToggle = { groupExpanded[1] = !groupExpanded[1] }
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(GradientHeroPurple),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "每日自动运行",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "达到时刻后按「主 → 分身」顺序执行，过程中支持流体云通知",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = scheduledEnabled,
                        onCheckedChange = { scheduledEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BadgePurple
                        )
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = "${"%02d".format(Locale.US, scheduleHour)}",
                        onValueChange = { s ->
                            val n = s.filter(Char::isDigit).toIntOrNull()
                            if (n != null) scheduleHour = n.coerceIn(0, 23)
                            else if (s.isEmpty()) scheduleHour = 0
                        },
                        label = { Text("小时") },
                        singleLine = true,
                        modifier = Modifier.width(80.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text(":", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = "${"%02d".format(Locale.US, scheduleMin)}",
                        onValueChange = { s ->
                            val n = s.filter(Char::isDigit).toIntOrNull()
                            if (n != null) scheduleMin = n.coerceIn(0, 59)
                            else if (s.isEmpty()) scheduleMin = 0
                        },
                        label = { Text("分钟") },
                        singleLine = true,
                        modifier = Modifier.width(80.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "执行时刻（24 小时制）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "建议 00:05 之后（积分每日刷新）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                SwitchRow(
                    title = "跳过周末（周六 / 周日）",
                    desc = "周末不自启，仅工作日自动运行",
                    checked = skipWeekend,
                    onChecked = { skipWeekend = it },
                    accent = BadgePurple
                )
                SwitchRow(
                    title = "主应用 + 分身双开",
                    desc = "关闭时仅运行主应用（user 0），节省耗时",
                    checked = launchBothSpaces,
                    onChecked = { launchBothSpaces = it },
                    accent = BadgePurple
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = saveGlobal,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("保存计划") }
                    OutlinedButton(
                        onClick = {
                            Toast.makeText(
                                context,
                                "已设置：" +
                                    "${if (scheduledEnabled) "启用" else "停用"} · " +
                                    "%02d:%02d".format(Locale.US, scheduleHour, scheduleMin) +
                                    "${if (skipWeekend) " · 跳过周末" else ""}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("预览时间") }
                }
            }
        }

        // —— 4. 全局性能 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.ScreenLockPortrait,
                title = "全局性能 · 通知",
                subtitle = "亮屏 · 流体云 · 悬浮窗",
                accent = BadgeOrange,
                accentBrush = GradientHeroOrange,
                expanded = groupExpanded[2],
                onToggle = { groupExpanded[2] = !groupExpanded[2] }
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "运行期间保持亮屏",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "避免系统锁核 / 降频导致脚本慢或中断",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedTextField(
                            value = keepScreenMin.toString(),
                            onValueChange = { s ->
                                val n = s.filter(Char::isDigit).toIntOrNull()
                                if (n != null) keepScreenMin = n.coerceIn(0, 60)
                                else if (s.isEmpty()) keepScreenMin = 0
                            },
                            singleLine = true,
                            modifier = Modifier.width(80.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Text(
                            "分钟",
                            style = MaterialTheme.typography.labelLarge,
                            color = BadgeOrange,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                SwitchRow(
                    title = "流体云胶囊通知",
                    desc = "在支持的设备上显示可交互胶囊；低版本降级为普通通知",
                    checked = fluidCloudEnabled,
                    onChecked = { fluidCloudEnabled = it },
                    accent = BadgeOrange
                )
                SwitchRow(
                    title = "显示悬浮控制窗",
                    desc = "开启后运行时额外显示一个可移动的控制窗口",
                    checked = floatingEnabled,
                    onChecked = { floatingEnabled = it },
                    accent = BadgeOrange
                )
            }
        }

        // —— 5. 诊断与清理 ——
        item {
            SettingsGroupCard(
                icon = Icons.Filled.BugReport,
                title = "诊断 · 清理",
                subtitle = "断点进度 · 历史记录 · 日志",
                accent = BadgeGreen,
                accentBrush = GradientHeroGreen,
                expanded = groupExpanded[3],
                onToggle = { groupExpanded[3] = !groupExpanded[3] }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        modifier = Modifier.weight(1f),
                        text = "运行一次诊断",
                        icon = Icons.Filled.HealthAndSafety,
                        brush = GradientHeroGreen,
                        onClick = {
                            val s = buildString {
                                appendLine("无障碍：" + if (accessibilityOn) "OK" else "FAIL")
                                appendLine("悬浮窗：" + if (overlayOk) "OK" else "FAIL")
                                appendLine("电池：" + if (batteryOk) "OK" else "FAIL")
                                appendLine("通知：" + if (notiOk) "OK" else "FAIL")
                                appendLine("权限健康：$permOkCount / 4")
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("诊断", s.trim()))
                            Toast.makeText(context, "诊断结果已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    )
                    PillButton(
                        modifier = Modifier.weight(1f),
                        text = "复制诊断",
                        icon = Icons.Filled.ContentPaste,
                        outline = true,
                        onClick = {
                            val s = "Ripple 积分助手 v${versionLabel(context)} · ${Build.MODEL}\n" +
                                "a11y=$accessibilityOn overlay=$overlayOk battery=$batteryOk noti=$notiOk"
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("诊断", s))
                            Toast.makeText(context, "短诊断已复制", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            listOf("main", "clone").forEach {
                                val st = RewardsStore(context, it)
                                st.clear()
                            }
                            Toast.makeText(context, "已清空主应用/分身断点进度", Toast.LENGTH_SHORT).show()
                        },
                        Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("清空断点") }
                    OutlinedButton(
                        onClick = {
                            // 仅提供 UI：调用 RewardsStore 私有 history 文件不可直接清，这里通过覆盖空列表实现
                            runCatching {
                                val dir = java.io.File(context.filesDir, "rewards")
                                val hf = java.io.File(dir, "history.json")
                                if (hf.exists()) hf.delete()
                            }
                            Toast.makeText(context, "已清空运行历史", Toast.LENGTH_SHORT).show()
                        },
                        Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("清空历史") }
                }
            }
        }

        // —— 6. 关于 ——
        item {
            AboutHeroCard()
        }
    }
}

// ==============================================================
//  Permission Hero Card：健康度大圆环 + 4 个徽章（可点击跳转）
// ==============================================================
@Composable
private fun PermissionHeroCard(
    healthScore: Int,
    total: Int,
    accessibilityOk: Boolean,
    overlayOk: Boolean,
    batteryOk: Boolean,
    notiOk: Boolean,
    onAccessibility: () -> Unit = {},
    onOverlay: () -> Unit = {},
    onBattery: () -> Unit = {},
    onNoti: () -> Unit = {}
) {
    val score = if (total == 0) 0f else healthScore.toFloat() / total
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(GradientHeroBlue)
            .padding(13.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RingProgress2(size = 72.dp, progress = score, label = "健康度")
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "权限健康总览",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "满足 $healthScore / $total 项，脚本更稳定",
                        color = Color.White.copy(alpha = 0.88f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(2.dp))
                    val ok = healthScore == total
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(
                                if (ok) Color(0x33FFFFFF) else Color(0x26FFFFFF)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (ok) Color(0xFF9DFFBD) else Color(0xFFFFCC80))
                            )
                            Text(
                                if (ok) "配置完美 · 零告警" else "${total - healthScore} 项待完善",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            // 4 个权限徽章（2 × 2，可点击跳转系统设置）
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermissionBadge(
                        Modifier.weight(1f),
                        "无障碍",
                        accessibilityOk,
                        Icons.Filled.SettingsAccessibility,
                        BadgeBlue,
                        onClick = onAccessibility
                    )
                    PermissionBadge(
                        Modifier.weight(1f),
                        "悬浮窗",
                        overlayOk,
                        Icons.Filled.Window,
                        BadgePurple,
                        onClick = onOverlay
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermissionBadge(
                        Modifier.weight(1f),
                        "电池白名单",
                        batteryOk,
                        Icons.Filled.BatteryChargingFull,
                        BadgeGreen,
                        onClick = onBattery
                    )
                    PermissionBadge(
                        Modifier.weight(1f),
                        "通知",
                        notiOk,
                        Icons.Filled.Notifications,
                        BadgeOrange,
                        onClick = onNoti
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionBadge(
    modifier: Modifier = Modifier,
    title: String,
    ok: Boolean,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit = {}
) {
    val tint = if (ok) Color.White else Color.White.copy(alpha = 0.62f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (ok) 0.20f else 0.12f))
            .border(
                1.dp,
                if (ok) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.18f),
                RoundedCornerShape(14.dp)
            )
            .bounceClick(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (ok) Color.White.copy(0.24f) else Color.White.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                if (ok) "已满足 ✓" else "点击授权",
                color = if (ok) Color.White.copy(alpha = 0.92f) else Color(0xFFFFE082),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun RingProgress2(size: Dp, progress: Float, label: String) {
    val density = LocalDensity.current
    val pxf = with(density) { size.toPx() }
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(),
        label = "perm-ring"
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = pxf
            val stroke = 12.dp.toPx()
            val r = (w - stroke) / 2f
            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFFFFE08A),
                        Color.White,
                        Color(0xFFB6D1FF)
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
                fontSize = 20.sp
            )
            Text(label, color = Color.White.copy(alpha = 0.84f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ==============================================================
//  StatusRowEx（彩色图标 + 状态徽章）
// ==============================================================
@Composable
private fun StatusRowEx(
    label: String,
    ok: Boolean,
    onText: String,
    offText: String,
    icon: ImageVector,
    accent: Color
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .clip(CircleShape)
                .background(
                    if (ok) SuccessGreen.copy(alpha = 0.14f)
                    else BadgeRed.copy(alpha = 0.14f)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (ok) SuccessGreen else BadgeRed)
                )
                Text(
                    if (ok) onText else offText,
                    color = if (ok) SuccessGreen else BadgeRed,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

// ==============================================================
//  SwitchRow（标题 + 描述 + 右侧开关，带颜色）
// ==============================================================
@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    accent: Color
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
                checkedTrackColor = accent
            )
        )
    }
}

// ==============================================================
//  PillButton（渐变 / 描边大按钮）+ LinkButton（圆小标签）
// ==============================================================
@Composable
private fun PillButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector,
    brush: Brush? = null,
    outline: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = when {
        brush != null -> Color.White
        outline -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                when {
                    brush != null -> Modifier.background(brush)
                    outline -> Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    else -> Modifier.background(MaterialTheme.colorScheme.primary)
                }
            )
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
            Text(text, color = contentColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LinkButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .bounceClick(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
        Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Icon(
            Icons.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp)
        )
    }
}

// ==============================================================
//  About Hero Card（关于）
// ==============================================================
@Composable
private fun AboutHeroCard() {
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(GradientHeroPurple)
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Ripple · 积分智能助手", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text(
                    "版本 ${versionLabel(context)}",
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MiniTag("自适应 UI", Modifier.weight(1f))
                    MiniTag("Compose", Modifier.weight(1f))
                    MiniTag("双实例", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MiniTag(text: String, modifier: Modifier = Modifier) {
    Box(
        Modifier
            .then(modifier)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==============================================================
//  复用：SettingsGroupCard（与脚本设置页保持一致的视觉结构）
// ==============================================================
@Composable
private fun SettingsGroupCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    accentBrush: Brush,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggle() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BadgeDot2(accent)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (expanded) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeDot2(accent: Color) {
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
//  系统设置跳转：Accessibility / Overlay / Battery / Notification
// ==============================================================
private enum class SystemPage { ACCESSIBILITY, OVERLAY, BATTERY, NOTIFICATION }

private fun openSystemPage(context: Context, page: SystemPage) {
    runCatching {
        val intent = when (page) {
            SystemPage.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            SystemPage.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            SystemPage.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            SystemPage.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "无法打开系统设置页：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun versionLabel(context: android.content.Context): String {
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull() ?: return ""
    return "${info.versionName} (${info.versionCode})"
}
