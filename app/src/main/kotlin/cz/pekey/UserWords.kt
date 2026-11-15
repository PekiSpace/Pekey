package cz.pekey

import android.content.Context
import java.io.File
import java.text.Normalizer
import java.util.concurrent.Executors

/**
 * Naučená slova: slova, která píšeš a nejsou ve slovníku.
 *
 * Slovo se naučí, když ho napíšeš [LEARN_AFTER]× (pokaždé potvrzené mezerou, Enterem
 * nebo interpunkcí), nebo hned, když v liště klepneš na přesně napsané slovo.
 * Ukládá se do souboru user_words.txt ve vnitřním úložišti aplikace, nikam se neposílá.
 * Sdílí ho klávesnice i aplikace (běží ve stejném procesu).
 */
class UserWords private constructor(ctx: Context) {

    class Entry(val lang: String, val word: String, var count: Int)

    private val file = File(ctx.filesDir, "user_words.txt")
    private val entries = LinkedHashMap<String, Entry>()
    private val saver = Executors.newSingleThreadExecutor()

    /** Jak často používáš jednotlivá slova (i ta ze slovníku); zlepšuje pořadí gest. */
    private val usageFile = File(ctx.filesDir, "word_usage.txt")
    private val usage = HashMap<String, Int>()
    private var usageSavePending = false

    /** Osobní dvojice slov (předchozí → další) s počtem použití. */
    private val pairsFile = File(ctx.filesDir, "word_pairs.txt")
    private val pairs = HashMap<String, Int>()

    /** Zvyšuje se při každé změně; gesta podle něj poznají, že mají přestavět seznam. */
    @Volatile
    var version = 0
        private set

    init {
        load()
        loadUsage()
        loadPairs()
    }

    // ---- osobní dvojice slov ----

    private fun pairKey(prev: String, word: String, lang: String) =
        "$lang\t${prev.lowercase()}\t${word.lowercase()}"

    /** Slovo [word] napsané nebo vybrané hned po [prev]. */
    @Synchronized
    fun usePair(prev: String?, word: String, lang: String) {
        if (prev.isNullOrBlank() || word.isBlank()) return
        if (!prev.all { it.isLetter() || it == '\'' || it == '-' }) return
        if (!word.all { it.isLetter() || it == '\'' || it == '-' }) return
        val k = pairKey(prev, word, lang)
        pairs[k] = (pairs[k] ?: 0) + 1
        if (pairs.size > MAX_PAIRS) {
            pairs.entries.sortedBy { it.value }.take(pairs.size - MAX_PAIRS).map { it.key }.forEach { pairs.remove(it) }
        }
        scheduleUsageSave()
    }

    @Synchronized
    fun pairCount(prev: String?, word: String, lang: String): Int =
        if (prev.isNullOrBlank()) 0 else pairs[pairKey(prev, word, lang)] ?: 0

