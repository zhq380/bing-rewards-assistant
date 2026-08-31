package com.ripple.script.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ripple.script.data.RewardsStore
import com.ripple.script.ui.screens.PhaseLog
import com.ripple.script.ui.screens.RewardsHomeScreen
import com.ripple.script.ui.screens.RewardsLogsScreen
import com.ripple.script.ui.screens.RewardsScriptSettingsScreen
import com.ripple.script.ui.screens.RewardsSettingsScreen
import com.ripple.script.ui.theme.GradientHeroBlue
import com.ripple.script.ui.theme.Motion
import com.ripple.script.ui.theme.bounceClick

/**
 * 导航路由（「从哪里来 · 到哪里去」返回栈约定）：
 *   HOME                     → 首页（脚本运行中心，胶囊底栏可见）
 *   LOGS                     → 运行日志（阶段流 / 时间轴 / 统计，胶囊底栏可见）
 *   SETTINGS                 → 全局设置（胶囊底栏可见）
 *   SCRIPT/{id}?source={src} → 脚本设置页（id=main|clone，source=home|settings|…）
 *                              TopBar 按钮根据 source 显示语义化标题
 *                              （「返回 · 智能运行中心」/「返回 · 全局设置」等），
 *                              popBackStack 严格返回上一页，不跳过栈。
 */
object Routes {
    const val HOME = "home"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val SCRIPT = "script/{id}?source={source}"       // id = "main" | "clone", source = "home" | "settings"
    const val SCRIPT_MAIN_HOME = "script/main?source=home"
    const val SCRIPT_CLONE_HOME = "script/clone?source=home"
    const val SCRIPT_MAIN_SETTINGS = "script/main?source=settings"
    const val SCRIPT_CLONE_SETTINGS = "script/clone?source=settings"
    fun scriptOf(id: String, source: String = "home") = "script/$id?source=$source"
}

