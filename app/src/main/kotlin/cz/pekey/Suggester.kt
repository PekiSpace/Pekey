package cz.pekey

import android.content.Context
import com.android.inputmethod.keyboard.ProximityInfo
import com.android.inputmethod.latin.BinaryDictionary
import com.android.inputmethod.latin.DicTraverseSession
import java.io.File

/** Rozložení písmenných kláves v souřadnicích klávesnice (pro nativní knihovnu). */
class KeyGeometry(
    val width: Int,
    val height: Int,
    val keyWidth: Int,
    val keyHeight: Int,
    val codes: IntArray,
    val xs: IntArray,
    val ys: IntArray,
    val widths: IntArray,
    val heights: IntArray
)

class Suggester(private val ctx: Context) {

    private class Dict(val handle: Long, val session: Long)

    private var dicts: Map<String, Dict> = emptyMap()
    private var signature = ""
    private var libReady = false
    private var proximity = 0L
    private var geometry: KeyGeometry? = null

    var lastError: String = ""
        private set

    /** Načte knihovnu a otevře slovníky; opakovaně volat je levné. */
    fun refresh() {
        try {
            if (!libReady) libReady = GestureLib.load(ctx).startsWith("Knihovna je načtená")
            if (!libReady) return

            val dir = File(ctx.filesDir, "dicts")
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".dict") }
                ?.sortedBy { it.name }.orEmpty()
            val sig = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
            if (sig == signature) return

            closeDicts()
            signature = sig
            val map = HashMap<String, Dict>()
            for (f in files) {
                val rest = f.name.removeSuffix(".dict").removePrefix("main_")
                val lang = rest.substringBefore('_').lowercase()
                if (map.containsKey(lang)) continue
                val handle = BinaryDictionary.openNative(f.absolutePath, 0L, f.length(), false)
                if (handle == 0L) {
                    lastError = "Slovník ${f.name} se nepodařilo otevřít"
                    continue
                }
                val session = DicTraverseSession.setDicTraverseSessionNative(localeOf(rest), f.length())
                if (session == 0L) {
                    BinaryDictionary.closeNative(handle)
                    continue
                }
                DicTraverseSession.initDicTraverseSessionNative(session, handle, IntArray(0), 0)
                map[lang] = Dict(handle, session)
            }
            dicts = map
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun setGeometry(g: KeyGeometry) {
        geometry = g
        releaseProximity()
    }

    /** Vrátí až 18 návrhů pro rozepsané slovo. Prázdný seznam, když nic není k dispozici. */
    fun suggest(word: String, language: String?): List<String> {
        if (!libReady || word.isEmpty()) return emptyList()
        return try {
            val lang = language?.substringBefore('_')?.lowercase()
            val dict = dicts[lang] ?: dicts.values.firstOrNull() ?: return emptyList()
            val cps = word.codePoints().toArray()
            if (cps.isEmpty() || cps.size >= MAX_WORD) return emptyList()

            val input = IntArray(MAX_WORD) { -1 }
            cps.copyInto(input)
            val xs = IntArray(MAX_WORD) { -1 }
            val ys = IntArray(MAX_WORD) { -1 }
            val times = IntArray(MAX_WORD)
            val pointers = IntArray(MAX_WORD)
            val options = IntArray(8)
            options[2] = 1      // blokovat urážlivá slova
            options[4] = 1000   // váha jazyka
            val prev = Array(MAX_PREV) { IntArray(0) }
            val outCount = IntArray(1)
            val outCps = IntArray(MAX_WORD * MAX_RESULTS)
            val scores = IntArray(MAX_RESULTS)
            val indices = IntArray(MAX_RESULTS)
            val types = IntArray(MAX_RESULTS)
            val confidence = IntArray(1)
            val weight = floatArrayOf(-1f)

            BinaryDictionary.getSuggestionsNative(
                dict.handle, ensureProximity(), dict.session,
                xs, ys, times, pointers,
                input, cps.size, options,
                prev, BooleanArray(MAX_PREV), 0,
                outCount, outCps, scores, indices, types, confidence, weight
            )

            val result = ArrayList<String>()
            val count = outCount[0].coerceIn(0, MAX_RESULTS)
            for (i in 0 until count) {
                if ((types[i] and 0xFF) == KIND_BLACKLIST) continue
                val start = i * MAX_WORD
                var len = 0
                while (len < MAX_WORD && outCps[start + len] != 0) len++
                if (len == 0) continue
                val s = String(outCps, start, len)
                if (s.contains(' ')) continue
                result.add(matchCase(word, s))
            }
            result
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            emptyList()
        }
    }

