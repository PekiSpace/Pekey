/*
 * Rozpoznávání psaní gesty pro Pekey.
 *
 * Algoritmus vychází ze StatisticalGlideTypingClassifier z projektu FlorisBoard
 * (Copyright The FlorisBoard Contributors, Apache License 2.0,
 * https://github.com/florisboard/florisboard), který dále vychází z rozboru
 * Étienna Desticourta pro AnySoftKeyboard. Upraveno pro Pekey: vlastní čtení
 * slovníků, převzorkování na pevný počet bodů, skóre v logaritmech a čeština
 * s diakritikou (č se kreslí přes klávesu c).
 *
 * Licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package cz.pekey

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Převede tah prstem přes klávesy na nejpravděpodobnější slova.
 * Veškerá těžká práce běží na vlastním vlákně, výsledek se vrací na hlavní vlákno.
 */
class GestureEngine(private val ctx: Context) {

    /** Výsledek rozpoznání. [ready] = false, když se slovník teprve načítá nebo chybí. */
    class Result(val words: List<String>, val ready: Boolean, val error: String = "")

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Následující stav používá jen vlákno [worker].
    private val lists = HashMap<String, DictReader.WordList>()
    private val failed = HashMap<String, String>()
    private var geometry: KeyGeometry? = null
    private var index: Index? = null

    fun setGeometry(g: KeyGeometry) {
        worker.execute {
            geometry = g
            index = null
        }
    }

    /** Dopředu načte slovník pro jazyk (např. "cs_CZ"), aby první gesto nečekalo. */
    fun prepare(language: String?) {
        worker.execute { runCatching { indexFor(langOf(language)) } }
    }

    fun recognize(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        language: String?,
        max: Int,
        callback: (Result) -> Unit
    ) {
        val px = xs.copyOf(count)
        val py = ys.copyOf(count)
        worker.execute {
            val result = try {
                val lang = langOf(language)
                val idx = indexFor(lang)
                if (idx == null) {
                    Result(emptyList(), false, failed[lang] ?: "")
                } else {
                    Result(idx.classify(px, py, max), true)
                }
            } catch (t: Throwable) {
                Result(emptyList(), true, "${t.javaClass.simpleName}: ${t.message}")
            }
            main.post { callback(result) }
        }
    }

    fun close() {
        worker.shutdownNow()
    }

    // ---- načítání ----

    private fun langOf(language: String?): String =
        language?.substringBefore('_')?.lowercase()?.ifEmpty { null } ?: "cs"

    private fun indexFor(lang: String): Index? {
        val g = geometry ?: return null
        index?.let { if (it.lang == lang) return it }
        val list = wordsFor(lang) ?: return null
        return Index(lang, list, g).also { index = it }
    }

    private fun wordsFor(lang: String): DictReader.WordList? {
        lists[lang]?.let { return it }
        if (failed.containsKey(lang)) return null
        return try {
            val data = loadDict(lang) ?: throw IllegalStateException("Chybí slovník pro „$lang“")
            DictReader.read(data, MAX_WORDS).also { lists[lang] = it }
        } catch (t: Throwable) {
            failed[lang] = t.message ?: t.javaClass.simpleName
            null
        }
    }

