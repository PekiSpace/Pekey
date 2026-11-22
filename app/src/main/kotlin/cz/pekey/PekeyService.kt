package cz.pekey

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
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

    /** Pole si nepřeje učení (anonymní karta prohlížeče apod.). */
    private var fieldIncognito = false

    /** Anonymní režim: pole si ho vyžádalo, nebo je zapnutý ručně v panelu úprav. */
    private var incognito = false
    private var autocorrectOn = true

    /** Silná automatická oprava (nižší práh jistoty). */
    private var autocorrectStrong = false
    private var predictOn = true

    /**
     * Místa ťuknutí u písmen rozepsaného slova (souřadnice klávesnice, -1 = neznámé).
     * Platí jen pro slovo [tapWord]; když se rozepsané slovo změní jinak než psaním
     * (oprava, gesto, návrat ke slovu), přestanou sedět a nepoužijí se.
     */
    private val tapXs = ArrayList<Int>()
    private val tapYs = ArrayList<Int>()
    private var tapWord = ""

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
        // průhledné okno: průhlednost klávesnice (nastavení) pak opravdu prosvítá
        window?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val v = PekeyKeyboardView(this) { key -> onKey(key) }
        // obsah klávesnice se neposílá systémovým službám (Content Capture, automatické vyplňování)
        if (Build.VERSION.SDK_INT >= 30) {
            v.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        v.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        v.onGeometry = { g ->
            suggester.setGeometry(g)
            gestures.setGeometry(g)
            gestures.prepare(currentLanguage(), secondLang())
        }
        v.onSuggestionPicked = { word -> pickSuggestion(word) }
        v.onSuggestionLongPress = { word -> forgetWord(word) }
        v.onClipOpen = { readClipboard() }
        v.onClipPicked = { text -> pasteClip(text) }
        v.onClipPin = { text -> togglePin(text) }
        v.onClipClearAll = { clearClips() }
        v.onClipRemove = { text ->
            clips.remove(text)
            if (pinned.remove(text)) savePinned()
            pushClips()
        }
        v.pinnedClips = pinned.toSet()
        v.clipItems = pinned.toList() + clips.filter { it !in pinned }
        v.onGesture = { xs, ys, ts, n -> onGesture(xs, ys, ts, n) }
        v.onOneHand = { mode -> Prefs.of(this).edit().putString(Prefs.ONE_HAND, mode).apply() }
        v.onIncognito = { toggleIncognito() }
        v.hardKeyboard = hardKeyboardOn()
        view = v
        v.notice = baseNotice()
        v.gestureEnabled = suggestionsOn
        applySettings()
        return v
    }

    /** Načte vzhled, rozložení, opravy, předpověď a učení slov z nastavení. */
    private fun applySettings() {
        incognito = fieldIncognito || Prefs.incognito(this)
        learnOn = Prefs.learn(this) && !incognito
        view?.incognito = incognito
        val level = Prefs.autocorrectLevel(this)
        autocorrectOn = level != Prefs.CORRECT_OFF
        autocorrectStrong = level == Prefs.CORRECT_STRONG
        predictOn = Prefs.predict(this)
        pushLanguage()
        view?.applySettings(Prefs.look(this))
    }

    /** Nástroje v liště (schránka, zpět) jsou vidět, když se nepíše slovo. */
    private fun updateTools() {
        view?.toolsVisible = composing.isEmpty()
    }

    private fun baseNotice() = if (suggestionsOn) "" else t(NOTICE_OFF)

    private fun flash(message: String) {
        flashing = true
        view?.suggestions = emptyList()
        view?.notice = t(message)
    }

    private fun endFlash() {
        if (flashing) {
            flashing = false
            view?.notice = baseNotice()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Tr.init(this)
        enableAllLanguagesOnce()
        appPackage = info?.packageName
        if (!restarting) applyAppLanguage(info)
        resetComposing()
        suggestionsOn = allowSuggestions(info)
        flashing = false
        autoSpace = false
        lastCorrection = null
        rejected.clear()
        view?.notice = baseNotice()
        view?.gestureEnabled = suggestionsOn
        view?.endEmojiSearch()
        view?.numericMode = numericModeFor(info)
        view?.fieldMode = fieldModeFor(info)
        view?.hardKeyboard = hardKeyboardOn()
        deadAccent = 0
        view?.voiceReady = cz.pekey.Voice.available() && cz.pekey.Voice.hasModel(this)
        // anonymní pole (anonymní karta prohlížeče apod.): nic se neučí
        capsLock = false
        fieldIncognito = info != null &&
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        applySettings()
        if (suggestionsOn) {
            suggester.refresh()
            gestures.prepare(currentLanguage(), secondLang())
        }
        updateAutoShift()
        // text zkopírovaný mezitím v jiné aplikaci (bez fokusu ho klávesnice číst nemohla)
        readClipboard()
        showPredictions()
    }

    /** Přepnutí jazyka (čeština ↔ angličtina) bez zavření klávesnice. */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        rememberAppLanguage(newSubtype)
        resetComposing()
        pushLanguage()
        if (suggestionsOn) gestures.prepare(currentLanguage(), secondLang())
    }

    // ---- návrat k napsanému slovu ----

    /** Slovo znovu otevřené kurzorem: návrhy ano, automatická oprava až po úpravě slova. */
    private var reopened = false

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!suggestionsOn || newSelStart != newSelEnd || newSelStart < 0) return
        if (candidatesStart >= 0 && newSelStart in candidatesStart..candidatesEnd) return
        if (swipeAnchor >= 0 || cz.pekey.Voice.isRecording) return
        // po Enteru a jiných změnách, které pole provede až po stisku: znovu velké písmeno
        if (composing.isEmpty()) updateAutoShift()
        // změna kurzoru po vlastním psaní (klávesa, gesto, návrh) není návrat ke slovu
        if (SystemClock.uptimeMillis() - lastOwnEdit < OWN_EDIT_MS) return
        if (composing.isNotEmpty()) {
            // kurzor odjel z rozepsaného slova jinam (klepnutím do textu)
            resetComposing()
        }
        // až když se kurzor ustálí (posun šipkami, tah po mezerníku)
        val h = reopenHandler
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ reopenWordAt(newSelStart) }, REOPEN_DELAY_MS)
    }

    private val reopenHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Kdy naposledy psala sama klávesnice. */
    private var lastOwnEdit = 0L

    private fun markOwnEdit() {
        lastOwnEdit = SystemClock.uptimeMillis()
        reopenHandler.removeCallbacksAndMessages(null)
    }

    /** Kurzor stojí na konci slova: slovo se znovu rozepíše a ukážou se k němu návrhy. */
    private fun reopenWordAt(cursor: Int) {
        if (composing.isNotEmpty() || !suggestionsOn) return
        val ic = currentInputConnection ?: return
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && isWordChar(after.toString())) return
        val before = ic.getTextBeforeCursor(REOPEN_LOOKBACK, 0)?.toString() ?: return
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'' || before[i - 1] == '-')) i--
        val word = before.substring(i).trimStart('\'', '-')
        if (word.isEmpty() || word.length > 32 || !word[0].isLetter()) return
        ic.setComposingRegion(cursor - word.length, cursor)
        composing.clear()
        composing.append(word)
        gestureWord = false
        reopened = true
        showSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // klávesnice se zavřela: nahrávání se zruší
        if (cz.pekey.Voice.isRecording) cz.pekey.Voice.cancel()
        view?.voiceState = VoiceState.OFF
    }

    override fun onFinishInput() {
        composing.clear()
        gestureWord = false
        view?.suggestions = emptyList()
        super.onFinishInput()
    }

    /** Pole na čísla, PIN, datum nebo telefon dostane číselnou klávesnici. */
    private fun numericModeFor(info: EditorInfo?): Int =
        when ((info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE -> NumMode.PHONE
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> NumMode.NUMBER
            else -> NumMode.OFF
        }

    /** E-mail a webová adresa mají ve spodní řadě @ nebo / a klávesu s doménou. */
    private fun fieldModeFor(info: EditorInfo?): Int {
        val type = info?.inputType ?: return FieldMode.TEXT
        if ((type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return FieldMode.TEXT
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldMode.EMAIL
            InputType.TYPE_TEXT_VARIATION_URI -> FieldMode.URL
            else -> FieldMode.TEXT
        }
    }

    /** Přepne ruční anonymní režim (nastavení se pak načte znovu přes prefsListener). */
    private fun toggleIncognito() {
        val on = !Prefs.incognito(this)
        Prefs.of(this).edit().putBoolean(Prefs.INCOGNITO, on).apply()
        applySettings()
        flash(if (on) "Anonymní režim: nic se neučí ani neukládá" else "Anonymní režim vypnutý")
    }

    // ---- fyzická klávesnice ----

    /**
     * Je připojená fyzická klávesnice a Android by klávesnici na obrazovce skryl
     * (volba „Použít klávesnici na obrazovce“ je vypnutá). Pekey pak ukáže jen lištu.
     */
    private fun hardKeyboardOn(): Boolean = !super.onEvaluateInputViewShown()

    /** Lišta s návrhy zůstane vidět i u fyzické klávesnice. */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.hardKeyboard = hardKeyboardOn()
    }

    /** Mrtvá klávesa (háček, čárka) z fyzické klávesnice čekající na písmeno. */
    private var deadAccent = 0

    /** Klávesy, jejichž stisk zpracoval Pekey (jejich puštění se pak aplikaci neposílá). */
    private val hardHandled = HashSet<Int>()

    /** Psaní z fyzické klávesnice jde přes Pekey (návrhy, opravy, učení), jinak rovnou do pole. */
    private fun hardTyping(event: KeyEvent): Boolean =
        view?.hardKeyboard == true && isInputViewShown && suggestionsOn &&
            currentInputConnection != null && !event.isCtrlPressed && !event.isMetaPressed

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!hardTyping(event)) return super.onKeyDown(keyCode, event)
        val key: Key = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> Key.Backspace
            KeyEvent.KEYCODE_SPACE -> Key.Space
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.Enter
            else -> {
                val uc = event.getUnicodeChar(event.metaState)
                if (uc != 0 && (uc and KeyCharacterMap.COMBINING_ACCENT) != 0) {
                    deadAccent = uc and KeyCharacterMap.COMBINING_ACCENT_MASK
                    hardHandled.add(keyCode)
                    return true
                }
                if (uc < 0x20 || uc == 0x7f) return super.onKeyDown(keyCode, event)
                var ch = uc
                if (deadAccent != 0) {
                    val combined = KeyEvent.getDeadChar(deadAccent, uc)
                    if (combined != 0) ch = combined
                }
                Key.Char(String(Character.toChars(ch)))
            }
        }
        deadAccent = 0
        hardHandled.add(keyCode)
        // velikost písmen určuje fyzická klávesnice (Shift, Caps Lock na ní)
        if (key is Key.Char) shift = false
        onKey(key)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (hardHandled.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
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
        view?.capsLock = capsLock
    }

    /** Zamčená velká písmena: dvojitý stisk Shiftu, zruší je další stisk. */
    private var capsLock = false
    private var lastShiftTap = 0L

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        when {
            capsLock -> {
                capsLock = false
                setShift(false)
            }
            shift && now - lastShiftTap < CAPS_DOUBLE_TAP_MS -> {
                capsLock = true
                setShift(true)
            }
            else -> setShift(!shift)
        }
        lastShiftTap = now
    }

    /** Velké písmeno na začátku věty podle toho, co si pole žádá. */
    /**
     * Velké písmeno na začátku řádku i tam, kde pole samo velká písmena nežádá
     * (ne v e-mailech, adresách, heslech a polích bez návrhů).
     */
    private fun lineStartCaps(ic: InputConnection, type: Int): Boolean {
        if (!suggestionsOn) return false
        if ((type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER -> return false
        }
        val before = ic.getTextBeforeCursor(3, 0) ?: return false
        if (before.isEmpty()) return true
        val t = before.toString()
        return t.endsWith("\n") || Regex("[.!?…]\\s+$").containsMatchIn(t)
    }

    private fun updateAutoShift() {
        if (capsLock) {
            setShift(true)
            return
        }
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: 0
        setShift(type != 0 && (ic.getCursorCapsMode(type) != 0 || lineStartCaps(ic, type)))
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

    /**
     * Android sám zapne jen jazyk podle telefonu. Od Androidu 14 smí klávesnice zapnout
     * své jazyky sama: jednou zapne všechny (češtinu i angličtinu). Starší Android: ručně
     * v nastavení Pekey → Psaní → Jazyky klávesnice.
     */
    private fun enableAllLanguagesOnce() {
        if (Build.VERSION.SDK_INT < 34) return
        val prefs = Prefs.of(this)
        if (prefs.getBoolean(KEY_LANGS_ENABLED, false)) return
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val me = imm.inputMethodList.firstOrNull { it.packageName == packageName } ?: return
            // přibalené slovníky má jen čeština a angličtina; ostatní jazyky po importu slovníku
            val hashes = (0 until me.subtypeCount).map { me.getSubtypeAt(it) }
                .filter { val l = it.locale.substringBefore('_'); l == "cs" || l == "en" }
                .map { it.hashCode() }.toIntArray()
            imm.setExplicitlyEnabledInputMethodSubtypes(me.id, hashes)
            prefs.edit().putBoolean(KEY_LANGS_ENABLED, true).apply()
        } catch (_: Exception) {
            // nepovolí-li to systém, zůstává ruční zapnutí
        }
    }

    // ---- hlasové zadávání ----

    private fun toggleVoice() {
        val v = view ?: return
        if (cz.pekey.Voice.isRecording) {
            v.voiceState = VoiceState.WORKING
            cz.pekey.Voice.stopAndTranscribe { text -> onVoiceText(text) }
            return
        }
        if (!cz.pekey.Voice.available()) {
            flash("Hlasové zadávání v tomto sestavení chybí")
            return
        }
        if (!cz.pekey.Voice.hasModel(this)) {
            flash("Nahraj hlasový model v nastavení → Slovníky a záloha")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // klávesnice se o oprávnění zeptat neumí: požádá nastavení Pekey
            requestHideSelf(0)
            startActivity(
                android.content.Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ASK_MIC, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        currentInputConnection?.let {
            learnComposing(it)
            resetComposing()
        }
        cz.pekey.Voice.onLimit = { if (cz.pekey.Voice.isRecording) toggleVoice() }
        // hotové věty se vkládají už během mluvení
        cz.pekey.Voice.onPartial = { text -> insertVoiceText(text) }
        val prompt = if (langKey() == "cs") "Dobrý den, tohle je diktovaný text v češtině." else ""
        if (cz.pekey.Voice.start(this, langKey(), prompt)) {
            v.voiceState = VoiceState.LISTENING
        } else {
            flash("Mikrofon nejde otevřít")
        }
    }

    private fun onVoiceText(text: String?) {
        markOwnEdit()
        view?.voiceState = VoiceState.OFF
        if (text == null) {
            voiceInserted = false
            flash("Přepis se nepovedl – zkontroluj hlasový model")
            return
        }
        if (text.isBlank()) {
            if (!voiceInserted) flash("Nic jsem neslyšel")
            voiceInserted = false
            return
        }
        insertVoiceText(text)
        voiceInserted = false
        updateAutoShift()
        showPredictions()
    }

    /** Během jedné nahrávky už byl vložen nějaký text (přepis po větách). */
    private var voiceInserted = false

    private fun insertVoiceText(text: String) {
        markOwnEdit()
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val sep = if (!before.isNullOrEmpty() && !before[0].isWhitespace()) " " else ""
        autoSpace = commitWithSpace(ic, sep + text)
        voiceInserted = true
    }

    /** Přepne na další jazyk Pekey (pořadí podle nastavení klávesnice v Androidu). */
    private fun nextLanguage() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val me = imm.enabledInputMethodList.firstOrNull { it.packageName == packageName } ?: return
        val list = imm.getEnabledInputMethodSubtypeList(me, true)
        if (list.size < 2) {
            flash("Zapni v nastavení Androidu u Pekey víc jazyků")
            return
        }
        val cur = currentLanguage()
        val i = list.indexOfFirst { it.locale == cur }
        val next = list[(i + 1).mod(list.size)]
        if (Build.VERSION.SDK_INT >= 28) {
            switchInputMethod(me.id, next)
        } else {
            val token = window?.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            imm.setInputMethodAndSubtype(token, me.id, next)
        }
    }

    /** Jazyk a vlajka jazykové sady pro klávesnici. */
    private fun pushLanguage() {
        view?.language = langKey()
        view?.spaceFlag = flagFor(currentLanguage())
    }

    /** Vlajka podle jazykové sady: země z locale (en_US → 🇺🇸), jinak podle jazyka. */
    private fun flagFor(locale: String?): String {
        val parts = (locale ?: "cs").split('_', '-')
        val country = parts.getOrNull(1)?.takeIf { it.length == 2 }?.uppercase()
            ?: when (parts[0].lowercase()) {
                "cs" -> "CZ"
                "en" -> "GB"
                "sk" -> "SK"
                "de" -> "DE"
                "pl" -> "PL"
                "fr" -> "FR"
                "it" -> "IT"
                "uk" -> "UA"
                else -> return ""
            }
        return country.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /**
     * Druhý jazyk pro návrhy a gesta (čeština a angličtina zároveň), nebo null,
     * když je to v nastavení vypnuté.
     */
    private fun secondLang(): String? =
        if (Prefs.bilingual(this)) (if (langKey() == "en") "cs" else "en") else null

    /** Zná slovo hlavní, nebo druhý jazyk? Null, když slovník chybí. */
    private fun knownAny(word: String): Boolean? {
        val main = suggester.isKnown(word, currentLanguage())
        if (main == true) return true
        val other = secondLang()?.let { suggester.isKnown(word, it, true) }
        return if (other == true) true else main
    }

    /** Jazyk jako krátký kód ("cs", "en"), stejně jako u gest. */
    private fun langKey(): String =
        currentLanguage()?.substringBefore('_')?.lowercase()?.ifEmpty { null } ?: "cs"

    // ---- jazyk podle aplikace ----

    /** Aplikace, do které se právě píše. */
    private var appPackage: String? = null

    /**
     * Přepne na jazyk, který jsi v této aplikaci použil naposledy.
     * Když v ní ještě nic nemáš, řídí se jazykem, který si pole samo žádá (hintLocales).
     */
    private fun applyAppLanguage(info: EditorInfo?) {
        if (Build.VERSION.SDK_INT < 28 || !Prefs.appLang(this)) return
        val pkg = info?.packageName ?: return
        if (pkg == packageName) return
        val wanted = Prefs.appLangs(this).getString(pkg, null)
            ?: info.hintLocales?.takeIf { !it.isEmpty }?.get(0)?.language
            ?: return
        if (wanted == langKey()) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val me = imm.enabledInputMethodList.firstOrNull { it.packageName == packageName } ?: return
        val subtype = imm.getEnabledInputMethodSubtypeList(me, true)
            .firstOrNull { it.locale.substringBefore('_').lowercase() == wanted } ?: return
        switchInputMethod(me.id, subtype)
    }

    /** Přepnutý jazyk si zapamatuje pro aktuální aplikaci. */
    private fun rememberAppLanguage(subtype: InputMethodSubtype) {
        val lang = subtype.locale.substringBefore('_').lowercase().ifEmpty { return }
        val pkg = appPackage ?: return
        if (pkg == packageName || !Prefs.appLang(this)) return
        Prefs.appLangs(this).edit().putString(pkg, lang).apply()
    }

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
        // v anonymním režimu se do historie schránky nic nepřidává
        if (incognito) return
        // jednorázové kódy (4–8 číslic) se do historie nedávají
        if (OTP.matches(text.trim())) return
        val now = SystemClock.elapsedRealtime()
        if (clips.firstOrNull() != text) {
            // nově zkopírovaný text se chvíli nabízí v liště návrhů
            recentClip = text
            recentClipAt = now
        }
        clips.remove(text)
        clips.addFirst(text)
        clipTimes[text] = now
        while (clips.size > MAX_CLIPS) clipTimes.remove(clips.removeLast())
        pushClips()
        if (composing.isEmpty()) showPredictions()
    }

    /** Kdy byla položka zkopírována (pro automatické mazání historie). */
    private val clipTimes = HashMap<String, Long>()

    /** Čerstvě zkopírovaný text pro lištu návrhů. */
    private var recentClip: String? = null
    private var recentClipAt = 0L

    /** Text návrhu „vložit zkopírované“ v liště (null = žádný). */
    private var clipChip: String? = null

    /** Smaže z historie (ne připnuté) položky starší, než dovoluje nastavení. */
    private fun expireClips() {
        val keepMin = Prefs.clipKeep(this)
        if (keepMin <= 0) return
        val limit = SystemClock.elapsedRealtime() - keepMin * 60_000L
        val it = clips.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if ((clipTimes[c] ?: 0L) < limit) {
                it.remove()
                clipTimes.remove(c)
            }
        }
    }

    private fun clearClips() {
        clips.clear()
        clipTimes.clear()
        recentClip = null
        pushClips()
    }

    /** Připnuté položky: uložené jen v telefonu, v soukromých datech klávesnice. */
    private val pinned: LinkedHashSet<String> by lazy {
        val raw = getSharedPreferences(CLIP_PREFS, MODE_PRIVATE).getString(KEY_PINNED, "") ?: ""
        LinkedHashSet(raw.split(PIN_SEP).filter { it.isNotEmpty() })
    }

    private fun savePinned() {
        getSharedPreferences(CLIP_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_PINNED, pinned.joinToString(PIN_SEP)).apply()
    }

    private fun togglePin(text: String) {
        if (!pinned.remove(text)) pinned.add(text)
        savePinned()
        pushClips()
    }

    /** Připnuté nahoře, pod nimi historie. */
    private fun pushClips() {
        expireClips()
        view?.pinnedClips = pinned.toSet()
        view?.clipItems = pinned.toList() + clips.filter { it !in pinned }
    }

    private fun isSensitive(desc: ClipDescription?): Boolean {
        val extras: PersistableBundle = desc?.extras ?: return false
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    private fun pasteClip(text: String) {
        markOwnEdit()
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
        val context = wordContext(ic)
        // nejdřív slova, která po předchozím slově píšeš ty, pak slovník
        val own = userWords.nextFor(context.prevWord, langKey(), 3)
        val list = ArrayList<String>(3)
        // čerstvě zkopírovaný text první (do minuty od zkopírování)
        clipChip = recentClip?.takeIf {
            SystemClock.elapsedRealtime() - recentClipAt < CLIP_SUGGEST_MS
        }?.let { "📋 " + it.replace('\n', ' ').trim() }
        clipChip?.let { list.add(it) }
        // předchozí slovo jen z druhého jazyka („meeting“): předpověď z jeho slovníku
        val second = secondLang()
        val predLang = if (second != null && context.prevWord != null &&
            suggester.isKnown(context.prevWord, currentLanguage(), true) != true &&
            suggester.isKnown(context.prevWord, second, true) == true
        ) second else currentLanguage()
        for (w in own + suggester.predict(predLang, context, 3)) {
            if (list.size < 3 && list.none { it.equals(w, ignoreCase = true) }) list.add(w)
        }
        v.suggestions = list
        v.more = emptyList()
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
        // vrácená oprava = tohle slovo chci: naučí se, příště se už neopraví
        if (learnOn && suggestionsOn) userWords.learnNow(typed, langKey())
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
        // četnost jen u slov napsaných ťukáním; nepřepsaná slova z gest by
        // posilovala i chybně rozpoznaná slova (Dneška místo Dneska)
        if (!gestureWord) {
            userWords.use(word, langKey())
            userWords.usePair(wordContext(ic).prevWord, word, langKey())
        }
        userWords.record(word, langKey(), knownAny(word))
    }

    private fun forgetWord(word: String) {
        if (userWords.remove(word, langKey())) {
            flash(t("Zapomenuto: %1\$s", word))
        } else {
            flash(t("„%1\$s“ není mezi naučenými slovy", word))
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
        val analysis = suggester.analyze(
            typed, currentLanguage(), context, secondLang(), currentTaps(), autocorrectStrong
        )
        correction = analysis.correction?.takeIf {
            autocorrectOn && !gestureWord && !reopened &&
                typed.lowercase() !in rejected &&
                !userWords.isLearned(typed, lang) &&
                knownAny(typed) != true
        }
        // naučená slova se nabízejí i při vypnutém učení (to jen nepřidává nová)
        val own = userWords.suggest(typed, lang, 3).map { caseLike(typed, it) }
        val list = ArrayList<String>(3)
        list.add(typed)
        fun add(s: String) {
            if (list.size < 3 && list.none { it.equals(s, ignoreCase = true) }) list.add(s)
        }
        // pořadí slovníku doladí to, co píšeš ty (častá slova, tvoje dvojice slov)
        val ranked = rerankTyped(analysis.words, context)
        // oprava uprostřed, pak nejčastější naučené slovo, slovník a zbytek naučených
        correction?.let { add(it) }
        own.firstOrNull()?.let { add(it) }
        for (s in ranked) add(s)
        own.drop(1).forEach { add(it) }
        // textová zkratka: celý text hned za napsaným slovem
        shortcutTemplate = Shortcuts.expand(this, typed)
        shortcutText = shortcutTemplate?.let { Shortcuts.preview(it).ifEmpty { "…" } }?.also { exp ->
            list.remove(exp)
            list.add(minOf(1, list.size), exp)
            while (list.size > 3) list.removeAt(list.size - 1)
        }
        // emoji ke slovu (pivo → 🍺) na posledním místě
        emojiChip = if (Prefs.emojiSuggest(this) && shortcutText == null) EmojiData.forWord(typed) else null
        emojiChip?.let { em ->
            if (list.size >= 3) list[2] = em else list.add(em)
        }
        // zbytek (i to, co z lišty vytlačila zkratka nebo emoji) do rozbalovacího panelu ⌄
        val more = (listOfNotNull(correction) + own + ranked)
            .filter { w -> list.none { it.equals(w, ignoreCase = true) } }
            .distinctBy { it.lowercase() }
            .take(MAX_MORE)
        v.suggestions = list
        v.more = more
        v.highlight = correction?.let { c -> list.indexOfFirst { it == c } } ?: -1
    }

    /** Emoji nabízené k rozepsanému slovu (jinak null). */
    private var emojiChip: String? = null

    /** Rozepsané slovo je zkratka: její náhled v liště a celý text se značkami (jinak null). */
    private var shortcutText: String? = null
    private var shortcutTemplate: String? = null

    private fun pickSuggestion(word: String) {
        markOwnEdit()
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty() && !composingIntact(ic)) {
            resetComposing()
            return
        }
        if (word == clipChip && composing.isEmpty()) {
            // vložit čerstvě zkopírovaný text
            val text = recentClip
            recentClip = null
            clipChip = null
            if (text != null) pasteClip(text)
            return
        }
        if (word == emojiChip && composing.isNotEmpty()) {
            // emoji za slovo: „pivo 🍺 “
            emojiChip = null
            learnComposing(ic)
            val spaced = commitWithSpace(ic, "$composing $word")
            if (!incognito) EmojiData.addRecent(this, word)
            composing.clear()
            gestureWord = false
            autoSpace = spaced
            correction = null
            view?.suggestions = emptyList()
            updateAutoShift()
            showPredictions()
            return
        }
        if (word == shortcutText && composing.isNotEmpty()) {
            // zkratka: vloží se celý text se značkami, nic se neučí
            shortcutText = null
            val full = Shortcuts.render(this, shortcutTemplate ?: word)
            shortcutTemplate = null
            val cursorAt = full.indexOf(Shortcuts.CURSOR)
            val text = full.replace(Shortcuts.CURSOR, "")
            val wantsSpace = cursorAt < 0 && !text.endsWith("\n") && !text.endsWith(" ")
            val withSpace = if (wantsSpace) {
                commitWithSpace(ic, text)
            } else {
                ic.commitText(text, 1)
                false
            }
            if (cursorAt >= 0) {
                // kurzor na místo %cursor%
                val back = text.length - cursorAt
                ic.getExtractedText(ExtractedTextRequest(), 0)?.let { et ->
                    val pos = et.startOffset + et.selectionEnd - back
                    ic.setSelection(pos, pos)
                }
            }
            composing.clear()
            gestureWord = false
            autoSpace = withSpace
            correction = null
            view?.suggestions = emptyList()
            updateAutoShift()
            showPredictions()
            return
        }
        var learned = false
        if (learnOn && suggestionsOn) {
            val lang = langKey()
            val known = knownAny(word)
            if (!gestureWord && composing.isNotEmpty() && word == composing.toString() && known != true) {
                // klepnutí na přesně napsané slovo = „tohle slovo chci“
                learned = !userWords.isLearned(word, lang)
                userWords.learnNow(word, lang)
            } else {
                userWords.record(word, lang, known)
            }
            userWords.use(word, lang)
            userWords.usePair(wordContext(ic).prevWord, word, lang)
        }
        if (composing.isEmpty()) {
            // předpověď: slovo za mezerou; kdyby mezera chyběla, doplní se
            val before = ic.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && needsSpaceAfter(before[before.length - 1])) ic.commitText(" ", 1)
        }
        val spaced = commitWithSpace(ic, word)
        composing.clear()
        gestureWord = false
        autoSpace = spaced
        correction = null
        view?.suggestions = emptyList()
        updateAutoShift()
        if (learned) {
            updateTools()
            flash(t("Naučeno: %1\$s  ·  podržením návrhu ho zapomeneš", word))
        } else {
            showPredictions()
        }
    }

    // ---- gesta ----

    /** Gesto rozpozná vlastní engine (GestureEngine). */
    private fun onGesture(xs: FloatArray, ys: FloatArray, times: IntArray, count: Int) {
        markOwnEdit()
        if (!suggestionsOn) return
        endFlash()
        autoSpace = false
        val capitalize = shift
        gestures.recognize(xs, ys, count, currentLanguage(), GESTURE_CANDIDATES, secondLang()) { result ->
            applyGesture(result, capitalize)
        }
    }

    private fun applyGesture(result: GestureEngine.Result, capitalize: Boolean, ranked: Boolean = false) {
        val v = view ?: return
        val ic = currentInputConnection ?: return
        if (result.words.isEmpty()) {
            v.suggestions = emptyList()
            v.notice = when {
                !result.ready && result.error.isNotEmpty() -> t("Gesta: %1\$s", result.error)
                !result.ready -> t("Slovník pro gesta se načítá…")
                result.error.isNotEmpty() -> t("Gesta: %1\$s", result.error)
                else -> t("Slovo se nepodařilo rozpoznat")
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

        val best = if (ranked) result.words.take(3) else rerankGesture(result, wordContext(ic)).take(3)
        val words = best.map { if (capitalize) it.replaceFirstChar { c -> c.uppercase() } else it }
        composing.append(words[0])
        ic.setComposingText(composing, 1)
        gestureWord = true
        predicting = false
        updateTools()
        v.suggestions = words
        updateAutoShift()
    }

    /**
     * Přeřazení kandidátů gesta: o vítězi rozhoduje tvar tahu a četnost ve slovníku,
     * slova, která často píšeš, dostanou jen malé zvýhodnění (rozhodne mezi podobnými).
     * Předchozí slovo se tu nepoužívá: dvojice ze slovníku pořadí gest kazily.
     */
    private fun rerankGesture(result: GestureEngine.Result, context: Suggester.WordContext): List<String> {
        if (result.scores.size != result.words.size) return result.words
        val lang = langKey()
        return result.words.indices.map { i ->
            val w = result.words[i]
            var score = result.scores[i]
            val used = userWords.usageOf(w, lang)
            if (used > 0) score += minOf(USAGE_BONUS_MAX, USAGE_WEIGHT * Math.log(1.0 + used))
            // vlastní dvojice (po „Karlových“ jsi už vybral „varů“): silné zvýhodnění
            val paired = userWords.pairCount(context.prevWord, w, lang)
            if (paired > 0) score += minOf(PAIR_BONUS_MAX, PAIR_BONUS + Math.log(paired.toDouble()))
            w to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Návrhy ze slovníku přeřazené podle tebe. Základem je pořadí slovníku (každé místo
     * = 1 bod); časté slovo dostane nejvýš [TYPED_USAGE_MAX], tvoje dvojice „předchozí
     * slovo → slovo“ nejvýš [TYPED_PAIR_MAX]. Slovo tak poskočí o pár míst, ne na úkor
     * návrhu, který sedí mnohem líp.
     */
    private fun rerankTyped(words: List<String>, context: Suggester.WordContext): List<String> {
        if (words.size < 2) return words
        val lang = langKey()
        return words.mapIndexed { i, w ->
            var score = -i.toDouble()
            val used = userWords.usageOf(w, lang)
            if (used > 0) score += minOf(TYPED_USAGE_MAX, TYPED_USAGE_WEIGHT * Math.log(1.0 + used))
            val paired = userWords.pairCount(context.prevWord, w, lang)
            if (paired > 0) score += minOf(TYPED_PAIR_MAX, TYPED_PAIR_BONUS + Math.log(paired.toDouble()))
            w to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** Zapíše místo ťuknutí k právě připsanému znaku rozepsaného slova. */
    private fun trackTap(before: String, added: String, tap: Pair<Int, Int>?) {
        if (tapWord != before) {
            // slovo se mezitím změnilo jinak než psaním: dosavadní písmena bez poloh
            tapXs.clear()
            tapYs.clear()
            repeat(before.codePointCount(0, before.length)) { tapXs.add(-1); tapYs.add(-1) }
        }
        val n = added.codePointCount(0, added.length)
        // jeden znak = jedno ťuknutí; víceznakové klávesy (domény) polohu nemají
        val at = if (n == 1) tap else null
        tapXs.add(at?.first ?: -1)
        tapYs.add(at?.second ?: -1)
        repeat(n - 1) { tapXs.add(-1); tapYs.add(-1) }
        tapWord = composing.toString()
    }

    /** Ťuknutí k rozepsanému slovu, jen když sedí na znak (jinak null). */
    private fun currentTaps(): Suggester.Taps? {
        val word = composing.toString()
        if (tapWord != word || tapXs.size != word.codePointCount(0, word.length)) return null
        return Suggester.Taps(tapXs.toIntArray(), tapYs.toIntArray())
    }

    /** Akce panelu úprav: výběr, schránka a pohyb kurzoru (s Shiftem = označování). */
    private fun editAction(ic: InputConnection, key: Key.Edit) {
        fun send(code: Int, meta: Int = 0) {
            val m = meta or if (key.select) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, m))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, m))
        }
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        when (key.id) {
            EditKey.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            EditKey.COPY -> ic.performContextMenuAction(android.R.id.copy)
            EditKey.CUT -> ic.performContextMenuAction(android.R.id.cut)
            EditKey.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            EditKey.LEFT -> send(KeyEvent.KEYCODE_DPAD_LEFT)
            EditKey.RIGHT -> send(KeyEvent.KEYCODE_DPAD_RIGHT)
            EditKey.UP -> send(KeyEvent.KEYCODE_DPAD_UP)
            EditKey.DOWN -> send(KeyEvent.KEYCODE_DPAD_DOWN)
            EditKey.HOME -> send(KeyEvent.KEYCODE_MOVE_HOME)
            EditKey.END -> send(KeyEvent.KEYCODE_MOVE_END)
            EditKey.DOC_START -> send(KeyEvent.KEYCODE_MOVE_HOME, ctrl)
            EditKey.DOC_END -> send(KeyEvent.KEYCODE_MOVE_END, ctrl)
        }
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

    /**
     * Vloží text a za něj automatickou mezeru, jen když za kurzorem žádná není.
     * Úprava slova uprostřed textu nebo na konci věty: stávající mezera se jen přeskočí,
     * před interpunkcí a koncem řádku se mezera nepřidá. Vrací true, když za textem
     * mezera stojí (nová nebo přeskočená).
     */
    private fun commitWithSpace(ic: InputConnection, text: String): Boolean {
        val next = ic.getTextAfterCursor(1, 0)?.firstOrNull()
        return when {
            next == null || !(next.isWhitespace() || next in NO_SPACE_BEFORE) -> {
                ic.commitText("$text ", 1)
                true
            }
            next == ' ' || next == '\u00A0' -> {
                // kurzor za vložený text i za stávající mezeru
                ic.commitText(text, 2)
                true
            }
            else -> {
                ic.commitText(text, 1)
                false
            }
        }
    }

    /** Stojí-li za kurzorem mezera, kurzor ji přeskočí (místo vložení druhé). */
    private fun skipSpaceAfter(ic: InputConnection): Boolean {
        val next = ic.getTextAfterCursor(1, 0)?.firstOrNull()
        if (next != ' ' && next != '\u00A0') return false
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        if (et.selectionStart != et.selectionEnd) return false
        val pos = et.startOffset + et.selectionEnd + 1
        ic.setSelection(pos, pos)
        return true
    }

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
        // posun kurzoru (tah po mezerníku) je návrat ke slovu, ne psaní
        if (key != Key.CursorLeft && key != Key.CursorRight && key != Key.WordLeft && key != Key.WordRight && key !is Key.Edit) {
            markOwnEdit()
        }
        val ic = currentInputConnection ?: return
        endFlash()
        val pendingSpace = autoSpace
        autoSpace = false
        val fix = lastCorrection
        lastCorrection = null
        if (composing.isNotEmpty() && !composingIntact(ic)) resetComposing()

        when (key) {
            is Key.Char -> {
                // kam prst ťukl (jen písmenné klávesy na obrazovce)
                val tap = view?.takeTap()
                // doména (.cz, .com…) se Shiftem nemění
                val domain = key.label.length > 1 && key.label.startsWith(".")
                val t = if (shift && !domain) key.label.uppercase() else key.label
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
                    reopened = false
                    val before = composing.toString()
                    composing.append(t)
                    trackTap(before, t, tap)
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
                    val space = if (wantsSpaceAfter(t, ic)) {
                        commitWithSpace(ic, t)
                    } else {
                        ic.commitText(t, 1)
                        false
                    }
                    autoSpace = space
                    updateAutoShift()
                    if (space) showPredictions()
                }
                updateAutoShift()
            }
            Key.Shift -> onShiftTap()
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
                    reopened = false
                    val before = composing.toString()
                    val last = Character.charCount(composing.codePointBefore(composing.length))
                    composing.setLength(composing.length - last)
                    if (tapWord == before && tapXs.isNotEmpty()) {
                        tapXs.removeAt(tapXs.size - 1)
                        tapYs.removeAt(tapYs.size - 1)
                        tapWord = composing.toString()
                    }
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
            is Key.Edit -> {
                learnComposing(ic)
                resetComposing()
                editAction(ic, key)
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
                // mezera už tam je (vložená automaticky, nebo stojí za kurzorem
                // při úpravě slova uprostřed textu): druhá se nepřidá, kurzor ji jen přeskočí
                val added = !pendingSpace && !skipSpaceAfter(ic)
                if (added) ic.commitText(" ", 1)
                if (fixed != null && added) lastCorrection = fixed
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
            Key.Voice -> toggleVoice()
            Key.Language -> {
                learnComposing(ic)
                resetComposing()
                nextLanguage()
            }
            Key.Settings -> {
                resetComposing()
                requestHideSelf(0)
                startActivity(
                    android.content.Intent(this, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            is Key.DeleteSwipe -> selectWordsBack(ic, key.words)
            Key.DeleteSwipeEnd -> {
                val anchor = swipeAnchor
                swipeAnchor = -1
                if (anchor >= 0 && !ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
                updateAutoShift()
                showPredictions()
            }
            Key.Symbols, Key.Letters, Key.Emoji, Key.Clipboard, Key.EmojiSearch, Key.ClipClear -> Unit
            is Key.ClipAction -> Unit
        }
    }

    // ---- mazání tahem z ⌫ ----

    /** Pozice kurzoru na začátku tahu (-1 = tah neprobíhá). */
    private var swipeAnchor = -1

    private fun selectWordsBack(ic: InputConnection, words: Int) {
        if (swipeAnchor < 0) {
            learnComposing(ic)
            resetComposing()
            val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
            swipeAnchor = et.startOffset + maxOf(et.selectionStart, et.selectionEnd)
        }
        val anchor = swipeAnchor
        if (words <= 0) {
            ic.setSelection(anchor, anchor)
            return
        }
        // označený text je součástí textu před kotvou, kurzor stojí na začátku označení
        val before = ic.getTextBeforeCursor(SWIPE_LOOKBACK, 0)?.toString() ?: return
        val text = before + (ic.getSelectedText(0)?.toString() ?: "")
        var i = text.length
        repeat(words) {
            while (i > 0 && text[i - 1].isWhitespace()) i--
            while (i > 0 && !text[i - 1].isWhitespace()) i--
        }
        val len = text.length - i
        ic.setSelection(anchor - len, anchor)
    }

    private companion object {
        const val NOTICE_OFF = "Návrhy jsou v tomto poli vypnuté"
        const val WORD_LOOKBACK = 64

        /** Kolik znaků před kurzorem se čte při návratu ke slovu. */
        const val REOPEN_LOOKBACK = 40

        /** Změny kurzoru do této doby po vlastním psaní se neberou jako návrat ke slovu. */
        const val OWN_EDIT_MS = 600L

        /** Návrhy k slovu až po ustálení kurzoru. */
        const val REOPEN_DELAY_MS = 150L

        /** Dvojitý stisk Shiftu do této doby zamkne velká písmena. */
        const val CAPS_DOUBLE_TAP_MS = 400L

        /** Jazyky už byly jednou zapnuty (Android 14+). */
        const val KEY_LANGS_ENABLED = "langs_enabled_v4"

        /** Kolik znaků před kurzorem se čte při mazání tahem z ⌫. */
        const val SWIPE_LOOKBACK = 2000

        /** Znaky, které ukončí slovo a slovo se počítá jako napsané (pro učení). */
        const val WORD_END = ".,!?;:…)\"»“”"

        /** Po těchto znacích se automaticky přidá mezera. */
        const val AUTO_SPACE = ".,!?"

        /** Před těmito znaky (za kurzorem) se automatická mezera nevkládá. */
        const val NO_SPACE_BEFORE = ".,!?;:…)]}\"»“”"

        /** Kolik posledních zkopírovaných textů se drží. */
        const val MAX_CLIPS = 20

        /** Jak dlouho se čerstvě zkopírovaný text nabízí v liště. */
        const val CLIP_SUGGEST_MS = 60_000L

        /** Jednorázový kód: jen 4–8 číslic. */
        val OTP = Regex("\\d{4,8}")

        /** Připnuté položky schránky. */
        const val CLIP_PREFS = "pekey_clipboard"
        const val KEY_PINNED = "pinned"
        const val PIN_SEP = "\u0000"

        /** Kolik znaků před kurzorem se čte kvůli předchozímu slovu. */
        const val CONTEXT_LOOKBACK = 48

        /** Kandidáti gesta před přeřazením (zobrazí se nejlepší tři). */
        const val GESTURE_CANDIDATES = 8

        /** Bonus gesta za slova, která často píšeš: WEIGHT × ln(1 + počet), nejvýš MAX. */
        const val USAGE_WEIGHT = 0.3
        const val USAGE_BONUS_MAX = 1.0

        /** Bonus gesta za vlastní dvojici slov: BONUS + ln(počet), nejvýš MAX. */
        const val PAIR_BONUS = 2.5
        const val PAIR_BONUS_MAX = 4.0

        /** Přeřazení návrhů při psaní (body = místa v pořadí slovníku). */
        const val TYPED_USAGE_WEIGHT = 0.5
        const val TYPED_USAGE_MAX = 1.5
        const val TYPED_PAIR_BONUS = 2.0
        const val TYPED_PAIR_MAX = 3.5

        /** Nejvíc návrhů v rozbalovacím panelu (⌄). */
        const val MAX_MORE = 15
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
    /** Tah z ⌫ doleva: označí tolik slov před kurzorem (0 = zrušit označení). */
    data class DeleteSwipe(val words: Int) : Key
    /** Konec tahu z ⌫: smaže označený text. */
    data object DeleteSwipeEnd : Key
    /** Hledání emoji (přepíná klávesnice). */
    data object EmojiSearch : Key
    /** Akce v panelu schránky: hledání, výběr (řeší klávesnice). */
    data class ClipAction(val id: Int) : Key
    /** Smazat historii schránky (připnuté zůstanou; přepíná klávesnice). */
    data object ClipClear : Key
    /** Další jazyk Pekey (čeština ↔ angličtina). */
    data object Language : Key
    /** Otevřít nastavení Pekey. */
    data object Settings : Key
    /** Hlasové zadávání: začít / skončit nahrávání. */
    data object Voice : Key

    /** Panel úprav textu; [select] = šipky rozšiřují výběr. */
    data class Edit(val id: Int, val select: Boolean = false) : Key
}