    fun selfTest(): String {
        refresh()
        if (!libReady) return "Knihovna není načtená. Přidej .so a zkus znovu."
        if (dicts.isEmpty()) {
            return "Žádný slovník se nepodařilo otevřít." +
                if (lastError.isNotEmpty()) "\n$lastError" else ""
        }
        val sb = StringBuilder()
        for (lang in dicts.keys.sorted()) {
            val sample = if (lang == "cs") "klavesn" else "keybo"
            val found = suggest(sample, lang).take(5).joinToString(", ").ifEmpty { "(nic)" }
            sb.append("$lang: $sample → $found\n")
        }
        if (lastError.isNotEmpty()) sb.append(lastError)
        return sb.toString().trim()
    }

    fun close() {
        releaseProximity()
        closeDicts()
    }

    // ---- interní ----

    private fun closeDicts() {
        if (libReady) {
            for (d in dicts.values) {
                try {
                    DicTraverseSession.releaseDicTraverseSessionNative(d.session)
                    BinaryDictionary.closeNative(d.handle)
                } catch (_: Throwable) {
                }
            }
        }
        dicts = emptyMap()
        signature = ""
    }

    private fun releaseProximity() {
        if (proximity != 0L && libReady) {
            try {
                ProximityInfo.releaseProximityInfoNative(proximity)
            } catch (_: Throwable) {
            }
        }
        proximity = 0L
    }

    private fun ensureProximity(): Long {
        if (proximity == 0L) proximity = buildProximity(geometry ?: defaultGeometry())
        return proximity
    }

    private fun buildProximity(g: KeyGeometry): Long {
        val n = g.codes.size
        val cellW = (g.width + GRID_W - 1) / GRID_W
        val cellH = (g.height + GRID_H - 1) / GRID_H
        val limit = (g.keyWidth * 1.2f).let { it * it }
        val chars = IntArray(GRID_W * GRID_H * MAX_PROX) { -1 }
        val dist = FloatArray(n)

        for (gy in 0 until GRID_H) {
            for (gx in 0 until GRID_W) {
                val cx = gx * cellW + cellW / 2f
                val cy = gy * cellH + cellH / 2f
                val near = ArrayList<Int>()
                for (i in 0 until n) {
                    val dx = maxOf(g.xs[i] - cx, 0f, cx - (g.xs[i] + g.widths[i]))
                    val dy = maxOf(g.ys[i] - cy, 0f, cy - (g.ys[i] + g.heights[i]))
                    val dd = dx * dx + dy * dy
                    if (dd < limit) {
                        dist[i] = dd
                        near.add(i)
                    }
                }
                near.sortBy { dist[it] }
                val base = (gy * GRID_W + gx) * MAX_PROX
                for (k in 0 until minOf(near.size, MAX_PROX)) {
                    chars[base + k] = g.codes[near[k]]
                }
            }
        }

        val centerX = FloatArray(n) { g.xs[it] + g.widths[it] / 2f }
        val centerY = FloatArray(n) { g.ys[it] + g.heights[it] / 2f }
        val radius = FloatArray(n) { g.widths[it] * 0.6f }

        return ProximityInfo.setProximityInfoNative(
            g.width, g.height, GRID_W, GRID_H, g.keyWidth, g.keyHeight,
            chars, n, g.xs, g.ys, g.widths, g.heights, g.codes,
            centerX, centerY, radius
        )
    }

    /** Záložní rozložení pro test v aplikaci, kde klávesnice ještě není vykreslená. */
    private fun defaultGeometry(): KeyGeometry {
        val rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
        val w = 1080
        val kw = 104
        val kh = 140
        val codes = ArrayList<Int>()
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        rows.forEachIndexed { r, row ->
            val offset = (w - row.length * kw) / 2
            row.forEachIndexed { i, ch ->
                codes.add(ch.code)
                xs.add(offset + i * kw)
                ys.add(160 + r * (kh + 8))
            }
        }
        val n = codes.size
        return KeyGeometry(
            w, 160 + 4 * (kh + 8), kw, kh,
            codes.toIntArray(), xs.toIntArray(), ys.toIntArray(),
            IntArray(n) { kw }, IntArray(n) { kh }
        )
    }

    private fun localeOf(rest: String): String {
        val p = rest.split('_')
        return if (p.size >= 2) p[0].lowercase() + "_" + p[1].uppercase() else p[0].lowercase()
    }

    private fun matchCase(typed: String, s: String): String {
        if (!Character.isUpperCase(typed.codePointAt(0))) return s
        val allUpper = typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }
        return if (allUpper) s.uppercase() else s.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        const val GRID_W = 32
        const val GRID_H = 16
        const val MAX_PROX = 16
        const val MAX_WORD = 48
        const val MAX_RESULTS = 18
        const val MAX_PREV = 3
        const val KIND_BLACKLIST = 4
    }
}