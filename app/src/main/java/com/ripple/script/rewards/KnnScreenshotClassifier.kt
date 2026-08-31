package com.ripple.script.rewards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * 端侧图像分类器（B 路线 v3）：z-score 标准化 + 欧氏距离 kNN 加权多数表决。
 *
 * 特征与 PC 端训练脚本 train/train_proto.py 严格对齐（downsample=40，共 1705 维）：
 *   1) 40x40 灰度降采样（1600 维）
 *   2) 边缘密度 4x4 分块均值（16 维）
 *   3) HSV 颜色直方图（40 维）
 *   4) 4x4 块平均 RGB（48 维）
 *   5) 全局亮度（1 维）
 *
 * 与 v2 相比：
 *   - 用每维 z-score（mean/std 来自训练集）替代 L2 归一化，放大判别性维度；
 *   - 距离用欧氏而非余弦，避免被大片白色背景主导；
 *   - 强门槛：gap（第二近/最近距离比）>= gap_threshold 且最近距离 <= dist_cap 才开口，
 *     保证只在“领域内且类间边界清晰”时输出，否则返回 null 交给规则引擎。
 *
 * 使用策略：规则引擎优先（OCR 文本判据可靠），本模型仅在规则判定 UNKNOWN 时兜底。
 */
class KnnScreenshotClassifier(
    private val context: Context,
    private val assetName: String = "protos.json"
) : ScreenshotClassifier {

    private val dim = 40
    private val featDim = dim * dim + 16 + 40 + 48 + 1

    private var loaded = false
    private var knnK = 3
    private var gapThreshold = 1.3f
    private var distCap = 110f
    private var classes: List<String> = emptyList()
    private var samples: Map<String, List<FloatArray>> = emptyMap()
    private var zmean: FloatArray = FloatArray(0)
    private var zstd: FloatArray = FloatArray(0)

    @Synchronized
    override fun isAvailable(): Boolean {
        if (!loaded) ensureLoaded()
        return loaded
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = loadFromAssets()
        if (!loaded) {
            android.util.Log.e("KnnCls", "protos.json 加载失败，ML 分类不可用，回退规则引擎")
        }
    }

    private fun loadFromAssets(): Boolean {
        return try {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            if (root.optInt("version", 1) < 3) {
                android.util.Log.e("KnnCls", "protos.json 版本过低，需要 v3")
                return false
            }
            gapThreshold = root.optDouble("gap_threshold", 1.3).toFloat()
            distCap = root.optDouble("dist_cap", 110.0).toFloat()
            knnK = root.optInt("knn_k", 3)
            classes = buildList {
                val arr = root.optJSONArray("classes") ?: return false
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
            zmean = root.getJSONArray("zmean").let { arr ->
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
            zstd = root.getJSONArray("zstd").let { arr ->
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
            if (zmean.size != featDim || zstd.size != featDim) return false
            val raw = root.optJSONObject("samples") ?: return false
            samples = buildMap {
                for (cls in classes) {
                    val arr = raw.optJSONArray(cls) ?: continue
                    val list = mutableListOf<FloatArray>()
                    for (i in 0 until arr.length()) {
                        val vec = arr.getJSONArray(i)
                        val f = FloatArray(vec.length())
                        for (j in 0 until vec.length()) f[j] = vec.getDouble(j).toFloat()
                        list.add(f)
                    }
                    if (list.isNotEmpty()) put(cls, list)
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("KnnCls", "加载 protos.json 失败: ${e.message}")
            false
        }
    }

    override suspend fun classify(bitmap: Bitmap): PageType? {
        return classifyConfident(bitmap)?.first
    }

    /** 带门槛的强判定：通过门槛返回 (类型, 置信分)，否则 null。置信分 = 最近距离倒数（越大越可靠） */
    override suspend fun classifyConfident(bitmap: Bitmap): Pair<PageType, Float>? {
        ensureLoaded()
        if (!loaded) return null
        return runCatching {
            val feat = extractFeatures(bitmap)
            val q = FloatArray(featDim)
            for (i in 0 until featDim) {
                if (zstd[i] > 1e-8f) q[i] = (feat[i] - zmean[i]) / zstd[i] else q[i] = 0f
            }
            predict(q)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // kNN 预测（欧氏距离 + 加权多数）
    // ------------------------------------------------------------------

    private fun predict(q: FloatArray): Pair<PageType, Float>? {
        // 距离最近的前 knnK 个样本
        val best = ArrayList<Pair<String, Float>>()  // (类, 距离)
        var totalCnt = 0
        for ((cls, vecs) in samples) {
            for (v in vecs) {
                if (v.size != q.size) continue
                best.add(Pair(cls, euclid(q, v)))
                totalCnt++
            }
        }
        if (best.size < knnK) return null
        best.sortBy { it.second }

        val top3 = best.subList(0, minOf(knnK, best.size))
        val d1 = top3[0].second
        if (d1 > distCap) return null   // 距领域太远，拒绝开口

        val votes = HashMap<String, Int>()
        for ((cls, d) in top3) {
            votes[cls] = (votes[cls] ?: 0) + 1
        }
        val winner = votes.maxByOrNull { it.value } ?: return null

        // 门槛：第二近/最近 距离比 >= gap（类间边界清晰）
        val d2 = top3[1].second
        val gap = if (d1 > 1e-6f) d2 / d1 else 99f
        if (gap < gapThreshold) return null

        val mapped = mapClass(winner.key) ?: return null  // unknown 类不输出
        return Pair(mapped, 1f / (d1 + 1e-6f))
    }

    private fun mapClass(name: String): PageType? = when (name) {
        "rewards_home" -> PageType.REWARDS_HOME
        "article" -> PageType.ARTICLE
        "search_result" -> PageType.SEARCH_RESULT
        "ad_page" -> PageType.AD_PAGE
        else -> null  // unknown 不输出，交给规则引擎
    }

    // ------------------------------------------------------------------
    // 特征提取（与 train_proto.py 对齐）
    // ------------------------------------------------------------------

    private fun extractFeatures(bmp: Bitmap): FloatArray {
        val n = dim
        val small = Bitmap.createScaledBitmap(bmp, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)

        // 1) 灰度降采样（PIL 'L' = 601 公式）
        val gp = FloatArray(n * n)
        val hCount = IntArray(16)
        val sCount = IntArray(16)
        val vCount = IntArray(8)
        val hsv = FloatArray(3)
        for (i in px.indices) {
            val argb = px[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            gp[i] = (r * 299 + g * 587 + b * 114) / 1000f / 255f
            Color.colorToHSV(argb, hsv)
            val hb = ((hsv[0] / 360f) * 255f / 256f * 16f).toInt().coerceIn(0, 15)
            val sb = (hsv[1] * 255f / 256f * 16f).toInt().coerceIn(0, 15)
            val vb = (hsv[2] * 255f / 256f * 8f).toInt().coerceIn(0, 7)
            hCount[hb]++
            sCount[sb]++
            vCount[vb]++
        }

        // 2) 边缘密度 4x4 块均值（39x39 梯度）
        val eh = n - 1
        val edge = FloatArray(eh * eh)
        for (y in 0 until eh) {
            for (x in 0 until eh) {
                val dx = Math.abs(gp[y * n + x + 1] - gp[y * n + x])
                val dy = Math.abs(gp[(y + 1) * n + x] - gp[y * n + x])
                edge[y * eh + x] = minOf(dx + dy, 1f)
            }
        }
        val blocks = FloatArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                val fromY = i * eh / 4
                val toY = (i + 1) * eh / 4
                val fromX = j * eh / 4
                val toX = (j + 1) * eh / 4
                var sum = 0f
                for (y in fromY until toY) {
                    for (x in fromX until toX) sum += edge[y * eh + x]
                }
                blocks[i * 4 + j] = sum / ((toY - fromY) * (toX - fromX))
            }
        }

        // 3) HSV 直方图（概率）
        val hist = FloatArray(40)
        for (k in 0 until 16) hist[k] = hCount[k] / (n * n).toFloat()
        for (k in 0 until 16) hist[16 + k] = sCount[k] / (n * n).toFloat()
        for (k in 0 until 8) hist[32 + k] = vCount[k] / (n * n).toFloat()

        // 4) 4x4 块平均 RGB
        val cblocks = FloatArray(48)
        var ci = 0
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                val fromY = i * n / 4
                val toY = (i + 1) * n / 4
                val fromX = j * n / 4
                val toX = (j + 1) * n / 4
                var sr = 0f; var sg = 0f; var sb = 0f
                var cnt = 0
                for (y in fromY until toY) {
                    for (x in fromX until toX) {
                        val argb = px[y * n + x]
                        sr += ((argb shr 16) and 0xFF) / 255f
                        sg += ((argb shr 8) and 0xFF) / 255f
                        sb += (argb and 0xFF) / 255f
                        cnt++
                    }
                }
                cblocks[ci++] = sr / cnt
                cblocks[ci++] = sg / cnt
                cblocks[ci++] = sb / cnt
            }
        }

        // 5) 亮度
        var bright = 0f
        for (v in gp) bright += v
        bright /= (n * n)

        val feat = FloatArray(featDim)
        System.arraycopy(gp, 0, feat, 0, gp.size)
        System.arraycopy(blocks, 0, feat, gp.size, blocks.size)
        System.arraycopy(hist, 0, feat, gp.size + blocks.size, hist.size)
        System.arraycopy(cblocks, 0, feat, gp.size + blocks.size + hist.size, cblocks.size)
        feat[featDim - 1] = bright
        return feat
    }

    private fun euclid(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += d * d
        }
        return Math.sqrt(s.toDouble()).toFloat()
    }
}