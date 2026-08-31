package com.ripple.script.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RewardsStoreTest {

    @Test
    fun `RewardParams 默认两套脚本参数`() {
        val p = RewardParams()
        assertEquals(5 * 60_000L, p.keepScreenMs)
        assertEquals(5, p.main.searchCount)
        assertEquals(4, p.main.readCount)
        assertEquals(0L, p.main.bingTargetSerial)
        // 分身默认跳转到 ColorOS 应用分身
        assertEquals(999L, p.clone.bingTargetSerial)
    }

    @Test
    fun `主分身脚本参数各自独立可配置`() {
        val p = RewardParams(
            keepScreenMs = 3 * 60_000L,
            main = ScriptParams(searchCount = 20, readCount = 6),
            clone = ScriptParams(searchCount = 10, readCount = 3, bingTargetSerial = 999L)
        )
        assertEquals(20, p.main.searchCount)
        assertEquals(6, p.main.readCount)
        assertEquals(10, p.clone.searchCount)
        assertEquals(3, p.clone.readCount)
        assertEquals(3 * 60_000L, p.keepScreenMs)
    }
}