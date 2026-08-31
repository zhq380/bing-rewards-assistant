package com.ripple.script.service

/**
 * 文本指纹与相似度工具：用于防重复阅读。
 *
 * 核心思路：
 * 1. 短标题（<8字）：直接用 normalize 后的全文做精确 key
 * 2. 长标题：取前 20 字符做"前缀指纹"快速过滤 + 3-gram Jaccard 相似度精确比较
 *    → 解决标题被截断/省略号/标点差异导致的不匹配问题
 */
object TextFingerprint {

    /** 规范化：去空白标点、转小写，只保留中文和字母数字 */
    fun normalize(s: String): String {
        return s.replace(Regex("[\\s\\p{P}\\p{S}…]"), "").lowercase()
    }

    /** 前缀指纹：取规范化后前 min(len,20) 字符，用于快速 hash 查找 */
    fun prefixKey(s: String): String {
        val n = normalize(s)
        return n.take(20)
    }

    /** 3-gram 集合：把字符串拆成连续 3 字符片段集合 */
    private fun trigrams(s: String): Set<String> {
        val n = normalize(s)
        if (n.length < 3) return setOf(n)
        return (0..n.length - 3).map { i -> n.substring(i, i + 3) }.toSet()
    }

    /**
     * Jaccard 相似度：两个 3-gram 集合的交集/并集比例（0~1）。
     * 对标题截断、标点差异、个别字不同有较好容错。
     */
    fun similarity(a: String, b: String): Double {
        val ta = trigrams(a)
        val tb = trigrams(b)
        if (ta.isEmpty() && tb.isEmpty()) return 1.0
        val inter = ta.intersect(tb).size
        val union = ta.union(tb).size
        return inter.toDouble() / union
    }

    /**
     * 已读标题库：维护一个前缀指纹 → 标题列表的索引。
     * 判断新标题是否已读：
     * 1. 前缀指纹完全匹配 → 可能已读，进一步做相似度比较
     * 2. 相似度 >= threshold → 判定为已读
     */
    class SeenIndex(private val threshold: Double = 0.65) {
        // 前缀指纹 → 已读标题原文列表
        private val index = HashMap<String, MutableList<String>>()
        // 所有已读的完整 key（含短标题精确匹配）
        private val exactSet = HashSet<String>()

        /** 添加一个已读标题 */
        fun add(title: String) {
            val key = prefixKey(title)
            if (key.length < 8) {
                exactSet.add(key)
            } else {
                index.getOrPut(key) { mutableListOf() }.add(title)
            }
        }

        /** 检查标题是否已读（含模糊匹配） */
        fun contains(title: String): Boolean {
            val key = prefixKey(title)
            if (key.length < 8) return key in exactSet
            // 前缀指纹精确命中
            if (key in exactSet) return true
            // 同前缀的已读标题列表 → 逐个算相似度
            val candidates = index[key] ?: return false
            return candidates.any { similarity(title, it) >= threshold }
        }

        fun size(): Int = exactSet.size + index.values.sumOf { it.size }

        fun clear() { exactSet.clear(); index.clear() }
    }
}
