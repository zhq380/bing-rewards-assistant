package com.ripple.script.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeywordsTest {

    private val zhWords = listOf(
        "天气预报", "空气质量", "大熊猫保护", "家常菜谱", "人工智能",
        "简历模板", "故宫建筑", "手机摄影", "成语接龙", "电影推荐",
    )

    private val enWords = listOf(
        "mountain", "river", "forest", "ocean", "tiger", "panda",
        "coffee", "teacher", "algorithm", "journey",
    )

    @Test
    fun `空行与空白被过滤`() {
        val raw = listOf("  hello  ", "", "   ", "\t", "world", "\tapple\n")
        val result = SearchKeywords.sanitize(raw)
        assertEquals(3, result.size)
        assertEquals(listOf("hello", "world", "apple"), result)
    }

    @Test
    fun `重复词被去重且保序`() {
        val raw = listOf("a", "b", "a", "b", "c", "c", "a")
        val result = SearchKeywords.sanitize(raw)
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `含中文词的输入中文数量足够`() {
        val raw = zhWords + enWords + zhWords.take(3) // 混入重复
        val result = SearchKeywords.sanitize(raw)
        assertTrue("中文数量应足够多", SearchKeywords.countZh(result) >= zhWords.size)
        assertTrue("英文数量应足够多", SearchKeywords.countEn(result) >= enWords.size)
        // 中英总量 = 去重后全部词
        assertEquals(zhWords.size + enWords.size, result.size)
    }

    @Test
    fun `全中文与全英文输入分别统计`() {
        assertEquals(zhWords.size, SearchKeywords.countZh(zhWords))
        assertEquals(0, SearchKeywords.countEn(zhWords))
        assertEquals(0, SearchKeywords.countZh(enWords))
        assertEquals(enWords.size, SearchKeywords.countEn(enWords))
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(SearchKeywords.sanitize(emptyList()).isEmpty())
    }
}