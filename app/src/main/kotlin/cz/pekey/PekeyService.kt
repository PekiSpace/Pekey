package cz.pekey

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

class PekeyService : InputMethodService() {

    private var shift = false
    private var view: PekeyKeyboardView? = null
    private lateinit var suggester: Suggester
    private lateinit var gestures: GestureEngine
    private lateinit var userWords: UserWords
    private val composing = StringBuilder()
    private var suggestionsOn = false
    private var learnOn = true
    private var autocorrectOn = true
    private var predictOn = true

    /** Oprava rozepsaného slova, která se použije při mezerníku (zvýrazněná v liště). */
    private var correction: String? = null

    /** Poslední provedená oprava (napsané → opravené); backspace hned potom ji vrátí. */
    private var lastCorrection: Pair<String, String>? = null

    /** Slova, u kterých jsi opravu vrátil; v tomto poli se už neopravují. */
    private val rejected = HashSet<String>()

    /**
     * Za kurzorem je mezera, kterou vložila klávesnice sama (po návrhu, tečce, čárce, emoji).
     * Následující tečka nebo čárka ji nahradí, další mezerník se nepřidá.
     */
    private var autoSpace = false

    /** Krátké hlášení v liště (např. „Naučeno“); zmizí při dalším stisku. */
    private var flashing = false

    /** Změna v nastavení se projeví hned, i když je klávesnice otevřená. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applySettings()
    }

    /** Rozepsané slovo vzniklo gestem: backspace ho smaže celé, další písmeno začne nové slovo. */
    private var gestureWord = false

