package com.ripple.script.data

import android.content.Context

/** 必应搜索词库（assets/search_kw.txt 的加载与清洗） */
object SearchKeywords {

    /** 逐行 trim、丢弃空白行、distinct 去重（纯逻辑，不依赖 Context，便于 JVM 单测） */
    fun sanitize(raw: List<String>): List<String> =
        raw.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** 读取 assets 中 search_kw.txt 的每一行并做清洗 */
    fun load(context: Context): List<String> =
        sanitize(context.assets.open("search_kw.txt").bufferedReader().readLines())

    private val CJK_RANGE = Regex("[\u4e00-\u9fff]")

    /** 统计列表中的中文词数量（供测试校验均衡） */
    fun countZh(words: List<String>): Int = words.count { w -> CJK_RANGE.containsMatchIn(w) }

    /** 统计列表中的英文词数量（供测试校验均衡） */
    fun countEn(words: List<String>): Int = words.size - countZh(words)
}