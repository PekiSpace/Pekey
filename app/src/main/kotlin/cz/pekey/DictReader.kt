package cz.pekey

import java.text.Normalizer

/**
 * Čte slovníky AOSP ve formátu v2 (verze 202, soubory main_xx.dict) čistě v Kotlinu,
 * bez nativní knihovny. Pro gesta vrací jen nejčastější slova složená z písmen klávesnice.
 */
object DictReader {

    class WordList(
        /** Slova seřazená od nejčastějšího. */
        val words: Array<String>,
        /** Četnost 0–255 ze slovníku, stejné pořadí jako [words]. */
        val freqs: IntArray
    )

    private const val MAGIC = 0x9BC13AFE.toInt()
    private const val MAX_LEN = 48

    private const val F_CHILDREN = 0xC0
    private const val F_MULTI_CHARS = 0x20
    private const val F_TERMINAL = 0x10
    private const val F_SHORTCUTS = 0x08
    private const val F_BIGRAMS = 0x04
    private const val F_NOT_A_WORD = 0x02
    private const val F_OFFENSIVE = 0x01

    private const val NO_KEY = 0
    private const val SKIP = -1

    private val baseCache = HashMap<Int, Int>()

    /**
     * Písmeno klávesy (a–z) pro znak slova, např. č → c, Ř → r.
     * [SKIP] pro apostrof (don't), 0 pro znak, který na klávesnici není.
     */
    fun baseOf(cp: Int): Int {
        if (cp in 'a'.code..'z'.code) return cp
        if (cp in 'A'.code..'Z'.code) return cp + 32
        if (cp == '\''.code || cp == '’'.code) return SKIP
        synchronized(baseCache) {
            baseCache[cp]?.let { return it }
            val s = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
            val b = s[0].lowercaseChar().code
            val r = if (b in 'a'.code..'z'.code) b else NO_KEY
            baseCache[cp] = r
            return r
        }
    }

    /** Načte slovník a ponechá nejvýš [maxWords] nejčastějších slov (min. 2 písmena). */
    fun read(data: ByteArray, maxWords: Int): WordList {
        require(data.size > 16 && readInt(data, 0) == MAGIC) { "Neznámý formát slovníku" }
        val version = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        require(version == 2 || version == 202) { "Nepodporovaná verze slovníku: $version" }
        val root = readInt(data, 8)

        // 1. průchod: kolik slov má jakou četnost
        val hist = IntArray(256)
        Parser(data) { buf, len, freq -> if (usable(buf, len)) hist[freq]++ }.array(root, 0, true)
        var threshold = 0
        var total = 0
        for (f in 255 downTo 0) {
            if (total + hist[f] > maxWords) {
                threshold = f + 1
                break
            }
            total += hist[f]
        }

        // 2. průchod: posbírat slova, velikost písmen sjednotit (přednost má tvar malými)
        val map = HashMap<String, Pair<String, Int>>(total * 2)
        Parser(data) { buf, len, freq ->
            if (freq >= threshold && usable(buf, len)) {
                val word = String(buf, 0, len)
                val key = word.lowercase()
                val old = map[key]
                if (old == null) {
                    map[key] = word to freq
                } else {
                    val form = if (old.first == key || word != key) old.first else word
                    map[key] = form to maxOf(old.second, freq)
                }
            }
        }.array(root, 0, true)

        val sorted = map.values.sortedByDescending { it.second }
        return WordList(
            Array(sorted.size) { sorted[it].first },
            IntArray(sorted.size) { sorted[it].second }
        )
    }

    /** Aspoň dvě písmena a nejde o zkratku psanou celou velkými (SSE, PVO…). */
    private fun usable(buf: IntArray, len: Int): Boolean {
        if (len < 2) return false
        for (i in 0 until len) {
            if (!Character.isUpperCase(buf[i]) && Character.isLetter(buf[i])) return true
        }
        return false
    }

    private fun readInt(d: ByteArray, p: Int): Int =
        ((d[p].toInt() and 0xFF) shl 24) or ((d[p + 1].toInt() and 0xFF) shl 16) or
            ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)

    /** Průchod stromem slovníku (PATRICIA trie). */
    private class Parser(
        private val d: ByteArray,
        private val onWord: (IntArray, Int, Int) -> Unit
    ) {
        private val buf = IntArray(MAX_LEN)

        private fun u8(p: Int) = d[p].toInt() and 0xFF

        fun array(start: Int, depth: Int, prefixValid: Boolean) {
            var p = start
            var count = u8(p++)
            if ((count and 0x80) != 0) count = ((count and 0x7F) shl 8) or u8(p++)

            repeat(count) {
                val flags = u8(p++)
                var len = depth
                var valid = prefixValid
                while (true) {
                    val b = u8(p)
                    if ((flags and F_MULTI_CHARS) != 0 && b == 0x1F) {
                        p++
                        break
                    }
                    val cp: Int
                    if (b < 0x20) {
                        cp = (b shl 16) or (u8(p + 1) shl 8) or u8(p + 2)
                        p += 3
                    } else {
                        cp = b
                        p++
                    }
                    if (len < MAX_LEN) buf[len] = cp
                    len++
                    if (baseOf(cp) == NO_KEY) valid = false
                    if ((flags and F_MULTI_CHARS) == 0) break
                }

                var freq = -1
                if ((flags and F_TERMINAL) != 0) freq = u8(p++)

                var child = 0
                when (flags and F_CHILDREN) {
                    0x40 -> { child = p + u8(p); p += 1 }
                    0x80 -> { child = p + ((u8(p) shl 8) or u8(p + 1)); p += 2 }
                    0xC0 -> { child = p + ((u8(p) shl 16) or (u8(p + 1) shl 8) or u8(p + 2)); p += 3 }
                }

                if ((flags and F_SHORTCUTS) != 0) p += (u8(p) shl 8) or u8(p + 1)
                if ((flags and F_BIGRAMS) != 0) {
                    while (true) {
                        val bf = u8(p++)
                        p += when (bf and 0x30) {
                            0x10 -> 1
                            0x20 -> 2
                            0x30 -> 3
                            else -> 0
                        }
                        if ((bf and 0x80) == 0) break
                    }
                }

                val ok = valid && len <= MAX_LEN
                if (ok && freq >= 0 && (flags and (F_NOT_A_WORD or F_OFFENSIVE)) == 0) {
                    onWord(buf, len, freq)
                }
                // pod neplatnou předponou (číslice, symboly…) už žádné použitelné slovo není
                if (ok && child != 0 && len < MAX_LEN) array(child, len, true)
            }
        }
    }
}