    /** Slova, která po [prev] píšeš nejčastěji. */
    @Synchronized
    fun nextFor(prev: String?, lang: String, limit: Int): List<String> {
        if (prev.isNullOrBlank()) return emptyList()
        val prefix = "$lang\t${prev.lowercase()}\t"
        return pairs.entries.asSequence()
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key.substring(prefix.length) }
            .toList()
    }

    private fun loadPairs() {
        runCatching {
            if (!pairsFile.exists()) return
            pairsFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 4) {
                    val n = p[3].toIntOrNull() ?: return@forEachLine
                    pairs["${p[0]}\t${p[1]}\t${p[2]}"] = n
                }
            }
        }
    }

    // ---- četnost používání ----

    /** Napsané a potvrzené slovo (jakékoli). */
    @Synchronized
    fun use(word: String, lang: String) {
        val w = word.trim().lowercase()
        if (w.length < MIN_LEN || w.length > MAX_LEN || !w.all { it.isLetter() || it == '\'' || it == '-' }) return
        val k = "$lang\t$w"
        usage[k] = (usage[k] ?: 0) + 1
        if (usage.size > MAX_USAGE) {
            val drop = usage.entries.sortedBy { it.value }.take(usage.size - MAX_USAGE).map { it.key }
            drop.forEach { usage.remove(it) }
        }
        scheduleUsageSave()
    }

    @Synchronized
    fun usageOf(word: String, lang: String): Int = usage["$lang\t${word.lowercase()}"] ?: 0

    private fun scheduleUsageSave() {
        if (usageSavePending) return
        usageSavePending = true
        saver.execute {
            // víc slov za sebou se uloží najednou
            runCatching { Thread.sleep(USAGE_SAVE_DELAY_MS) }
            val (snapshot, pairSnapshot) = synchronized(this) {
                usageSavePending = false
                usage.entries.joinToString("\n") { "${it.key}\t${it.value}" } to
                    pairs.entries.joinToString("\n") { "${it.key}\t${it.value}" }
            }
            runCatching {
                val tmp = File(usageFile.path + ".tmp")
                tmp.writeText(snapshot)
                tmp.renameTo(usageFile)
                val tmp2 = File(pairsFile.path + ".tmp")
                tmp2.writeText(pairSnapshot)
                tmp2.renameTo(pairsFile)
            }
        }
    }

    private fun loadUsage() {
        runCatching {
            if (!usageFile.exists()) return
            usageFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 3) {
                    val n = p[2].toIntOrNull() ?: return@forEachLine
                    usage["${p[0]}\t${p[1]}"] = n
                }
            }
        }
    }

    // ---- učení ----

    /** Slovo napsané a potvrzené uživatelem. [known] = je ve slovníku (null = nevíme). */
    @Synchronized
    fun record(word: String, lang: String, known: Boolean?) {
        val w = normalize(word) ?: return
        val k = key(lang, w)
        val e = entries[k]
        if (e != null) {
            e.count++
        } else {
            if (known == true) return
            entries[k] = Entry(lang, w, 1)
        }
        changed()
    }

    /** Uživatel slovo výslovně potvrdil (klepl na něj v liště): naučí se hned. */
    @Synchronized
    fun learnNow(word: String, lang: String) {
        val w = normalize(word) ?: return
        val k = key(lang, w)
        val e = entries.getOrPut(k) { Entry(lang, w, 0) }
        e.count = maxOf(e.count + 1, LEARN_AFTER)
        changed()
    }

    @Synchronized
    fun isLearned(word: String, lang: String): Boolean {
        val w = normalize(word) ?: return false
        return (entries[key(lang, w)]?.count ?: 0) >= LEARN_AFTER
    }

    // ---- návrhy ----

    /** Naučená slova začínající na [prefix] (bez ohledu na velikost písmen a diakritiku). */
    @Synchronized
    fun suggest(prefix: String, lang: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = fold(prefix)
        return entries.values.asSequence()
            .filter { it.lang == lang && it.count >= LEARN_AFTER && fold(it.word).startsWith(p) }
            .sortedByDescending { it.count }
            .take(limit)
            .map { it.word }
            .toList()
    }

    /** Naučená slova jazyka s četností (pro gesta). */
    @Synchronized
    fun learned(lang: String): List<Pair<String, Int>> =
        entries.values.filter { it.lang == lang && it.count >= LEARN_AFTER }.map { it.word to it.count }

    // ---- správa (aplikace) ----

    @Synchronized
    fun all(): List<Entry> =
        entries.values.filter { it.count >= LEARN_AFTER }
            .sortedWith(compareBy({ it.lang }, { it.word.lowercase() }))
            .map { Entry(it.lang, it.word, it.count) }

    @Synchronized
    fun remove(word: String, lang: String): Boolean {
        val w = normalize(word) ?: return false
        val removed = entries.remove(key(lang, w)) != null
        if (removed) changed()
        return removed
    }

    @Synchronized
    fun clear() {
        entries.clear()
        usage.clear()
        pairs.clear()
        scheduleUsageSave()
        changed()
    }

    // ---- interní ----

    private fun key(lang: String, word: String) = lang + "\t" + word.lowercase()

    private fun changed() {
        version++
        prune()
        val snapshot = entries.values.joinToString("\n") { "${it.lang}\t${it.word}\t${it.count}" }
        saver.execute {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(snapshot)
                tmp.renameTo(file)
            }
        }
    }

    /** Drží velikost souboru v mezích: zahodí nejméně používané nenaučené pokusy. */
    private fun prune() {
        if (entries.size <= MAX_ENTRIES) return
        val drop = entries.values.sortedBy { it.count }.take(entries.size - MAX_ENTRIES)
        for (e in drop) entries.remove(key(e.lang, e.word))
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 3) {
                    val count = p[2].toIntOrNull() ?: return@forEachLine
                    entries[key(p[0], p[1])] = Entry(p[0], p[1], count)
                }
            }
        }
    }

    companion object {
        /** Kolikrát se musí slovo napsat, než se začne nabízet. */
        const val LEARN_AFTER = 2

        private const val MAX_ENTRIES = 5000
        private const val MAX_USAGE = 5000
        private const val MAX_PAIRS = 10000
        private const val USAGE_SAVE_DELAY_MS = 3000L
        private const val MIN_LEN = 2
        private const val MAX_LEN = 32

        @Volatile
        private var instance: UserWords? = null

        fun get(ctx: Context): UserWords =
            instance ?: synchronized(this) {
                instance ?: UserWords(ctx.applicationContext).also { instance = it }
            }

        /**
         * Úprava slova pro uložení: jen písmena (uvnitř smí být apostrof nebo spojovník).
         * Velké písmeno jen na začátku (začátek věty) se ukládá malým; slova jako „GitHub“
         * nebo „CoGo“ zůstávají, jak jsou. Vrací null pro slovo, které se učit nemá.
         */
        fun normalize(word: String): String? {
            val w = word.trim()
            if (w.length < MIN_LEN || w.length > MAX_LEN) return null
            if (!Character.isLetter(w.codePointAt(0))) return null
            if (!Character.isLetter(w.codePointBefore(w.length))) return null
            var i = 0
            while (i < w.length) {
                val cp = w.codePointAt(i)
                if (!Character.isLetter(cp) && cp != '\''.code && cp != '’'.code && cp != '-'.code) {
                    return null
                }
                i += Character.charCount(cp)
            }
            val rest = w.substring(Character.charCount(w.codePointAt(0)))
            return if (rest.any { it.isUpperCase() }) w else w.lowercase()
        }

        /** Malá písmena bez diakritiky, pro porovnání předpon. */
        fun fold(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
    }
}
