package cz.pekey

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class PekeyService : InputMethodService() {

    private var shift = false
    private var view: PekeyKeyboardView? = null
    private lateinit var suggester: Suggester
    private lateinit var gestures: GestureEngine
    private val composing = StringBuilder()
    private var suggestionsOn = false

    /** Rozepsané slovo vzniklo gestem: backspace ho smaže celé, další písmeno začne nové slovo. */
    private var gestureWord = false

    override fun onCreate() {
        super.onCreate()
        suggester = Suggester(this)
        gestures = GestureEngine(this)
    }

    override fun onDestroy() {
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
        v.onGesture = { xs, ys, n -> onGesture(xs, ys, n) }
        view = v
        v.notice = if (suggestionsOn) "" else NOTICE_OFF
        v.gestureEnabled = suggestionsOn
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetComposing()
        suggestionsOn = allowSuggestions(info)
        view?.notice = if (suggestionsOn) "" else NOTICE_OFF
        view?.gestureEnabled = suggestionsOn
        if (suggestionsOn) {
            suggester.refresh()
            gestures.prepare(currentLanguage())
        }
        updateAutoShift()
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
        if (composing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composing.clear()
        }
        gestureWord = false
        view?.suggestions = emptyList()
    }

    /** Zjistí přímo v poli, jestli před kurzorem pořád stojí rozepsané slovo. */
    private fun composingIntact(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(composing.length, 0) ?: return false
        return before.toString() == composing.toString()
    }

    private fun currentLanguage(): String? =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .currentInputMethodSubtype?.locale

    private fun showSuggestions() {
        val v = view ?: return
        if (!suggestionsOn || composing.isEmpty()) {
            v.suggestions = emptyList()
            return
        }
        val typed = composing.toString()
        val list = ArrayList<String>(3)
        list.add(typed)
        for (s in suggester.suggest(typed, currentLanguage())) {
            if (list.size >= 3) break
            if (list.none { it.equals(s, ignoreCase = true) }) list.add(s)
        }
        v.suggestions = list
    }

    private fun pickSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty() && !composingIntact(ic)) {
            resetComposing()
            return
        }
        ic.commitText("$word ", 1)
        composing.clear()
        gestureWord = false
        view?.suggestions = emptyList()
        updateAutoShift()
    }

    // ---- gesta ----

    private fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) {
        if (!suggestionsOn) return
        val capitalize = shift
        gestures.recognize(xs, ys, count, currentLanguage(), 3) { result ->
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

        // předchozí slovo potvrdit a oddělit mezerou
        if (composing.isNotEmpty()) {
            if (composingIntact(ic)) ic.finishComposingText()
            composing.clear()
        }
        val before = ic.getTextBeforeCursor(1, 0)
        if (!before.isNullOrEmpty() && needsSpaceAfter(before[before.length - 1])) {
            ic.commitText(" ", 1)
        }

        val words = result.words.map { if (capitalize) it.replaceFirstChar { c -> c.uppercase() } else it }
        composing.append(words[0])
        ic.setComposingText(composing, 1)
        gestureWord = true
        v.suggestions = words.take(3)
        updateAutoShift()
    }

    private fun needsSpaceAfter(c: Char): Boolean =
        !c.isWhitespace() && c != '(' && c != '[' && c != '{' && c != '"' && c != '\'' &&
            c != '„' && c != '/' && c != '@' && c != '#'

    private fun isWordChar(s: String): Boolean {
        val cp = s.codePointAt(0)
        return s.length == Character.charCount(cp) && Character.isLetter(cp)
    }

    private fun onKey(key: Key) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty() && !composingIntact(ic)) resetComposing()

        when (key) {
            is Key.Char -> {
                val t = if (shift) key.label.uppercase() else key.label
                if (gestureWord && isWordChar(t) && composing.isNotEmpty()) {
                    // po slově z gesta začíná písmeno nové slovo
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
                    resetComposing()
                    ic.commitText(t, 1)
                }
                updateAutoShift()
            }
            Key.Shift -> setShift(!shift)
            Key.Backspace -> {
                if (gestureWord && composing.isNotEmpty()) {
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
            }
            Key.Space -> {
                resetComposing()
                ic.commitText(" ", 1)
                updateAutoShift()
            }
            Key.Enter -> {
                resetComposing()
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                updateAutoShift()
            }
            Key.SwitchIme -> {
                resetComposing()
                switchKeyboard()
            }
            // přepínání vrstev a emoji řeší samotná klávesnice (view)
            Key.Symbols, Key.Letters, Key.Emoji -> Unit
        }
    }

    private companion object {
        const val NOTICE_OFF = "Návrhy jsou v tomto poli vypnuté"
    }
}

sealed interface Key {
    data class Char(val label: String) : Key
    data object Shift : Key
    data object Backspace : Key
    data object Space : Key
    data object Enter : Key
    data object Symbols : Key
    data object Letters : Key
    data object Emoji : Key
    data object SwitchIme : Key
}
