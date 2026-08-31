package com.ripple.script.rewards

import com.ripple.script.data.RunProgress
import com.ripple.script.data.RunProgressStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardsAgentTest {

    /** 内存版断点进度存储 */
    private class MemProgress(var p: RunProgress = RunProgress()) : RunProgressStore {
        override fun load(): RunProgress = p
        override fun save(p: RunProgress) { this.p = p }
        override fun clear() { p = RunProgress() }
    }

    /**
     * 模拟「离开必应后能被自动拉回」：
     * 首次 launch（ensureBing 内）失败 → 交由 gate 自动拉回，第二次 launch 成功回到必应。
     */
    private class RecoverableUi(texts: List<String>) :
        FakeBingUi(texts, foreground = "com.android.launcher", launchOk = false) {
        private var calls = 0
        override suspend fun launch(): Boolean {
            calls++
            return if (calls == 1) false else {
                foreground = "com.microsoft.bing"
                true
            }
        }
    }

    /** 模拟「必应永远拉不回」：launch 恒失败 */
    private class DeadUi(texts: List<String>) :
        FakeBingUi(texts, foreground = "com.android.launcher", launchOk = false)

    // —— T1 会话检测 ——

    @Test
    fun `未登录时停止运行且不执行任何签到动作`() = runBlocking {
        val ui = FakeBingUi(listOf("登录", "Sign in"))
        val agent = RewardsAgent(ui, searchCount = 2, readCount = 0, onProgress = {})
        val result = agent.run()

        assertFalse(result.success)
        assertFalse(result.signedIn)
        assertEquals(0, result.searched)
        assertTrue(result.log.any { it.contains("未登录") })
    }

    @Test
    fun `含已登录标记时不误判为未登录`() = runBlocking {
        // 「每日奖励」是登录态下的积分卡片文案，即便同时出现登录入口短文本也应判定已登录
        val ui = FakeBingUi(listOf("登录 Microsoft", "每日奖励"))
        val agent = RewardsAgent(
            ui, searchCount = 0, readCount = 0, onProgress = {},
            dailySetEnabled = false, searchEnabled = false, readEnabled = false
        )
        val result = agent.run()

        assertTrue(result.log.any { it.contains("会话检测") })
        assertTrue(result.signedIn)
    }

    @Test
    fun `正文里出现登录字样不算登录入口`() {
        assertFalse(RewardsIntelligence.isSignInEntryText("如何登录 Microsoft 账户并同步收藏夹数据"))
        assertTrue(RewardsIntelligence.isSignInEntryText("登录"))
        assertTrue(RewardsIntelligence.isSignInEntryText("Sign in"))
    }

    // —— T2 无人接管：离开必应自动拉回，绝不挂起等人 ——

    @Test
    fun `离开必应能拉回时自动继续执行`() = runBlocking {
        val ui = RecoverableUi(listOf("今日积分"))
        val control = RunControl()
        val agent = RewardsAgent(
            ui, searchCount = 0, readCount = 0, onProgress = {},
            control = control,
            dailySetEnabled = false, searchEnabled = false, readEnabled = false,
            recoverAttempts = 3, recoverGapMs = 1
        )
        val result = agent.run()

        assertTrue(result.log.any { it.contains("自动拉回") })
        assertTrue(result.log.any { it.contains("回到必应") })
        assertFalse(control.paused.value)
    }

    @Test
    fun `离开必应拉不回时终止运行而不是挂起等人工`() = runBlocking {
        val ui = DeadUi(listOf("今日积分"))
        val control = RunControl()
        val agent = RewardsAgent(
            ui, searchCount = 0, readCount = 0, onProgress = {},
            control = control,
            dailySetEnabled = false, searchEnabled = false, readEnabled = false,
            recoverAttempts = 2, recoverGapMs = 1
        )
        val result = agent.run()

        assertFalse(result.success)
        // 关键：绝不进入暂停态等人工接管
        assertFalse("不应进入暂停等人状态", control.paused.value)
        assertTrue(result.message.contains("无法回到必应"))
    }

    // —— T4 断点续跑 ——

    @Test
    fun `断点续跑：已有进度时只补剩余次数`() = runBlocking {
        val today = java.time.LocalDate.now().toString()
        val mem = MemProgress(RunProgress(today, searchedDone = 3))
        val ui = FakeBingUi(listOf("今日积分"))
        val agent = RewardsAgent(
            ui, searchCount = 5, readCount = 0, onProgress = {},
            progress = mem, dailySetEnabled = false, readEnabled = false,
            searchGapMs = 1
        )
        agent.run()

        // 今日已跑 3 次，配置为 5 次 → 本次只补 2 次，累计落盘应为 5
        assertEquals(5, mem.p.searchedDone)
    }

    @Test
    fun `断点续跑：跨天进度不生效`() = runBlocking {
        val mem = MemProgress(RunProgress("2000-01-01", searchedDone = 3))
        val ui = FakeBingUi(listOf("今日积分"))
        val agent = RewardsAgent(
            ui, searchCount = 1, readCount = 0, onProgress = {},
            progress = mem, dailySetEnabled = false, readEnabled = false,
            searchGapMs = 1
        )
        agent.run()

        // 跨天进度失效：本次应完整执行 1 次，累计为 1（若错误补偿则会变成 0 次）
        assertEquals(1, mem.p.searchedDone)
    }
}
