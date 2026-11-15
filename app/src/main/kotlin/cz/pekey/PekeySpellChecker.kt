package cz.pekey

import android.service.textservice.SpellCheckerService
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo

/**
 * Kontrola pravopisu pro celý telefon: podtrhává slova, která nejsou ve slovníku
 * ani mezi naučenými slovy, a nabízí opravy. Vše offline, stejný slovník jako klávesnice.
 */
class PekeySpellChecker : SpellCheckerService() {

    /** Jeden slovník pro všechny relace; knihovna není vícevláknová, proto zámek. */
    private val suggester by lazy { Suggester(this) }
    private val userWords by lazy { UserWords.get(this) }
    private val lock = Any()

    override fun createSession(): Session = PekeySession()

    override fun onDestroy() {
        synchronized(lock) { suggester.close() }
        super.onDestroy()
    }

    private inner class PekeySession : Session() {

        private var language = "cs"

        override fun onCreate() {
            language = locale?.substringBefore('_')?.lowercase()?.ifEmpty { null } ?: "cs"
        }

        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo {
            val word = textInfo.text.orEmpty().trim()
            return check(word, suggestionsLimit, textInfo.cookie, textInfo.sequence)
        }

        private fun check(word: String, limit: Int, cookie: Int, sequence: Int): SuggestionsInfo {
            val ok = SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyArray(), cookie, sequence)
            // čísla, zkratky celé velkými, odkazy a jednopísmenná slova se nekontrolují
            if (!shouldCheck(word)) return ok
            // null = nevíme (chybí knihovna nebo slovník), true = v pořádku, jinak návrhy oprav
            val verdict: Pair<Boolean, List<String>>? = synchronized(lock) {
                suggester.refresh()
                val known = suggester.isKnown(word, language)
                when {
                    known == null -> null
                    known || userWords.isLearned(word, language) -> true to emptyList()
                    else -> false to suggester.analyze(word, language).words
                        .filter { it != word }
                        .take(limit.coerceIn(1, MAX_SUGGESTIONS))
                }
            }
            if (verdict == null || verdict.first) return ok
            val fixes = verdict.second
            var attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            if (fixes.isNotEmpty()) attrs = attrs or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            return SuggestionsInfo(attrs, fixes.toTypedArray(), cookie, sequence)
        }

        private fun shouldCheck(word: String): Boolean {
            if (word.length < 2) return false
            if (word.any { it.isDigit() || it == '@' || it == '/' || it == '.' || it == '_' }) return false
            if (word.all { !it.isLetter() || it.isUpperCase() }) return false
            return word.any { it.isLetter() }
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 5
    }
}
