package com.ripple.script.rewards

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** OCR 识别到的文本行及其屏幕坐标（bounds） */
data class OcrBlock(val text: String, val bounds: Rect)

/** 一次识别的完整结果：全文 + 带坐标的文本行 */
data class OcrResult(val allText: String, val lines: List<OcrBlock>)

/**
 * 离线中文 OCR（Google ML Kit），用于读取屏幕截图中的文字。
 * 分身（u999）场景下无障碍读不到必应内容，改用截屏 + OCR 识别任务卡片。
 *
 * 性能：单次全屏识别数百 ms~2s，是搜索场景的主要耗时来源。这里做两层优化：
 * 1) 全文与行坐标合并为一次识别（原来 recognize + recognizeLines 要跑两遍）；
 * 2) 短 TTL 缓存——同一张截图（采样指纹一致）在 [CACHE_TTL_MS] 内直接复用，
 *    消除同一页面连续判定（如 isOnRewardsPage → isAllTasksDone）的重复开销。
 */
object ScreenOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private const val CACHE_TTL_MS = 1_000L

    private val lock = Any()
    private var cacheKey: String? = null
    private var cacheValue: OcrResult? = null
    private var cacheAt = 0L

    /** 识别整张图片，返回合并后的全部文字；失败/无文字返回空串 */
    suspend fun recognize(bitmap: Bitmap): String = recognizeBoth(bitmap).allText

    /**
     * 按行识别并保留坐标（点「赚取 N 积分」按钮等需要精确位置）。
     * 失败/无文字返回空列表。
     */
    suspend fun recognizeLines(bitmap: Bitmap): List<OcrBlock> = recognizeBoth(bitmap).lines

    /** 一次识别同时产出全文与行坐标（命中缓存则不重复识别） */
    suspend fun recognizeBoth(bitmap: Bitmap): OcrResult {
        val key = fingerprint(bitmap)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (key == cacheKey && now - cacheAt < CACHE_TTL_MS) {
                cacheValue?.let { return it }
            }
        }
        val started = System.currentTimeMillis()
        val result = process(bitmap)
        android.util.Log.d("RippleOcr", "识别耗时 ${System.currentTimeMillis() - started}ms, 行数=${result.lines.size}")
        synchronized(lock) {
            cacheKey = key
            cacheValue = result
            cacheAt = System.currentTimeMillis()
        }
        return result
    }

    /**
     * 截图采样指纹：尺寸 + 8x8 稀疏采样点。
     * 开销在微秒级，远低于全像素 hash，也远低于一次 OCR。
     */
    private fun fingerprint(b: Bitmap): String {
        val stepX = (b.width / 8).coerceAtLeast(1)
        val stepY = (b.height / 8).coerceAtLeast(1)
        val sb = StringBuilder(128)
        sb.append(b.width).append('x').append(b.height).append('x').append(b.config).append('|')
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                sb.append(b.getPixel(x, y)).append(',')
                x += stepX
            }
            y += stepY
        }
        return sb.toString()
    }

    private suspend fun process(bitmap: Bitmap): OcrResult =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    if (!cont.isActive) return@addOnSuccessListener
                    val lines = mutableListOf<OcrBlock>()
                    for (b in text.textBlocks) {
                        for (l in b.lines) {
                            val bb = l.boundingBox
                            if (bb != null && l.text.isNotBlank()) lines.add(OcrBlock(l.text.trim(), bb))
                        }
                    }
                    cont.resume(OcrResult(text.text, lines))
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(OcrResult("", emptyList()))
                }
        }
}