private data class Tab(
    val route: String,
    val label: String,
    val activeLabel: String,
    val icon: ImageVector
)

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val store = remember { RewardsStore(context) }
    val navController = rememberNavController()

    val tabs = listOf(
        Tab(Routes.HOME, "运行", "智能运行中心", Icons.Filled.AutoAwesome),
        Tab(Routes.LOGS, "日志", "运行日志", Icons.Filled.TrackChanges),
        Tab(Routes.SETTINGS, "设置", "偏好设置", Icons.Filled.Settings)
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val isRoot = tabs.any { it.route == currentRoute }

    // 共享阶段日志：首页运行产生 → 日志页实时展示
    val phaseLog = remember { PhaseLog() }

    // 读取屏幕配置：用户填了像素 → 动态换算成 dp；没填 → 默认 600dp 响应式
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val params = remember { store.loadParams() }
    val maxWidthDp = remember(params.screen, config.screenWidthDp) {
        val sc = params.screen
        if (sc.enabled && sc.maxWidthPx > 0) {
            // 用户填的像素值 → 换算 dp
            with(density) { sc.maxWidthPx.toDp() }
        } else {
            600.dp
        }
    }

    // 语义化返回标题：按 source（从哪里来 → 回到哪里）
    val backTitle: String = when (currentRoute) {
        Routes.SCRIPT -> {
            val source = backStack?.arguments?.getString("source")?.takeIf { it.isNotBlank() } ?: "home"
            val id = backStack?.arguments?.getString("id") ?: "main"
            val instanceName = if (id == "clone") "分身" else "主应用"
            when (source) {
                "settings" -> "返回 · 全局设置"
                "home" -> "返回 · 智能运行中心"
                else -> "返回 · $instanceName"
            }
        }
        else -> "返回"
    }
    val pageTitle: String = when (currentRoute) {
        Routes.HOME -> "智能运行中心"
        Routes.LOGS -> "运行日志"
        Routes.SETTINGS -> "全局设置"
        Routes.SCRIPT -> {
            val id = backStack?.arguments?.getString("id") ?: "main"
            if (id == "clone") "分身应用脚本 · 设置" else "主应用脚本 · 设置"
        }
        else -> ""
    }
    val rootTitle: String = when (currentRoute) {
        Routes.LOGS -> "运行日志"
        Routes.SETTINGS -> "偏好设置"
        else -> "智能运行中心"
    }

    Scaffold(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (isRoot) RootTopBar(title = rootTitle) else SubTopBar(title = pageTitle, backTitle = backTitle, onBack = { navController.popBackStack() })
        },
        bottomBar = {
            if (isRoot) AdaptiveBottomNav(tabs, currentRoute, onClick = { r ->
                navController.navigate(r) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            })
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = maxWidthDp)
                    .padding(padding),
            // 横滑过渡：二级页从右侧滑入回到右侧滑出，根 tab 淡入淡出
            enterTransition = {
                val fromSub = initialState.destination.route == Routes.SCRIPT
                val toSub = targetState.destination.route == Routes.SCRIPT
                when {
                    toSub && !fromSub -> slideInHorizontally(
                        animationSpec = tween(Motion.Duration.LONG, easing = Motion.EmphasizedDecelerate),
                        initialOffsetX = { w -> (w * 0.25f).toInt() }
                    ) + fadeIn(tween(Motion.Duration.MEDIUM))
                    else -> fadeIn(tween(Motion.Duration.SHORT))
                }
            },
            exitTransition = {
                val fromSub = initialState.destination.route == Routes.SCRIPT
                val toSub = targetState.destination.route == Routes.SCRIPT
                when {
                    fromSub && !toSub -> slideOutHorizontally(
                        animationSpec = tween(Motion.Duration.LONG, easing = Motion.EmphasizedAccelerate),
                        targetOffsetX = { w -> (w * 0.25f).toInt() }
                    ) + fadeOut(tween(Motion.Duration.MEDIUM))
                    else -> fadeOut(tween(Motion.Duration.SHORT))
                }
            },
            popEnterTransition = {
                val popFrom = initialState.destination.route
                slideInHorizontally(
                    animationSpec = tween(Motion.Duration.LONG, easing = Motion.EmphasizedDecelerate),
                    initialOffsetX = { w -> -(w * 0.2f).toInt() }
                ) + fadeIn(tween(Motion.Duration.MEDIUM))
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(Motion.Duration.LONG, easing = Motion.EmphasizedAccelerate),
                    targetOffsetX = { w -> (w * 0.4f).toInt() }
                ) + fadeOut(tween(Motion.Duration.MEDIUM))
            }
        ) {
            composable(Routes.HOME) {
                RewardsHomeScreen(
                    store = store,
                    phaseLog = phaseLog,
                    onOpenScript = { isClone ->
                        navController.navigate(if (isClone) Routes.SCRIPT_CLONE_HOME else Routes.SCRIPT_MAIN_HOME) {
                            // 从首页进入脚本设置：push 一次，popBackStack 必回首页
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.LOGS) {
                RewardsLogsScreen(store = store, phaseLog = phaseLog)
            }
            composable(Routes.SETTINGS) {
                RewardsSettingsScreen(store = store)
            }
            composable(
                route = Routes.SCRIPT,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("source") {
                        type = NavType.StringType
                        defaultValue = "home"
                        nullable = false
                    }
                )
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: "main"
                RewardsScriptSettingsScreen(
                    store = store,
                    isClone = (id == "clone"),
                    onBack = { navController.popBackStack() }
                )
            }
            }
        }
    }
}

// ============================================================
//  Ripple 根顶栏：流体渐变 Hero 背景 + 品牌标题
// ============================================================
@Composable
private fun RootTopBar(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(GradientHeroBlue)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = "Ripple · 积分助手",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = title,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ============================================================
//  二级顶栏：「从哪里来 · 到哪里去」语义化返回
//  左侧「返回 · 来源页名」胶囊按钮，中间当前页标题视觉居中
// ============================================================
@Composable
private fun SubTopBar(
    title: String,
    backTitle: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .bounceClick(onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = backTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        // 占位权重 = 1 + 返回按钮大致宽比重（2f），让标题偏视觉中心
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(2.6f))
    }
}

// ============================================================
//  胶囊底栏：3 项 · 激活显示主色 Pill 背景
// ============================================================
@Composable
private fun AdaptiveBottomNav(
    tabs: List<Tab>,
    currentRoute: String?,
    onClick: (String) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onClick(tab.route) },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(56.dp, 32.dp)
                            .clip(RoundedCornerShape(50))
                            .then(
                                if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                label = {
                    Text(
                        if (selected) tab.activeLabel else tab.label,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

// helper
private fun AnimatedContentTransitionScope<*>.initialState() = initialState
private fun AnimatedContentTransitionScope<*>.targetState() = targetState