    /** Přednost má slovník přidaný v aplikaci, jinak ten přibalený v APK. */
    private fun loadDict(lang: String): ByteArray? {
        val added = File(ctx.filesDir, "dicts").listFiles { f ->
            f.isFile && f.name.endsWith(".dict") &&
                f.name.removeSuffix(".dict").removePrefix("main_")
                    .substringBefore('_').lowercase() == lang
        }?.minByOrNull { it.name }
        if (added != null) return added.readBytes()
        return try {
            ctx.assets.open("dicts/main_$lang.dict").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    // ---- klasifikace ----

    /** Slova připravená pro konkrétní rozložení kláves. */
    private class Index(val lang: String, private val list: DictReader.WordList, g: KeyGeometry) {

        private val centerX = HashMap<Int, Float>()
        private val centerY = HashMap<Int, Float>()
        private val keyCodes = g.codes
        private val keyW: Float
        private val keyH: Float
        private val radius: Float
        private val minStep: Float

        /** Písmena kláves pro každé slovo (bez apostrofů). */
        private val letters: Array<IntArray>

        /** Délka ideální dráhy každého slova. */
        private val idealLength: FloatArray

        /** Slova podle dvojice (první klávesa, poslední klávesa). */
        private val byEnds = HashMap<Int, IntArray>()

        init {
            for (i in g.codes.indices) {
                centerX[g.codes[i]] = g.xs[i] + g.widths[i] / 2f
                centerY[g.codes[i]] = g.ys[i] + g.heights[i] / 2f
            }
            keyW = g.keyWidth.toFloat()
            keyH = g.keyHeight.toFloat()
            radius = min(keyW, keyH)
            minStep = keyW / 4f

            val n = list.words.size
            letters = Array(n) { i ->
                val w = list.words[i]
                val out = ArrayList<Int>(w.length)
                var k = 0
                while (k < w.length) {
                    val cp = w.codePointAt(k)
                    val b = DictReader.baseOf(cp)
                    if (b > 0 && centerX.containsKey(b)) out.add(b)
                    k += Character.charCount(cp)
                }
                out.toIntArray()
            }
            idealLength = FloatArray(n) { i -> pathLength(letters[i]) }

            val groups = HashMap<Int, ArrayList<Int>>()
            for (i in 0 until n) {
                val l = letters[i]
                if (l.size < 2) continue
                groups.getOrPut(pair(l[0], l[l.size - 1])) { ArrayList() }.add(i)
            }
            for ((k, v) in groups) byEnds[k] = v.toIntArray()
        }

        private fun pair(a: Int, b: Int) = (a shl 16) or b

        private fun pathLength(l: IntArray): Float {
            var len = 0f
            for (i in 1 until l.size) {
                len += dist(centerX[l[i - 1]]!!, centerY[l[i - 1]]!!, centerX[l[i]]!!, centerY[l[i]]!!)
            }
            return len
        }

        fun classify(rawX: FloatArray, rawY: FloatArray, max: Int): List<String> {
            // body blíž než čtvrt klávesy od předchozího se zahodí (šum prstu)
            val user = Path()
            for (i in rawX.indices) {
                if (user.size == 0 ||
                    dist(user.lastX(), user.lastY(), rawX[i], rawY[i]) > minStep ||
                    i == rawX.size - 1
                ) {
                    user.add(rawX[i], rawY[i])
                }
            }
            if (user.size < 2) return emptyList()

            val userLength = user.length()
            val userSampled = user.resample(SAMPLES)
            val userShape = userSampled.normalized()

            val starts = nearestKeys(user.xs[0], user.ys[0])
            val ends = nearestKeys(user.lastX(), user.lastY())

            val best = ArrayList<Pair<String, Double>>()
            val seen = HashSet<Int>()
            for (s in starts) for (e in ends) {
                val ids = byEnds[pair(s, e)] ?: continue
                for (id in ids) {
                    if (!seen.add(id)) continue
                    if (abs(userLength - idealLength[id]) > LENGTH_THRESHOLD * radius) continue
                    var score = Double.NEGATIVE_INFINITY
                    for (ideal in idealPaths(letters[id])) {
                        val sampled = ideal.resample(SAMPLES)
                        val shape = shapeDistance(sampled.normalized(), userShape)
                        val location = locationDistance(sampled, userSampled)
                        val s1 = logGauss(shape, SHAPE_STD) + logGauss(location, LOCATION_STD * radius)
                        if (s1 > score) score = s1
                    }
                    score += ln((list.freqs[id] + 1) / 256.0)
                    insert(best, list.words[id] to score, max)
                }
            }
            return best.map { it.first }
        }

        private fun insert(best: ArrayList<Pair<String, Double>>, item: Pair<String, Double>, max: Int) {
            var i = 0
            while (i < best.size && best[i].second >= item.second) i++
            if (i >= max) return
            best.add(i, item)
            if (best.size > max) best.removeAt(best.size - 1)
        }

        /** Dvě klávesy nejblíž bodu. */
        private fun nearestKeys(x: Float, y: Float): List<Int> =
            keyCodes.sortedBy { dist(centerX[it]!!, centerY[it]!!, x, y) }.take(2)

        /** Ideální dráha přes středy kláves; u zdvojených písmen i varianta se smyčkou. */
        private fun idealPaths(l: IntArray): List<Path> {
            val plain = Path()
            val loops = Path()
            var hasLoops = false
            var prev = -1
            for (c in l) {
                val cx = centerX[c]!!
                val cy = centerY[c]!!
                if (c == prev) {
                    val dx = keyW / 4f
                    val dy = keyH / 4f
                    loops.add(cx + dx, cy + dy)
                    loops.add(cx + dx, cy - dy)
                    loops.add(cx - dx, cy - dy)
                    loops.add(cx - dx, cy + dy)
                    hasLoops = true
                } else {
                    plain.add(cx, cy)
                    loops.add(cx, cy)
                }
                prev = c
            }
            return if (hasLoops) listOf(plain, loops) else listOf(plain)
        }

        private fun shapeDistance(a: Path, b: Path): Float {
            var total = 0f
            for (i in 0 until SAMPLES) total += dist(a.xs[i], a.ys[i], b.xs[i], b.ys[i])
            return total
        }

        private fun locationDistance(a: Path, b: Path): Float {
            var total = 0f
            for (i in 0 until SAMPLES) total += abs(a.xs[i] - b.xs[i]) + abs(a.ys[i] - b.ys[i])
            return total / SAMPLES / 2
        }

        private fun logGauss(value: Float, std: Float): Double {
            val z = value / std.toDouble()
            return -0.5 * z * z - ln(std * sqrt(2 * PI))
        }
    }

    /** Lomená čára. */
    private class Path(capacity: Int = 64) {
        var xs = FloatArray(capacity)
        var ys = FloatArray(capacity)
        var size = 0

        fun add(x: Float, y: Float) {
            if (size == xs.size) {
                xs = xs.copyOf(size * 2)
                ys = ys.copyOf(size * 2)
            }
            xs[size] = x
            ys[size] = y
            size++
        }

        fun lastX() = xs[size - 1]
        fun lastY() = ys[size - 1]

        fun length(): Float {
            var len = 0f
            for (i in 1 until size) len += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            return len
        }

        /** Přesně [n] bodů rovnoměrně rozložených po délce dráhy. */
        fun resample(n: Int): Path {
            val out = Path(n)
            val total = length()
            if (size < 2 || total <= 0f) {
                repeat(n) { out.add(xs[0], ys[0]) }
                return out
            }
            val step = total / (n - 1)
            out.add(xs[0], ys[0])
            var seg = 1
            var segStart = 0f
            var segLen = dist(xs[0], ys[0], xs[1], ys[1])
            for (k in 1 until n - 1) {
                val target = k * step
                while (seg < size - 1 && segStart + segLen < target) {
                    segStart += segLen
                    seg++
                    segLen = dist(xs[seg - 1], ys[seg - 1], xs[seg], ys[seg])
                }
                val t = if (segLen > 0f) ((target - segStart) / segLen).coerceIn(0f, 1f) else 0f
                out.add(
                    xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t,
                    ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t
                )
            }
            out.add(lastX(), lastY())
            return out
        }

        /** Posune do středu a zmenší tak, aby delší strana ohraničení měla délku 1. */
        fun normalized(): Path {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until size) {
                minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
                minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
            }
            val side = max(max(maxX - minX, maxY - minY), 0.00001f)
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f
            val out = Path(size)
            for (i in 0 until size) out.add((xs[i] - cx) / side, (ys[i] - cy) / side)
            return out
        }
    }

    private companion object {
        /** Kolik nejčastějších slov ze slovníku se použije pro gesta. */
        const val MAX_WORDS = 150_000

        /** Počet bodů, na které se převzorkuje každá dráha. */
        const val SAMPLES = 100

        /** Povolený rozdíl délek dráhy, v násobcích velikosti klávesy. */
        const val LENGTH_THRESHOLD = 8.42f

        /** Rozptyl tvaru (pro normalizované dráhy). FlorisBoard: 22,08 při 200 bodech. */
        const val SHAPE_STD = 22.08f * SAMPLES / 200f

        /** Rozptyl polohy v násobcích velikosti klávesy. */
        const val LOCATION_STD = 0.5109f

        fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return sqrt(dx * dx + dy * dy)
        }
    }
}