    override fun onCreate() {
        super.onCreate()
        suggester = Suggester(this)
        gestures = GestureEngine(this)
        userWords = UserWords.get(this)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefsListener)
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        gestures.close()
        suggester.close()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = PekeyKeyboardView(this) { key -> onKey(key) }
        v.onGeometry = { g ->
            suggester.setGeometry(g)
            gestures.setGeometry(g)
            gestures.prepare(currentLanguage())
        }
        v.onSuggestionPicked = { word -> pickSuggestion(word) }
        v.onSuggestionLongPress = { word -> forgetWord(word) }
        v.onClipOpen = { readClipboard() }
        v.onClipPicked = { text -> pasteClip(text) }
        v.onClipRemove = { text ->
            clips.remove(text)
            view?.clipItems = clips.toList()
        }
        v.clipItems = clips.toList()
        v.onGesture = { xs, ys, n -> onGesture(xs, ys, n) }
        v.onOneHand = { mode -> Prefs.of(this).edit().putString(Prefs.ONE_HAND, mode).apply() }
        view = v
        v.notice = baseNotice()
        v.gestureEnabled = suggestionsOn
        applySettings()
        return v
    }

    /** Načte vzhled, rozložení, opravy, předpověď a učení slov z nastavení. */
    private fun applySettings() {
        learnOn = Prefs.learn(this)
        autocorrectOn = Prefs.autocorrect(this)
        predictOn = Prefs.predict(this)
        view?.language = langKey()
        view?.applySettings(Prefs.look(this))
    }

    /** Nástroje v liště (schránka, zpět) jsou vidět, když se nepíše slovo. */
    private fun updateTools() {
        view?.toolsVisible = composing.isEmpty()
    }

    private fun baseNotice() = if (suggestionsOn) "" else NOTICE_OFF

    private fun flash(message: String) {
        flashing = true
        view?.suggestions = emptyList()
        view?.notice = message
    }

    private fun endFlash() {
        if (flashing) {
            flashing = false
            view?.notice = baseNotice()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetComposing()
        suggestionsOn = allowSuggestions(info)
        flashing = false
        autoSpace = false
        lastCorrection = null
        rejected.clear()
        view?.notice = baseNotice()
        view?.gestureEnabled = suggestionsOn
        applySettings()
        if (suggestionsOn) {
            suggester.refresh()
            gestures.prepare(currentLanguage())
        }
        updateAutoShift()
        showPredictions()
    }

    /** Přepnutí jazyka (čeština ↔ angličtina) bez zavření klávesnice. */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        resetComposing()
        view?.language = langKey()
        if (suggestionsOn) gestures.prepare(currentLanguage())
    }

    override fun onFinishInput() {
        composing.clear()
        gestureWord = false
        view?.suggestions = emptyList()
        super.onFinishInput()
    }

    private fun allowSuggestions(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        if ((type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        if ((type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    private fun setShift(value: Boolean) {
        shift = value
        view?.shiftOn = value
    }

    /** Velké písmeno na začátku věty podle toho, co si pole žádá. */
    private fun updateAutoShift() {
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: 0
        setShift(type != 0 && ic.getCursorCapsMode(type) != 0)
    }

    private fun switchKeyboard() {
        val switched = Build.VERSION.SDK_INT >= 28 && switchToNextInputMethod(false)
        if (!switched) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    /** Rozepsané slovo se nechá v poli jako běžný text. */
    private fun resetComposing() {
        correction = null
        if (composing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composing.clear()
        }
        gestureWord = false
        view?.suggestions = emptyList()
        updateTools()
    }

    /** Zjistí přímo v poli, jestli před kurzorem pořád stojí rozepsané slovo. */
    private fun composingIntact(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(composing.length, 0) ?: return false
        return before.toString() == composing.toString()
    }

    private fun currentLanguage(): String? =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .currentInputMethodSubtype?.locale

    /** Jazyk jako krátký kód ("cs", "en"), stejně jako u gest. */
    private fun langKey(): String =
        currentLanguage()?.substringBefore('_')?.lowercase()?.ifEmpty { null } ?: "cs"

    // ---- schránka ----

    /**
     * Historie schránky jen v paměti (po restartu klávesnice zmizí), nejnovější první.
     * Text označený jako citlivý (hesla ze správce hesel) se neukládá.
     */
    private val clips = ArrayDeque<String>()
    private val clipboard: ClipboardManager? by lazy {
        getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readClipboard() }

    private fun readClipboard() {
        val text = try {
            val clip = clipboard?.primaryClip ?: return
            if (isSensitive(clip.description)) return
            if (clip.itemCount == 0) return
            clip.getItemAt(0).coerceToText(this)?.toString()
        } catch (_: Exception) {
            // Android nedovolí číst schránku, když klávesnice nemá fokus
            null
        } ?: return
        if (text.isBlank()) return
        clips.remove(text)
        clips.addFirst(text)
        while (clips.size > MAX_CLIPS) clips.removeLast()
        view?.clipItems = clips.toList()
    }

    private fun isSensitive(desc: ClipDescription?): Boolean {
        val extras: PersistableBundle = desc?.extras ?: return false
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    private fun pasteClip(text: String) {
        val ic = currentInputConnection ?: return
        resetComposing()
        ic.commitText(text, 1)
        autoSpace = false
        updateAutoShift()
    }

    /** Zpět jako Ctrl+Z: běžná textová pole v Androidu ho umí. */
    private fun undo(ic: InputConnection) {
        resetComposing()
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
    }

    // ---- kontext a předpověď dalšího slova ----

    /**
     * Co stojí před kurzorem (před rozepsaným slovem): předchozí slovo oddělené mezerou,
     * nebo začátek věty (začátek pole, nový řádek, za . ! ?). Jinak žádný kontext.
     */
    private fun wordContext(ic: InputConnection): Suggester.WordContext {
        var text = ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0)?.toString()
            ?: return Suggester.WordContext.NONE
        if (composing.isNotEmpty() && text.endsWith(composing.toString())) {
            text = text.substring(0, text.length - composing.length)
        }
        var i = text.length
        while (i > 0 && text[i - 1] == ' ') i--
        val hadSpace = i < text.length
        if (i == 0) return Suggester.WordContext(null, true)
        val c = text[i - 1]
        if (c == '\n') return Suggester.WordContext(null, true)
        if (!hadSpace) return Suggester.WordContext.NONE
        if (c == '.' || c == '!' || c == '?' || c == '…') return Suggester.WordContext(null, true)
        if (!c.isLetter()) return Suggester.WordContext.NONE
        var j = i
        while (j > 0 && (text[j - 1].isLetter() || text[j - 1] == '\'' || text[j - 1] == '-')) j--
        return Suggester.WordContext(text.substring(j, i), false)
    }

    /** Lišta ukazuje předpověď dalšího slova (ne návrhy k rozepsanému slovu). */
    private var predicting = false

    /** Po dopsaném slově nabídne slova, která obvykle následují. */
    private fun showPredictions() {
        predicting = false
        val v = view ?: return
        updateTools()
        if (!suggestionsOn || !predictOn || composing.isNotEmpty() || flashing) return
        val ic = currentInputConnection ?: return
        val list = suggester.predict(currentLanguage(), wordContext(ic), 3)
        v.suggestions = list
        predicting = list.isNotEmpty()
    }

    // ---- automatická oprava ----

    /** Nahradí rozepsané slovo opravou. Vrací (napsané, opravené), nebo null. */
    private fun applyCorrection(ic: InputConnection): Pair<String, String>? {
        val c = correction ?: return null
        correction = null
        if (gestureWord || composing.isEmpty() || !composingIntact(ic)) return null
        val typed = composing.toString()
        if (c == typed) return null
        composing.setLength(0)
        composing.append(c)
        ic.setComposingText(c, 1)
        return typed to c
    }

    /** Backspace hned po opravě: vrátí napsané slovo (rozepsané, bez mezery). */
    private fun revertCorrection(ic: InputConnection, fix: Pair<String, String>): Boolean {
        val (typed, corrected) = fix
        val expected = "$corrected "
        val before = ic.getTextBeforeCursor(expected.length, 0)?.toString() ?: return false
        if (before != expected) return false
        ic.deleteSurroundingText(expected.length, 0)
        rejected.add(typed.lowercase())
        composing.setLength(0)
        composing.append(typed)
        ic.setComposingText(composing, 1)
        showSuggestions()
        return true
    }

    // ---- naučená slova ----

    /** Slovo dopsané a potvrzené mezerou, Enterem nebo interpunkcí. */
    private fun learnComposing(ic: InputConnection) {
        if (!learnOn || !suggestionsOn || composing.isEmpty()) return
        if (!composingIntact(ic)) return
        val word = composing.toString()
        userWords.use(word, langKey())
        userWords.record(word, langKey(), suggester.isKnown(word, currentLanguage()))
    }

    private fun forgetWord(word: String) {
        if (userWords.remove(word, langKey())) {
            flash("Zapomenuto: $word")
        } else {
            flash("„$word“ není mezi naučenými slovy")
        }
    }

    /** Naučené slovo ve velikosti písmen podle napsaného (GitHub, CoGo… zůstávají). */
    private fun caseLike(typed: String, word: String): String {
        val rest = word.substring(Character.charCount(word.codePointAt(0)))
        if (rest.any { it.isUpperCase() }) return word
        if (!Character.isUpperCase(typed.codePointAt(0))) return word
        val allUpper = typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }
        return if (allUpper) word.uppercase() else word.replaceFirstChar { it.uppercase() }
    }

    private fun showSuggestions() {
        val v = view ?: return
        if (!suggestionsOn || composing.isEmpty()) {
            v.suggestions = emptyList()
            return
        }
        val typed = composing.toString()
        val lang = langKey()
        predicting = false
        updateTools()
        val context = currentInputConnection?.let { wordContext(it) } ?: Suggester.WordContext.NONE
        val analysis = suggester.analyze(typed, currentLanguage(), context)
        correction = analysis.correction?.takeIf {
            autocorrectOn && !gestureWord &&
                typed.lowercase() !in rejected &&
                !userWords.isLearned(typed, lang) &&
                suggester.isKnown(typed, currentLanguage()) != true
        }
        // naučená slova se nabízejí i při vypnutém učení (to jen nepřidává nová)
        val own = userWords.suggest(typed, lang, 3).map { caseLike(typed, it) }
        val list = ArrayList<String>(3)
        list.add(typed)
        fun add(s: String) {
            if (list.size < 3 && list.none { it.equals(s, ignoreCase = true) }) list.add(s)
        }
        // oprava uprostřed, pak nejčastější naučené slovo, slovník a zbytek naučených
        correction?.let { add(it) }
        own.firstOrNull()?.let { add(it) }
        for (s in analysis.words) add(s)
        own.drop(1).forEach { add(it) }
        v.suggestions = list
        v.highlight = correction?.let { c -> list.indexOfFirst { it == c } } ?: -1
    }

    private fun pickSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty() && !composingIntact(ic)) {
            resetComposing()
            return
        }
        var learned = false
        if (learnOn && suggestionsOn) {
            val lang = langKey()
            val known = suggester.isKnown(word, currentLanguage())
            if (!gestureWord && composing.isNotEmpty() && word == composing.toString() && known != true) {
                // klepnutí na přesně napsané slovo = „tohle slovo chci“
                learned = !userWords.isLearned(word, lang)
                userWords.learnNow(word, lang)
            } else {
                userWords.record(word, lang, known)
            }
            userWords.use(word, lang)
        }
        if (composing.isEmpty()) {
            // předpověď: slovo za mezerou; kdyby mezera chyběla, doplní se
            val before = ic.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && needsSpaceAfter(before[before.length - 1])) ic.commitText(" ", 1)
        }
        ic.commitText("$word ", 1)
        composing.clear()
        gestureWord = false
        autoSpace = true
        correction = null
        view?.suggestions = emptyList()
        updateAutoShift()
        if (learned) {
            updateTools()
            flash("Naučeno: $word  ·  podržením návrhu ho zapomeneš")
        } else {
            showPredictions()
        }
    }

    // ---- gesta ----

    private fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) {
        if (!suggestionsOn) return
        endFlash()
        autoSpace = false
        val capitalize = shift
        gestures.recognize(xs, ys, count, currentLanguage(), GESTURE_CANDIDATES) { result ->
            applyGesture(result, capitalize)
        }
    }

    private fun applyGesture(result: GestureEngine.Result, capitalize: Boolean) {
        val v = view ?: return
        val ic = currentInputConnection ?: return
        if (result.words.isEmpty()) {
            v.suggestions = emptyList()
            v.notice = when {
                !result.ready && result.error.isNotEmpty() -> "Gesta: ${result.error}"
                !result.ready -> "Slovník pro gesta se načítá…"
                result.error.isNotEmpty() -> "Gesta: ${result.error}"
                else -> "Slovo se nepodařilo rozpoznat"
            }
            return
        }
        v.notice = ""

        correction = null
        // předchozí slovo potvrdit a oddělit mezerou
        if (composing.isNotEmpty()) {
            learnComposing(ic)
            if (composingIntact(ic)) ic.finishComposingText()
            composing.clear()
        }
        val before = ic.getTextBeforeCursor(1, 0)
        if (!before.isNullOrEmpty() && needsSpaceAfter(before[before.length - 1])) {
            ic.commitText(" ", 1)
        }

        val ranked = rerankGesture(result, wordContext(ic)).take(3)
        val words = ranked.map { if (capitalize) it.replaceFirstChar { c -> c.uppercase() } else it }
        composing.append(words[0])
        ic.setComposingText(composing, 1)
        gestureWord = true
        predicting = false
        updateTools()
        v.suggestions = words
        updateAutoShift()
    }

    /**
     * Přeřazení kandidátů gesta: k tvaru tahu se přičte, jak dobře slovo navazuje
     * na předchozí slovo (dvojice ze slovníku) a jak často ho sám píšeš.
     */
    private fun rerankGesture(result: GestureEngine.Result, context: Suggester.WordContext): List<String> {
        if (result.scores.size != result.words.size) return result.words
        val lang = langKey()
        return result.words.indices.map { i ->
            val w = result.words[i]
            var score = result.scores[i]
            val ngram = suggester.ngram(w, currentLanguage(), context)
            if (ngram >= 0) score += NGRAM_BONUS + NGRAM_BONUS_RANGE * ngram / 255.0
            val used = userWords.usageOf(w, lang)
            if (used > 0) score += minOf(USAGE_BONUS_MAX, USAGE_WEIGHT * Math.log(1.0 + used))
            w to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun needsSpaceAfter(c: Char): Boolean =
        !c.isWhitespace() && c != '(' && c != '[' && c != '{' && c != '"' && c != '\'' &&
            c != '„' && c != '/' && c != '@' && c != '#'

    /**
     * Smaže slovo před kurzorem i s mezerami za ním. Interpunkci maže po jednom znaku,
     * rozepsané slovo celé.
     */
    private fun deleteWord(ic: InputConnection) {
        if (composing.isNotEmpty()) {
            ic.commitText("", 1)
            composing.clear()
            gestureWord = false
            view?.suggestions = emptyList()
            return
        }
        val before = ic.getTextBeforeCursor(WORD_LOOKBACK, 0)?.toString().orEmpty()
        if (before.isEmpty()) {
            // začátek pole nebo označený text
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        var i = before.length
        while (i > 0 && Character.isWhitespace(before.codePointBefore(i))) {
            i -= Character.charCount(before.codePointBefore(i))
        }
        if (i > 0 && Character.isLetterOrDigit(before.codePointBefore(i))) {
            while (i > 0 && Character.isLetterOrDigit(before.codePointBefore(i))) {
                i -= Character.charCount(before.codePointBefore(i))
            }
        } else if (i > 0) {
            i -= Character.charCount(before.codePointBefore(i))
        }
        ic.deleteSurroundingText(before.length - i, 0)
    }

    /** Posun kurzoru o celé slovo doleva/doprava (tah po mezerníku). */
    private fun moveByWord(ic: InputConnection, left: Boolean) {
        val text = (if (left) ic.getTextBeforeCursor(WORD_LOOKBACK, 0) else ic.getTextAfterCursor(WORD_LOOKBACK, 0))
            ?.toString().orEmpty()
        if (text.isEmpty()) return
        // kódové body ve směru pohybu
        val cps = text.codePoints().toArray().let { if (left) it.reversedArray() else it }
        var k = 0
        while (k < cps.size && !Character.isLetterOrDigit(cps[k])) k++
        while (k < cps.size && Character.isLetterOrDigit(cps[k])) k++
        val code = if (left) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(k.coerceAtLeast(1)) { sendDownUpKeyEvents(code) }
    }

    private fun isWordChar(s: String): Boolean {
        val cp = s.codePointAt(0)
        return s.length == Character.charCount(cp) && Character.isLetter(cp)
    }

    private fun isWordEnd(s: String): Boolean = s.length == 1 && s[0] in WORD_END

    private fun isEmoji(s: String): Boolean =
        s.isNotEmpty() && Character.getType(s.codePointAt(0)) == Character.OTHER_SYMBOL.toInt()

    /** Smaže mezeru, kterou za kurzor vložila klávesnice. */
    private fun removeAutoSpace(ic: InputConnection) {
        if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
    }

    /**
     * Po tečce, čárce, ! ? a emoji automatická mezera. Ne v číslech (3,5 nebo 1.2),
     * ne v polích bez návrhů (heslo, e-mail, adresa).
     */
    private fun wantsSpaceAfter(t: String, ic: InputConnection): Boolean {
        if (!suggestionsOn) return false
        if (isEmoji(t)) return true
        if (t.length != 1 || t[0] !in AUTO_SPACE) return false
        val before = ic.getTextBeforeCursor(1, 0)
        return before.isNullOrEmpty() || !before[0].isDigit()
    }

    private fun onKey(key: Key) {
        val ic = currentInputConnection ?: return
        endFlash()
        val pendingSpace = autoSpace
        autoSpace = false
        val fix = lastCorrection
        lastCorrection = null
        if (composing.isNotEmpty() && !composingIntact(ic)) resetComposing()

        when (key) {
            is Key.Char -> {
                val t = if (shift) key.label.uppercase() else key.label
                if (gestureWord && isWordChar(t) && composing.isNotEmpty()) {
                    // po slově z gesta začíná písmeno nové slovo
                    learnComposing(ic)
                    ic.finishComposingText()
                    composing.clear()
                    gestureWord = false
                    ic.commitText(" ", 1)
                }
                gestureWord = false
                if (suggestionsOn && isWordChar(t)) {
                    composing.append(t)
                    ic.setComposingText(composing, 1)
                    showSuggestions()
                } else {
                    if (isWordEnd(t)) {
                        applyCorrection(ic)
                        learnComposing(ic)
                    }
                    resetComposing()
                    // „slovo .“ → „slovo. “: interpunkce a emoji se přilepí ke slovu
                    if (pendingSpace && (isWordEnd(t) || isEmoji(t))) removeAutoSpace(ic)
                    val space = wantsSpaceAfter(t, ic)
                    ic.commitText(if (space) "$t " else t, 1)
                    autoSpace = space
                    updateAutoShift()
                    if (space) showPredictions()
                }
                updateAutoShift()
            }
            Key.Shift -> setShift(!shift)
            Key.Backspace -> {
                if (fix != null && composing.isEmpty() && revertCorrection(ic, fix)) {
                    // oprava vrácena
                } else if (gestureWord && composing.isNotEmpty()) {
                    // slovo z gesta se maže celé
                    ic.commitText("", 1)
                    composing.clear()
                    gestureWord = false
                    view?.suggestions = emptyList()
                } else if (composing.isNotEmpty()) {
                    val last = Character.charCount(composing.codePointBefore(composing.length))
                    composing.setLength(composing.length - last)
                    if (composing.isEmpty()) {
                        ic.commitText("", 1)
                    } else {
                        ic.setComposingText(composing, 1)
                    }
                    showSuggestions()
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
                updateAutoShift()
                if (composing.isEmpty()) showPredictions()
            }
            Key.DeleteWord -> {
                deleteWord(ic)
                updateAutoShift()
            }
            Key.CursorLeft, Key.CursorRight -> {
                resetComposing()
                sendDownUpKeyEvents(
                    if (key == Key.CursorLeft) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                )
                updateAutoShift()
            }
            Key.WordLeft, Key.WordRight -> {
                resetComposing()
                moveByWord(ic, key == Key.WordLeft)
                updateAutoShift()
            }
            Key.Space -> {
                val fixed = applyCorrection(ic)
                learnComposing(ic)
                resetComposing()
                // mezera už tam je (vložená automaticky), druhá se nepřidá
                if (!pendingSpace) ic.commitText(" ", 1)
                if (fixed != null && !pendingSpace) lastCorrection = fixed
                updateAutoShift()
                showPredictions()
            }
            Key.Enter -> {
                learnComposing(ic)
                resetComposing()
                if (pendingSpace) removeAutoSpace(ic)
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                updateAutoShift()
                showPredictions()
            }
            Key.SwitchIme -> {
                resetComposing()
                switchKeyboard()
            }
            // přepínání vrstev a emoji řeší samotná klávesnice (view)
            Key.Undo -> {
                undo(ic)
                updateAutoShift()
            }
            Key.Symbols, Key.Letters, Key.Emoji, Key.Clipboard -> Unit
        }
    }

    private companion object {
        const val NOTICE_OFF = "Návrhy jsou v tomto poli vypnuté"
        const val WORD_LOOKBACK = 64

        /** Znaky, které ukončí slovo a slovo se počítá jako napsané (pro učení). */
        const val WORD_END = ".,!?;:…)\"»“”"

        /** Po těchto znacích se automaticky přidá mezera. */
        const val AUTO_SPACE = ".,!?"

        /** Kolik posledních zkopírovaných textů se drží. */
        const val MAX_CLIPS = 20

        /** Kolik znaků před kurzorem se čte kvůli předchozímu slovu. */
        const val CONTEXT_LOOKBACK = 48

        /** Kandidáti gesta před přeřazením (zobrazí se nejlepší tři). */
        const val GESTURE_CANDIDATES = 8

        /** Bonus gesta za známou dvojici slov (+ až RANGE podle její četnosti). */
        const val NGRAM_BONUS = 1.5
        const val NGRAM_BONUS_RANGE = 2.5

        /** Bonus gesta za slova, která často píšeš: WEIGHT × ln(1 + počet), nejvýš MAX. */
        const val USAGE_WEIGHT = 0.7
        const val USAGE_BONUS_MAX = 2.5
    }
}

sealed interface Key {
    data class Char(val label: String) : Key
    data object Shift : Key
    data object Backspace : Key
    /** Podržený backspace: smaže celé slovo. */
    data object DeleteWord : Key
    /** Tah po mezerníku: posun kurzoru o znak. */
    data object CursorLeft : Key
    data object CursorRight : Key
    /** Rychlý tah po mezerníku: posun kurzoru o slovo. */
    data object WordLeft : Key
    data object WordRight : Key
    data object Space : Key
    data object Enter : Key
    data object Symbols : Key
    data object Letters : Key
    data object Emoji : Key
    data object SwitchIme : Key
    /** Panel historie schránky (přepíná klávesnice). */
    data object Clipboard : Key
    /** Vrátit poslední úpravu (Ctrl+Z). */
    data object Undo : Key
}
