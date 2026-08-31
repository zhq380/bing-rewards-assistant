package com.ripple.script.rewards

import org.junit.Assert.*
import org.junit.Test

class RewardsIntelligenceTest {
    private val ri = RewardsIntelligence

    @Test
    fun `轮播页码指示器命中`() {
        assertTrue(ri.isCarouselIndicator("1/5"))
        assertTrue(ri.isCarouselIndicator("page 2 of 6"))
        assertTrue(ri.isCarouselIndicator("第2页 共6页"))
        assertFalse(ri.isCarouselIndicator("一篇正常标题长长长长长长"))
    }

    @Test
    fun `广告标记文本命中`() {
        assertTrue(ri.isAdMarkerText("广告选项"))
        assertTrue(ri.isAdMarkerText("Sponsored"))
        assertTrue(ri.isAdMarkerText("赞助"))
        assertFalse(ri.isAdMarkerText("新闻标题"))
    }

    @Test
    fun `b站文案仅子串不误杀含词标题`() {
        // 正常标题含 "ad" 子串不得命中（词边界/白名单区分）
        assertFalse(ri.isAdCtaText("read the guide"))
        assertTrue(ri.isAdCtaText("立即下载"))
    }

    @Test
    fun `任务卡片完成态判定`() {
        // 进行中：有「需要」缺口
        assertFalse(ri.isTaskCompleted("搜索以赚取, , 已赚取 3 积分(需要 60 积分)"))
        // 已完成：已赚取且无缺口
        assertTrue(ri.isTaskCompleted("阅读以赚取, 已赚取的 30 积分"))
        assertTrue(ri.isTaskCompleted("Stockholm Nordic summer light, 已赚取的 10 积分"))
        // 空文案不误判
        assertFalse(ri.isTaskCompleted(""))
    }

    @Test
    fun `任务卡片完成判定防误杀`() {
        // 只有「赚取」没有「已赚」→ 未完成
        assertFalse(ri.isTaskCompleted("阅读以赚取"))
        assertFalse(ri.isTaskCompleted("搜索以赚取"))
        // 已赚取 0 积分：尚未计分，不算完成
        assertFalse(ri.isTaskCompleted("阅读以赚取, 已赚取的 0 积分"))
        // 缺口标记优先于完成式文案
        assertFalse(ri.isTaskCompleted("已赚取的 6 积分(还需 30 积分)"))
        // 真完成
        assertTrue(ri.isTaskCompleted("已赚取的 60 积分"))
        assertTrue(ri.isTaskCompleted("已完成"))
    }

    @Test
    fun `会话登录标记判定`() {
        assertTrue(ri.isSignInEntryText("登录"))
        assertTrue(ri.isSignInEntryText("Sign in"))
        assertTrue(ri.isSignInEntryText("登录以赚取积分"))
        // 正文长句含「登录」不算登录入口（按钮/链接级短文本才算）
        assertFalse(ri.isSignInEntryText("如何在 Microsoft Edge 中登录并同步你的收藏夹数据"))
        // 已登录标记：积分卡片 / 账户入口
        assertTrue(ri.hasSignedInMarker(listOf("今日积分", "搜索以赚取")))
        assertTrue(ri.hasSignedInMarker(listOf("我的 Microsoft 账户")))
        assertFalse(ri.hasSignedInMarker(listOf("登录", "Sign in")))
    }
}