package com.ripple.script.rewards

import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BingUiTest {
    @Test
    fun `语义点击选中包含文本的目标`() = runBlocking {
        val ui = FakeBingUi(texts = listOf("登录 Microsoft", "返回", "搜索"))
        val clicked = ui.clickText("Microsoft")
        assertTrue(clicked)
        assertEquals("登录 Microsoft", ui.lastClicked)
    }

    @Test
    fun `无匹配则失败`() = runBlocking {
        val ui = FakeBingUi(texts = listOf("设置"))
        assertFalse(ui.clickText("不存在的按钮"))
    }
}

/**
 * 测试用 BingUi 假实现：实现 [BingUi] 全量方法，行为可通过字段配置，
 * 避免每次接口变更都要在多个测试类里补方法。
 *
 * 可配置项：
 * - [texts]：初始屏幕文本；
 * - [foreground]：前台包名（模拟离开必应时设为 launcher）；
 * - [launchOk]：[launch] 是否成功（模拟必应拉不起）；
 * - [onSearch]：每次 [typeAndSubmit] 后替换屏幕文本的回调（模拟搜索结果页）。
 */
open class FakeBingUi(
    texts: List<String> = emptyList(),
    var foreground: String = "com.microsoft.bing",
    var launchOk: Boolean = true
) : BingUi {
    val onScreen = texts.toMutableList()
    var lastClicked: String? = null
    var searched = 0
    var onSearch: (() -> List<String>)? = null

    override suspend fun screenTexts(): List<String> = onScreen.toList()
    override suspend fun clickText(text: String): Boolean =
        onScreen.firstOrNull { it.contains(text) }?.let { lastClicked = it; true } ?: false
    override suspend fun clickResourceId(id: String): Boolean = false
    override suspend fun clickBounds(bounds: Rect): Boolean = true
    override suspend fun click(x: Int, y: Int): Boolean = true
    override suspend fun scrollUp(): Boolean = true
    override suspend fun scrollDown(): Boolean = true
    override suspend fun typeText(s: String): Boolean = true
    override suspend fun pressEnter(): Boolean = true
    override suspend fun typeAndSubmit(s: String): Boolean {
        searched++
        onSearch?.let { fn ->
            onScreen.clear()
            onScreen.addAll(fn())
        }
        return true
    }
    override suspend fun back(): Boolean = true
    override suspend fun scrollToText(text: String, timeoutMs: Long): Boolean =
        onScreen.firstOrNull { it.contains(text) } != null
    override suspend fun waitForText(text: String, timeoutMs: Long): Boolean =
        onScreen.any { it.contains(text) }
    override suspend fun clickTextVisible(text: String): Boolean = clickText(text)
    override suspend fun foregroundPackage(): String = foreground
    override suspend fun launch(): Boolean = launchOk
    override suspend fun captureScreen(): android.graphics.Bitmap? = null
    override suspend fun visibleWidth(): Int = 1272
    override suspend fun visibleHeight(): Int = 2772
    override suspend fun findDesc(desc: String): Rect? = null
    override suspend fun findAllDesc(desc: String): List<Rect> = emptyList()
    override suspend fun findAllDescWithText(desc: String): List<Pair<String, Rect>> = emptyList()
    override fun dismissDialogs() { }
    override suspend fun resolveChooser(): Boolean = true
    override suspend fun newsTitleCandidates(): List<Pair<String, Rect>> = emptyList()
}
