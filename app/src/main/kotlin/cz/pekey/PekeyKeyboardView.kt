package cz.pekey

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** QWERTZ (cs/en): symboly pod písmeny i čísly, vrstva symbolů, emoji, lišta návrhů. */
class PekeyKeyboardView(
    context: Context,
    private val onKey: (Key) -> Unit
) : View(context) {

    var shiftOn: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Návrhy slov v pruhu nad klávesami (nejvýš tři). */
    var suggestions: List<String> = emptyList()
        set(value) {
            highlight = -1
            if (field != value) {
                field = value
                // jiné návrhy: staré „další“ už neplatí (služba je pošle znovu)
                more = emptyList()
                invalidate()
            }
        }

    /** Další návrhy pro rozbalovací panel (⌄ v liště); nastavuje se po [suggestions]. */
    var more: List<String> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                if (value.isEmpty()) moreOpen = false
                invalidate()
            }
        }

    /** Panel dalších návrhů je otevřený místo kláves. */
    private var moreOpen = false

    /** Stisknutý návrh v panelu (-1 = žádný). */
    private var morePressed = -1

    /** Místo, kam prst dopadl na písmennou klávesu (souřadnice klávesnice); jednou se vyzvedne. */
    private var pendingTap: Pair<Int, Int>? = null
    private var tapDownX = 0f
    private var tapDownY = 0f

    /** Vyzvedne místo posledního ťuknutí na písmeno (null = písmeno nebylo ťuknuté na obrazovce). */
    fun takeTap(): Pair<Int, Int>? {
        val t = pendingTap
        pendingTap = null
        return t
    }

    /** Návrh, který se při mezerníku použije jako oprava (zvýrazněný); -1 = žádný. */
    var highlight: Int = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Šedý hint v pruhu, když nejsou žádné návrhy (např. proč jsou vypnuté). */
    var notice: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onGeometry: ((KeyGeometry) -> Unit)? = null
    var onSuggestionPicked: ((String) -> Unit)? = null

    /** Historie schránky (nejnovější první); plní ji služba. */
    var clipItems: List<String> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Otevření panelu schránky (služba načte aktuální obsah schránky). */
    var onClipOpen: (() -> Unit)? = null
    var onClipPicked: ((String) -> Unit)? = null
    var onClipRemove: ((String) -> Unit)? = null

    /** Připnuté položky schránky (zůstávají nahoře a přežijí restart). */
    var pinnedClips: Set<String> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var onClipPin: ((String) -> Unit)? = null
    var onClipClearAll: (() -> Unit)? = null

    /** Podržený návrh (např. zapomenout naučené slovo). */
    var onSuggestionLongPress: ((String) -> Unit)? = null

    /**
     * Jazyk ("cs", "en"): určuje pořadí variant dlouhého stisku.
     * Česky je první diakritika (á), anglicky symbol (@).
     */
    var language: String = "cs"
        set(value) {
            if (field == value) return
            val relayout = layoutRows(field) != layoutRows(value) ||
                (czechRowOn && (field == "cs") != (value == "cs"))
            field = value
            if (relayout) {
                // jiné rozložení písmen (QWERTZ, QWERTY, AZERTY, ЙЦУКЕН)
                letterRows = buildLetterRows()
                if (width > 0) layoutKeys(width, height)
            }
            invalidate()
        }

    /** Jméno na mezerníku (nastavení; prázdné = výchozí nápis). */
    private var spaceName = ""

    /** Angličtina na QWERTY (nastavení). */
    private var enQwerty = false

    /** Zamčená velká písmena (dvojitý stisk Shiftu). */
    var capsLock: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Tři řady písmen podle jazyka. */
    private fun layoutRows(lang: String): List<String> = when (lang) {
        "fr" -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
        "pl", "it" -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        "en" -> if (enQwerty) listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        else listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
        "uk" -> listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю")
        else -> listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
    }

    /** Písmena s diakritikou na podržení podle jazyka (čeština je v základní mapě). */
    private val langAccents: Map<String, Map<String, List<String>>> = mapOf(
        "fr" to mapOf(
            "e" to listOf("é", "è", "ê", "ë"), "a" to listOf("à", "â", "æ"), "u" to listOf("ù", "û", "ü"),
            "i" to listOf("î", "ï"), "o" to listOf("ô", "œ"), "c" to listOf("ç"), "y" to listOf("ÿ")
        ),
        "it" to mapOf(
            "e" to listOf("è", "é"), "a" to listOf("à"), "i" to listOf("ì"), "o" to listOf("ò"), "u" to listOf("ù")
        ),
        "pl" to mapOf(
            "a" to listOf("ą"), "c" to listOf("ć"), "e" to listOf("ę"), "l" to listOf("ł"), "n" to listOf("ń"),
            "o" to listOf("ó"), "s" to listOf("ś"), "z" to listOf("ż", "ź"), "x" to listOf("ź")
        ),
        "de" to mapOf(
            "a" to listOf("ä"), "o" to listOf("ö"), "u" to listOf("ü"), "s" to listOf("ß")
        ),
        "uk" to mapOf(
            "г" to listOf("ґ"), "ь" to listOf("'"), "є" to listOf("ʼ")
        )
    )

    /** Vlajka aktuální jazykové sady na mezerníku (nastavuje služba). */
    var spaceFlag: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * Lišta je bez rozepsaného slova: vpravo vedle emoji nástroje (schránka, zpět),
     * návrhy (předpověď dalšího slova) jsou vlevo od nich.
     */
    var toolsVisible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Anonymní režim (pole si ho vyžádalo nebo je zapnutý ručně); ukazuje se na mezerníku. */
    var incognito: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Ruční zapnutí / vypnutí anonymního režimu (panel úprav). */
    var onIncognito: (() -> Unit)? = null

    /**
     * Je připojená fyzická klávesnice: vidět je jen lišta s návrhy a nástroji.
     * Emoji, schránka a panel úprav se otevřou v plné výšce.
     */
    var hardKeyboard: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    /** Klávesnice je složená jen na lištu. */
    private fun collapsed() = hardKeyboard && !emoji && !clip && !edit && !emojiSearch

    /** Naposledy rozložená výška (složená / plná); při změně se klávesnice přeměří. */
    private var laidCollapsed = false

    override fun invalidate() {
        if (collapsed() != laidCollapsed) {
            laidCollapsed = collapsed()
            requestLayout()
        }
        super.invalidate()
    }

    /** Přepnutí režimu jedné ruky z klávesnice (služba uloží do nastavení). */
    var onOneHand: ((String) -> Unit)? = null

    /** Psaní gesty; vypíná se v polích bez návrhů (hesla, e-maily…). */
    var gestureEnabled: Boolean = true

    /** Dokončené gesto: souřadnice bodů, časy v ms od začátku tahu a počet bodů. */
    var onGesture: ((FloatArray, FloatArray, IntArray, Int) -> Unit)? = null

    private class Cell(val key: Key, val label: String, val weight: Float, val rect: RectF = RectF())

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun c(s: String, w: Float = 1f) = Cell(Key.Char(s), s, w)

    // nastavení rozložení (applySettings)
    private var numberRowOn = true

    /** Horní řada s českou diakritikou místo číslic (jen při češtině). */
    private var czechRowOn = false
    private var bottomLeft = ","
    private var bottomRight = "."
    private var oneHand = Prefs.ONE_HAND_OFF

    /**
     * Druh pole (nastavuje služba): v e-mailu a adrese má spodní řada @ nebo / a klávesu
     * s doménou (.cz, podržením další).
     */
    var fieldMode: Int = FieldMode.TEXT
        set(value) {
            if (field != value) {
                field = value
                letterRows = buildLetterRows()
                if (width > 0) layoutKeys(width, height)
                invalidate()
            }
        }

    /** Spodní řada písmen pro e-mail a adresu: @ nebo /, mezerník, .cz, tečka. */
    private fun webBottom(): List<Cell> {
        val left = if (fieldMode == FieldMode.EMAIL) "@" else "/"
        return if (BuildConfig.LANGUAGE_SWITCH) listOf(
            Cell(Key.Symbols, "#+=", 1.4f),
            Cell(Key.Language, "", 1f),
            c(left, 1f),
            Cell(Key.Space, SPACE_LABEL, 2.9f),
            c(FieldMode.DOMAIN, 1f),
            c(".", 1f),
            Cell(Key.Enter, "↵", 1.7f)
        ) else listOf(
            Cell(Key.Symbols, "#+=", 1.4f),
            c(left, 1.1f),
            Cell(Key.Space, SPACE_LABEL, 3.7f),
            c(FieldMode.DOMAIN, 1.1f),
            c(".", 1.1f),
            Cell(Key.Enter, "↵", 1.7f)
        )
    }

    private fun bottom(switch: Key, label: String): List<Cell> =
        if (BuildConfig.LANGUAGE_SWITCH) listOf(
            Cell(switch, label, 1.4f),
            Cell(Key.Language, "", 1f),
            c(bottomLeft, 1f),
            Cell(Key.Space, SPACE_LABEL, 3.9f),
            c(bottomRight, 1f),
            Cell(Key.Enter, "↵", 1.7f)
        ) else listOf(
            // jen jeden jazyk: bez klávesy pro jeho přepínání
            Cell(switch, label, 1.4f),
            c(bottomLeft, 1.1f),
            Cell(Key.Space, SPACE_LABEL, 4.8f),
            c(bottomRight, 1.1f),
            Cell(Key.Enter, "↵", 1.7f)
        )

    private fun numberRow() = "1234567890".map { c(it.toString()) }

    /** Česká horní řada je teď vidět (zapnutá a píše se česky). */
    private fun czechRowShown() = numberRowOn && czechRowOn && language == "cs"

    /** Číslice na české horní řadě (podržením), stejně jako na české klávesnici. */
    private val czechDigits: Map<String, String> =
        CZECH_ROW.zip("1234567890").associate { (k, v) -> k.toString() to v.toString() }

    private fun topRow(): List<Cell> =
        if (czechRowShown()) CZECH_ROW.map { c(it.toString()) } else numberRow()

    private fun buildLetterRows(): List<List<Cell>> {
        val rows = ArrayList<List<Cell>>()
        if (numberRowOn) rows.add(topRow())
        val lr = layoutRows(language)
        rows.add(lr[0].map { c(it.toString()) })
        rows.add(lr[1].map { c(it.toString()) })
        rows.add(
            listOf(Cell(Key.Shift, "⇧", 1.5f)) +
                lr[2].map { c(it.toString()) } +
                listOf(Cell(Key.Backspace, "⌫", 1.5f))
        )
        rows.add(if (fieldMode == FieldMode.TEXT) bottom(Key.Symbols, "#+=") else webBottom())
        return rows
    }

    /**
     * Vrstva symbolů má číselnou řadu vždy. Bez číselné řady u písmen je kompaktní
     * (stejně vysoká jako písmena): závorky [] {} <> a € _ jsou podržením na ( ) $ -.
     */
    private fun buildSymbolRows(): List<List<Cell>> =
        if (numberRowOn) listOf(
            numberRow(),
            "@#\$%&-+()".map { c(it.toString()) },
            "[]{}<>=_€".map { c(it.toString()) },
            listOf(c("/", 1.5f)) +
                "*\"':;!?".map { c(it.toString()) } +
                listOf(Cell(Key.Backspace, "⌫", 1.5f)),
            bottom(Key.Letters, "ABC")
        ) else listOf(
            numberRow(),
            "@#\$%&-+()/".map { c(it.toString()) },
            "*\"':;!?=".map { c(it.toString()) } + listOf(Cell(Key.Backspace, "⌫", 2f)),
            bottom(Key.Letters, "ABC")
        )

    private var letterRows: List<List<Cell>> = buildLetterRows()
    private var symbolRows: List<List<Cell>> = buildSymbolRows()

    /** Řad v nejvyšší vrstvě: určuje výšku klávesnice (symboly mají číselnou řadu vždy). */
    private fun rowCount() = maxOf(letterRows.size, symbolRows.size)

    /** Spodní řada schránky: smazání historie (dvojím klepnutím). */
    private val clipNormalBottom = listOf(
        Cell(Key.Letters, "ABC", 1.4f),
        Cell(Key.ClipAction(CLIP_SEARCH), "🔍", 1.2f),
        Cell(Key.ClipClear, "Smazat historii", 3.6f),
        Cell(Key.Backspace, "⌫", 1.5f)
    )

    /** Spodní řada při výběru položek (podržením). */
    private val clipSelectBottom = listOf(
        Cell(Key.ClipAction(CLIP_SEL_CANCEL), "Zrušit", 2f),
        Cell(Key.ClipAction(CLIP_SEL_PIN), "Připnout", 2f),
        Cell(Key.ClipAction(CLIP_SEL_DELETE), "Smazat", 2f)
    )
    private val clipBottom get() = if (clipSelect) clipSelectBottom else clipNormalBottom

    // hledání a výběr ve schránce
    private var clipQuery = ""
    private var searchClip = false
    private var clipSelect = false
    private val clipSelected = LinkedHashSet<String>()

    /** Položky schránky podle hledání (bez diakritiky, bez ohledu na velikost písmen). */
    private val clipShown: List<String>
        get() {
            if (clipQuery.isBlank()) return clipItems
            val q = plainText(clipQuery.trim())
            return clipItems.filter { plainText(it).contains(q) }
        }

    private fun plainText(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
    private var clearArmedUntil = 0L

    private val emojiBottom = listOf(
        Cell(Key.Letters, "ABC", 1.4f),
        Cell(Key.EmojiSearch, "🔍", 1.2f),
        Cell(Key.Space, SPACE_LABEL, 4.4f),
        Cell(Key.Backspace, "⌫", 1.5f)
    )

    private var symbols = false
    private var emoji = false
    private var clip = false

    /** Panel úprav textu (šipky, výběr, kopírování) místo písmen. */
    private var edit = false

    /** V panelu úprav je zapnuté označování: šipky rozšiřují výběr. */
    private var editSelect = false

    private fun e(id: Int, label: String) = Cell(Key.Edit(id), label, 1f)

    // odezva při stisku (nastavení): vibrace v ms a zvuk
    private var vibrateMs = 0

    /** Rychlost kurzoru při tahu po mezerníku (nastavení, Prefs.CURSOR_SPEED_*). */
    private var cursorSpeed = Prefs.CURSOR_SPEED_DEFAULT
    private var keySound = false
    private val vibrator: android.os.Vibrator? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }
    private val audio: android.media.AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    }

    /** Krátká vibrace a zvuk podle klávesy, jsou-li zapnuté. */
    private fun feedback(key: Key) {
        if (vibrateMs > 0) {
            try {
                val amp = (vibrateMs * 6).coerceIn(40, 255)
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(vibrateMs.toLong(), amp))
            } catch (_: Exception) {
            }
        }
        if (keySound) {
            val fx = when (key) {
                Key.Backspace -> android.media.AudioManager.FX_KEYPRESS_DELETE
                Key.Space -> android.media.AudioManager.FX_KEYPRESS_SPACEBAR
                Key.Enter -> android.media.AudioManager.FX_KEYPRESS_RETURN
                else -> android.media.AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, 0.4f)
        }
    }

    /** Podržená šipka v panelu úprav se opakuje. */
    private var editRepeating = false
    private val editRepeat = object : Runnable {
        override fun run() {
            val p = pressed ?: return
            editRepeating = true
            handleKey(p.key)
            postDelayed(this, EDIT_REPEAT_MS)
        }
    }

    private val editRows: List<List<Cell>> = listOf(
        listOf(e(EditKey.SELECT_ALL, "Vybrat vše"), e(EditKey.COPY, "Kopírovat"), e(EditKey.CUT, "Vyjmout"), e(EditKey.PASTE, "Vložit")),
        listOf(e(EditKey.HOME, "⇤"), e(EditKey.UP, "↑"), e(EditKey.END, "⇥"), e(EditKey.SELECT, "Označit")),
        listOf(e(EditKey.LEFT, "←"), e(EditKey.DOWN, "↓"), e(EditKey.RIGHT, "→"), Cell(Key.Backspace, "⌫", 1f)),
        listOf(
            Cell(Key.Letters, "ABC", 1f), e(EditKey.DOC_START, "Začátek"),
            e(EditKey.DOC_END, "Konec"), e(EditKey.INCOGNITO, "Anonymně"), Cell(Key.Enter, "↵", 1f)
        )
    )

    private val rows: List<List<Cell>>
        get() = when {
            edit -> editRows
            numericMode != NumMode.OFF && !symbols -> numericRows
            symbols -> symbolRows
            else -> letterRows
        }

    /** Číselná klávesnice pro pole na čísla, PIN a telefon (nastavuje služba podle pole). */
    var numericMode: Int = NumMode.OFF
        set(value) {
            if (field != value) {
                field = value
                numericRows = buildNumericRows()
                symbols = false
                if (width > 0) layoutKeys(width, height)
                invalidate()
            }
        }
    private var numericRows: List<List<Cell>> = emptyList()

    private fun buildNumericRows(): List<List<Cell>> {
        val phone = numericMode == NumMode.PHONE
        return listOf(
            listOf(c("1"), c("2"), c("3"), c(if (phone) "+" else "-")),
            listOf(c("4"), c("5"), c("6"), Cell(Key.Space, "␣", 1f)),
            listOf(c("7"), c("8"), c("9"), Cell(Key.Backspace, "⌫", 1f)),
            listOf(
                if (phone) c("*") else c(","),
                c("0"),
                if (phone) c("#") else c("."),
                Cell(Key.Enter, "↵", 1f)
            )
        )
    }

    /** Číselné klávesy jsou vyšší: čtyři řady na celou výšku klávesnice. */
    private fun placeNumericRows(left: Float, right: Float) {
        placeGrid(numericRows, left, right)
        placeGrid(editRows, left, right)
    }

    /** Mřížka stejně širokých kláves přes celou výšku klávesnice. */
    private fun placeGrid(grid: List<List<Cell>>, left: Float, right: Float) {
        if (grid.isEmpty()) return
        val area = rowCount() * (rowH + gap) - gap
        val h = (area - gap * (grid.size - 1)) / grid.size
        grid.forEachIndexed { r, row ->
            val unit = (right - left - gap * (row.size + 1)) / row.size
            val y = stripH + gap + r * (h + gap)
            row.forEachIndexed { i, cell ->
                val x = left + gap + i * (unit + gap)
                cell.rect.set(x, y, x + unit, y + h)
            }
        }
    }

    /** Dlouhý stisk: nejdřív česká diakritika, pak symbol. První varianta je předvybraná. */
    private val variants = mapOf(
        "1" to listOf("!"),
        "2" to listOf("@"),
        "3" to listOf("#"),
        "4" to listOf("$"),
        "5" to listOf("%"),
        "6" to listOf("^"),
        "7" to listOf("&"),
        "8" to listOf("*"),
        "9" to listOf("("),
        "0" to listOf(")"),
        "q" to listOf("%"),
        "w" to listOf("\\"),
        "e" to listOf("ě", "é", "|"),
        "r" to listOf("ř", "="),
        "t" to listOf("ť", "["),
        "z" to listOf("ž", "]"),
        "u" to listOf("ů", "ú", "<"),
        "i" to listOf("í", ">"),
        "o" to listOf("ó", "{"),
        "p" to listOf("}"),
        "a" to listOf("á", "@"),
        "s" to listOf("š", "#"),
        "d" to listOf("ď", "€"),
        "f" to listOf("_"),
        "g" to listOf("&"),
        "h" to listOf("-"),
        "j" to listOf("+"),
        "k" to listOf("("),
        "l" to listOf(")"),
        "y" to listOf("ý", "*"),
        "x" to listOf("\""),
        "c" to listOf("č", "'"),
        "v" to listOf(":"),
        "b" to listOf(";"),
        "n" to listOf("ň", "!"),
        "m" to listOf("?"),
        "." to listOf("!", "?", ":", ";", "…"),
        "," to listOf("/"),
        // vrstva symbolů
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "$" to listOf("€", "£"),
        "-" to listOf("_", "–", "—"),
        "+" to listOf("=", "±"),
        "*" to listOf("×", "•"),
        "/" to listOf("\\", "|"),
        // klávesa s doménou v e-mailu a adrese
        FieldMode.DOMAIN to listOf(".com", ".eu", ".sk", ".org", ".net", ".de")
    )

    /** Kategorie emoji (bez znaků, které telefon neumí vykreslit). */
    private val emojiCats: List<EmojiData.Category> by lazy {
        EmojiData.categories(Paint().apply { textSize = 28 * d })
    }

    /** Vybraná záložka: 0 = naposledy použité, 1.. = kategorie. */
    private var emojiTab = 0
    private var recentEmoji: List<String> = emptyList()
    private val emojis: List<String>
        get() = if (emojiTab == 0) recentEmoji else emojiCats[emojiTab - 1].items

    // hledání emoji: písmena se píšou do hledání, ne do pole
    private var emojiSearch = false
    private var emojiQuery = ""
    private var emojiResults: List<String> = emptyList()

    // barvy se nastaví v applySettings() podle motivu
    private val bg = Paint()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyDown = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyOn = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Shift a Enter: modrá stažená na polovinu (smíchaná s barvou klávesy). */
    private val keyOnSoft = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        255,
        (Color.red(a) * t + Color.red(b) * (1 - t)).toInt(),
        (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(),
        (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt()
    )
    private val popupBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 22 * d
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT; textSize = 12 * d
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 28 * d
    }
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 20 * d
    }
    private val suggestText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 18 * d
    }
    /** Kreslené ikony (Enter, schránka, zpět). */
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPath = Path()
    private val clipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16 * d }
    private val emojiButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 18 * d
    }
    private val suggestBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 18 * d
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val noticePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 13 * d
    }
    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT; textSize = 14 * d
    }

    /** Vlajka na mezerníku: menší a s ubranou sytostí. */
    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 11 * d
        alpha = 190
        colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix().apply { setSaturation(0.35f) }
        )
    }
    private val divider = Paint()

    /** Text na modrých klávesách (Shift). */
    private val onAccentText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 22 * d; color = ON_ACCENT
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val previewText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 30 * d
    }
    private val gap = 4 * d

    /** Výška řady kláves; mění se v nastavení. */
    private var rowH = BASE_ROW_DP * d

    /** Volný pruh nad klávesami: lišta návrhů a varianty dlouhého stisku. */
    private val stripH get() = rowH + gap

    /** Symboly v rozích kláves. */
    private var showHints = true

    /** Bublina se stisknutým znakem nad prstem. */
    private var showPreview = true

    /** Zaoblení kláves; u tvaru „bez rámečků“ se klávesa kreslí jen při stisku. */
    private var keyRadius = 8 * d
    private var flatKeys = false

    private var look: Look? = null

    init {
        applySettings(
            Look(
                Palette.DARK, 100, 100, true, true, Prefs.ONE_HAND_OFF, ",", ".",
                Prefs.SHAPE_ROUNDED, Prefs.FONT_DEFAULT, true, false
            )
        )
    }

    /** Motiv, výška, tvar, písmo, rozložení a jedna ruka podle nastavení. */
    fun applySettings(l: Look) {
        if (l == look) return
        val old = look
        look = l
        val palette = l.palette
        bg.color = palette.background
        keyPaint.color = palette.key
        keyDown.color = palette.keyDown
        keyOn.color = palette.accent
        keyOnSoft.color = blend(palette.accent, palette.key, 0.5f)
        popupBg.color = palette.popup
        text.color = palette.text
        hintPaint.color = palette.hint
        suggestText.color = palette.text
        suggestBold.color = palette.text
        noticePaint.color = palette.notice
        spacePaint.color = palette.notice
        divider.color = palette.divider
        iconPaint.color = palette.text
        clipText.color = palette.text
        previewText.color = palette.text
        trailPaint.color = palette.trail
        trailColor = palette.trail
        showHints = l.hints
        showPreview = l.preview

        keyRadius = when (l.shape) {
            Prefs.SHAPE_ROUND -> 18 * d
            Prefs.SHAPE_SQUARE -> 2 * d
            else -> 8 * d
        }
        flatKeys = l.shape == Prefs.SHAPE_FLAT

        val base = when (l.font) {
            Prefs.FONT_MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            Prefs.FONT_CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        for (p in listOf(text, hintPaint, suggestText, noticePaint, clipText, previewText)) p.typeface = base
        suggestBold.typeface = Typeface.create(base, Typeface.BOLD)

        rowH = BASE_ROW_DP * d * l.height / 100f
        // nižší klávesy: o něco menší písmo, aby se vešlo
        val scale = (l.height / 100f).coerceIn(0.8f, 1.15f)
        text.textSize = 22 * d * scale
        hintPaint.textSize = 12 * d * scale

        widthPercent = l.width
        vibrateMs = l.vibrate
        cursorSpeed = l.cursorSpeed
        keySound = l.sound
        val layoutChanged = old == null || old.height != l.height || old.width != l.width ||
            old.numberRow != l.numberRow || old.czechRow != l.czechRow || old.enQwerty != l.enQwerty ||
            old.oneHand != l.oneHand || old.bottomLeft != l.bottomLeft || old.bottomRight != l.bottomRight
        if (layoutChanged) {
            numberRowOn = l.numberRow
            czechRowOn = l.czechRow
            enQwerty = l.enQwerty
            spaceName = l.spaceName
            bottomLeft = l.bottomLeft
            bottomRight = l.bottomRight
            oneHand = l.oneHand
            letterRows = buildLetterRows()
            symbolRows = buildSymbolRows()
            pressed = null
            popup = null
            selected = null
            requestLayout()
            if (width > 0) layoutKeys(width, measuredHeightFor())
        }
        invalidate()
    }

    private var pressed: Cell? = null
    private var stripPressed = -1

    // lišta s variantami
    private var popup: List<Cell>? = null
    private var popupRect = RectF()
    private var selected: Cell? = null

    // emoji
    private val cols = 8
    private var emojiScroll = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false

    // gesto
    private var glideX = FloatArray(256)
    private var glideY = FloatArray(256)
    private var glideT = IntArray(256)
    private var glideStart = 0L
    private var glideCount = 0
    private var glideArmed = false
    private var gliding = false
    private val trail = Path()
    private var trailColor = 0xFF2D7BFF.toInt()

    // podržený backspace maže po slovech
    private var deleteRepeating = false
    private val deleteRepeat = object : Runnable {
        override fun run() {
            deleteRepeating = true
            onKey(Key.DeleteWord)
            postDelayed(this, DELETE_REPEAT_MS)
        }
    }

    // tah po mezerníku posouvá kurzor
    private var spaceArmed = false
    private var cursorMode = false

    /** Tah z ⌫ doleva: označí slova před kurzorem, po puštění je smaže. */
    private var deleteArmed = false
    private var deleteSwipe = false
    private var deleteStartX = 0f
    private var deleteWords = 0
    private var spaceStartX = 0f
    private var spaceLastX = 0f

    /** Poloha prstu ve chvíli, kdy se otevřela lišta variant. */
    private var popupAnchorX = 0f
    private var lastTouchX = 0f

    /** Číslice podržením: na horní řadě písmen bez číselné řady, nebo na české řadě. */
    private fun digitFor(label: String): String? = when {
        !numberRowOn -> topDigits[label]
        czechRowShown() -> czechDigits[label]
        else -> null
    }

    /** Číslice na horní řadě písmen, když je číselná řada vypnutá. */
    private val topDigits: Map<String, String>
        get() = layoutRows(language)[0].zip("1234567890").associate { (k, v) -> k.toString() to v.toString() }

    /**
     * Varianty klávesy seřazené pro aktuální jazyk; první je ta nejpoužívanější.
     * Bez číselné řady: na horní řadě je číslice hned za diakritikou (česky) nebo první.
     */
    private fun variantsFor(label: String): List<String>? {
        val accents = langAccents[language]
        // v jiném jazyce než češtině se česká diakritika nahradí jeho vlastní
        val base = if (accents != null) {
            val own = accents[label].orEmpty()
            val sym = variants[label].orEmpty().filter { !it[0].isLetter() }
            (own + sym).ifEmpty { null }
        } else {
            variants[label]
        }
        val digit = digitFor(label)
        if (base == null && digit == null) return null
        val list = base.orEmpty()
        val letters = list.filter { it[0].isLetter() || it == "'" || it == "ʼ" }
        val others = list.filter { !(it[0].isLetter() || it == "'" || it == "ʼ") }
        return if (language != "en") {
            letters + listOfNotNull(digit) + others
        } else {
            // mimo češtinu nejdřív číslice a symboly, česká diakritika až za nimi
            listOfNotNull(digit) + others + letters
        }
    }

    private val longPress = Runnable {
        if (gliding) return@Runnable
        val p = pressed ?: return@Runnable
        val list = variantsFor(p.label) ?: return@Runnable
        val cw = POPUP_CELL_DP * d
        val total = cw * list.size
        // první (předvybraná) varianta stojí přímo nad prstem, další vpravo od ní
        var x = p.rect.centerX() - cw / 2
        x = x.coerceIn(gap * 2, (width - gap * 2 - total).coerceAtLeast(gap * 2))
        val top = p.rect.top - rowH - gap
        popup = list.mapIndexed { i, v ->
            Cell(Key.Char(v), v, 1f, RectF(x + i * cw, top, x + (i + 1) * cw, top + rowH))
        }
        popupRect = RectF(x - gap, top - gap / 2, x + total + gap, top + rowH + gap / 2)
        selected = popup?.first()
        popupAnchorX = lastTouchX
        invalidate()
    }

    /**
     * Výběr varianty posunem prstu: dokud se prst nepohne o půl políčka,
     * zůstává vybraná první varianta (malé chvění prstu nic nezmění).
     */
    private fun selectVariant(list: List<Cell>, x: Float) {
        val first = list.first().rect.centerX()
        val target = first + (x - popupAnchorX)
        selected = list.minByOrNull { Math.abs(it.rect.centerX() - target) }
    }

    private fun measuredHeightFor(): Int =
        if (collapsed()) stripH.toInt() + navInset
        else (stripH + rowH * rowCount() + gap * (rowCount() + 1)).toInt() + navInset

    /**
     * Výška systémové lišty (tlačítka zpět, plocha, přehled). Klávesnice se o ni prodlouží,
     * aby lišta nezakrývala spodní řadu; pod klávesami zůstane jen pozadí.
     */
    private var navInset = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootView.setOnApplyWindowInsetsListener { v, ins ->
            post { checkNavOverlap() }
            v.onApplyWindowInsets(ins)
        }
        post { checkNavOverlap() }
    }

    /** Výška systémové navigační lišty (tlačítka nebo proužek gest). */
    private fun navBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val h = rootWindowInsets
                ?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
            if (h > 0) return h
        }
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun screenHeight(): Int {
        val wm = context.getSystemService(android.view.WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.heightPixels
        }
    }

    /**
     * Sahá klávesnice pod navigační lištu? Pak se o překryv prodlouží, aby lišta
     * nezakrývala spodní řadu. Když systém klávesnici nad lištu posadí sám, nic se nemění.
     */
    private fun checkNavOverlap() {
        if (!isAttachedToWindow || height == 0) return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val keysBottom = loc[1] + height - navInset
        val need = (keysBottom - (screenHeight() - navBarHeight())).coerceIn(0, 200 * d.toInt())
        if (need != navInset) {
            navInset = need
            requestLayout()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), measuredHeightFor())
    }

    // ---- jedna ruka ----

    /** Vodorovný rozsah kláves; v režimu jedné ruky jsou klávesy užší a u kraje. */
    private var keysLeft = 0f
    private var keysRight = 0f
    private val oneHandSwap = RectF()
    private val oneHandExit = RectF()
    private var oneHandPressed = 0  // 1 = přesun na druhou stranu, 2 = zrušit

    /** Šířka kláves v % (nastavení), klávesy jsou uprostřed. */
    private var widthPercent = 100

    private fun oneHandOn() = oneHand == Prefs.ONE_HAND_LEFT || oneHand == Prefs.ONE_HAND_RIGHT

    /**
     * Řada [r] (počítáno shora) v rozsahu [left, right]. Kratší vrstvy (bez číselné
     * řady) se zarovnají dolů, aby spodní řada zůstala na místě.
     */
    private fun placeRow(row: List<Cell>, r: Int, rowsInLayer: Int, left: Float, right: Float) {
        val total = row.sumOf { it.weight.toDouble() }.toFloat()
        val unit = (right - left - gap * (row.size + 1)) / total
        var x = left + gap
        val shift = rowCount() - rowsInLayer
        val y = stripH + gap + (r + shift) * (rowH + gap)
        row.forEach { cell ->
            val cw = unit * cell.weight
            cell.rect.set(x, y, x + cw, y + rowH)
            x += cw + gap
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        layoutKeys(w, h)
        post { checkNavOverlap() }
    }

    private fun layoutKeys(w: Int, h: Int) {
        val full = w.toFloat()
        // zúžená klávesnice: klávesy uprostřed, okraje prázdné
        val areaW = full * widthPercent / 100f
        val areaL = (full - areaW) / 2f
        if (oneHandOn()) {
            val keysW = areaW * ONE_HAND_RATIO
            if (oneHand == Prefs.ONE_HAND_LEFT) {
                keysLeft = 0f; keysRight = keysW
            } else {
                keysLeft = full - keysW; keysRight = full
            }
            // volný pruh: nahoře přesun na druhou stranu, dole zrušení
            val panelL = if (oneHand == Prefs.ONE_HAND_LEFT) keysW else 0f
            val panelR = if (oneHand == Prefs.ONE_HAND_LEFT) full else full - keysW
            val top = stripH + gap
            val bottom = top + rowCount() * (rowH + gap)
            val mid = (top + bottom) / 2
            oneHandSwap.set(panelL, top, panelR, mid)
            oneHandExit.set(panelL, mid, panelR, bottom)
        } else {
            keysLeft = areaL; keysRight = areaL + areaW
            oneHandSwap.setEmpty()
            oneHandExit.setEmpty()
        }
        letterRows.forEachIndexed { r, row -> placeRow(row, r, letterRows.size, keysLeft, keysRight) }
        symbolRows.forEachIndexed { r, row -> placeRow(row, r, symbolRows.size, keysLeft, keysRight) }
        placeNumericRows(keysLeft, keysRight)
        // emoji a schránka mají plnou šířku
        placeRow(emojiBottom, rowCount() - 1, rowCount(), 0f, full)
        placeRow(clipNormalBottom, rowCount() - 1, rowCount(), 0f, full)
        placeRow(clipSelectBottom, rowCount() - 1, rowCount(), 0f, full)
        if (w > 0 && h > 0 && !collapsed()) onGeometry?.invoke(buildGeometry(w, h))
    }

    /** Šipky ⇆ a ikona roztažení ⤢ v prázdném pruhu vedle kláves. */
    private fun drawOneHandPanel(canvas: Canvas) {
        if (!oneHandOn()) return
        iconPaint.strokeWidth = 2.4f * d
        val s = minOf(oneHandSwap.width(), rowH) * 0.5f
        for ((i, r) in listOf(oneHandSwap, oneHandExit).withIndex()) {
            if (oneHandPressed == i + 1) {
                canvas.drawRoundRect(
                    r.left + gap, r.top + gap, r.right - gap, r.bottom - gap, keyRadius, keyRadius, keyDown
                )
            }
            val cx = r.centerX()
            val cy = r.centerY()
            iconPath.reset()
            if (i == 0) {
                // ⇆: šipka doprava nahoře, doleva dole
                val h = s * 0.25f
                iconPath.moveTo(cx - s / 2, cy - h); iconPath.lineTo(cx + s / 2, cy - h)
                iconPath.moveTo(cx + s / 2 - h, cy - 2 * h); iconPath.lineTo(cx + s / 2, cy - h)
                iconPath.lineTo(cx + s / 2 - h, cy)
                iconPath.moveTo(cx + s / 2, cy + h); iconPath.lineTo(cx - s / 2, cy + h)
                iconPath.moveTo(cx - s / 2 + h, cy); iconPath.lineTo(cx - s / 2, cy + h)
                iconPath.lineTo(cx - s / 2 + h, cy + 2 * h)
            } else {
                // ⤢: úhlopříčka s hroty na obou koncích
                val a = s * 0.4f
                val h = s * 0.25f
                iconPath.moveTo(cx - a, cy + a); iconPath.lineTo(cx + a, cy - a)
                iconPath.moveTo(cx + a - h, cy - a); iconPath.lineTo(cx + a, cy - a)
                iconPath.lineTo(cx + a, cy - a + h)
                iconPath.moveTo(cx - a + h, cy + a); iconPath.lineTo(cx - a, cy + a)
                iconPath.lineTo(cx - a, cy + a - h)
            }
            canvas.drawPath(iconPath, iconPaint)
        }
    }

    private fun oneHandTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                oneHandPressed = when {
                    oneHandSwap.contains(e.x, e.y) -> 1
                    oneHandExit.contains(e.x, e.y) -> 2
                    else -> 0
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val p = oneHandPressed
                oneHandPressed = 0
                invalidate()
                if (p == 1 && oneHandSwap.contains(e.x, e.y)) {
                    onOneHand?.invoke(
                        if (oneHand == Prefs.ONE_HAND_LEFT) Prefs.ONE_HAND_RIGHT else Prefs.ONE_HAND_LEFT
                    )
                } else if (p == 2 && oneHandExit.contains(e.x, e.y)) {
                    onOneHand?.invoke(Prefs.ONE_HAND_OFF)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                oneHandPressed = 0
                invalidate()
            }
        }
        return true
    }

    /**
     * Polohy kláves pro nativní knihovnu a gesta. Jako AOSP/HeliBoard se předávají všechny
     * klávesy s kódem ≥ mezera (písmena, číslice, interpunkce, mezerník), ne jen písmena;
     * vlastní engine si z nich bere jen písmena.
     */
    private fun buildGeometry(w: Int, h: Int): KeyGeometry {
        fun codeOf(c: Cell): Int = when {
            c.key == Key.Space -> ' '.code
            c.key is Key.Char && c.label.codePointCount(0, c.label.length) == 1 -> c.label.codePointAt(0)
            else -> -1
        }
        val keys = letterRows.flatten().filter { codeOf(it) >= ' '.code }
        // nejběžnější šířka/výška = písmenná klávesa
        val common = keys.first { it.key is Key.Char && it.label[0].isLetter() }
        return KeyGeometry(
            width = w,
            height = h,
            keyWidth = common.rect.width().toInt(),
            keyHeight = common.rect.height().toInt(),
            codes = IntArray(keys.size) { codeOf(keys[it]) },
            xs = IntArray(keys.size) { keys[it].rect.left.toInt() },
            ys = IntArray(keys.size) { keys[it].rect.top.toInt() },
            widths = IntArray(keys.size) { keys[it].rect.width().toInt() },
            heights = IntArray(keys.size) { keys[it].rect.height().toInt() }
        )
    }

    private fun shown(cell: Cell): String = when {
        cell.key is Key.Edit -> t(cell.label)
        shiftOn && cell.key is Key.Char && cell.label != FieldMode.DOMAIN -> cell.label.uppercase()
        else -> cell.label
    }

    private fun hintFor(cell: Cell): String? {
        if (cell.key !is Key.Char || !cell.label[0].isLetterOrDigit()) return null
        digitFor(cell.label)?.let { return it }
        return variantsFor(cell.label)?.firstOrNull { !it[0].isLetter() }
    }

    private fun drawKey(canvas: Canvas, cell: Cell, paint: Paint, hint: Boolean) {
        // bez rámečků: běžná klávesa se nepodbarví, jen stisknutá a zvýrazněná
        if (!(flatKeys && paint === keyPaint)) {
            canvas.drawRoundRect(cell.rect, keyRadius, keyRadius, paint)
        }
        if (cell.key == Key.Enter) {
            val c = iconPaint.color
            iconPaint.color = ON_ACCENT
            drawEnterIcon(canvas, cell.rect.centerX(), cell.rect.centerY(), rowH * 0.46f)
            iconPaint.color = c
            return
        }
        if (cell.key == Key.Shift) {
            // Shift: vypnutý obyčejný, zapnutý modrý s plnou šipkou, zamčený ⇪
            val p = if (shiftOn) onAccentText else text
            val sy = cell.rect.centerY() - (p.descent() + p.ascent()) / 2
            val label = when {
                capsLock -> "⇪"
                shiftOn -> "⬆"
                else -> "⇧"
            }
            canvas.drawText(label, cell.rect.centerX(), sy, p)
            if (capsLock) {
                // podtržení: velká písmena zamčená
                val w = cell.rect.width() * 0.22f
                canvas.drawRect(cell.rect.centerX() - w, cell.rect.bottom - 8 * d, cell.rect.centerX() + w, cell.rect.bottom - 6 * d, onAccentText)
            }
            return
        }
        if (cell.key == Key.Language) {
            drawGlobeIcon(canvas, cell.rect.centerX(), cell.rect.centerY(), rowH * 0.42f)
            return
        }
        if (cell.key == Key.Space && cell.label == SPACE_LABEL) {
            // mezerník: malá tlumená vlajka jazyka a šedý nápis Pekey
            val name = spaceName.ifBlank { BuildConfig.SPACE_LABEL }
            val word = if (incognito) "$INCOGNITO_MARK $name" else name
            val ww = spacePaint.measureText(word)
            val fw = if (spaceFlag.isEmpty()) 0f else flagPaint.measureText(spaceFlag) + 6 * d
            val x0 = cell.rect.centerX() - (fw + ww) / 2
            val sy = cell.rect.centerY() - (spacePaint.descent() + spacePaint.ascent()) / 2
            if (fw > 0) {
                val fy = cell.rect.centerY() - (flagPaint.descent() + flagPaint.ascent()) / 2
                canvas.drawText(spaceFlag, x0, fy, flagPaint)
            }
            canvas.drawText(word, x0 + fw, sy, spacePaint)
            return
        }
        if (cell.key is Key.ClipAction && cell.label.length > 2) {
            val label = if ((cell.key as Key.ClipAction).id == CLIP_SEL_DELETE) t("Smazat (%1\$d)", clipSelected.size) else t(cell.label)
            val cy = cell.rect.centerY() - (suggestText.descent() + suggestText.ascent()) / 2
            canvas.drawText(label, cell.rect.centerX(), cy, suggestText)
            return
        }
        if (cell.key is Key.Edit && cell.label.length > 2) {
            // slova v panelu úprav: menší písmo, dlouhé překlady se zmenší do klávesy
            val label = t(cell.label)
            val size = suggestText.textSize
            val room = cell.rect.width() - 8 * d
            val w = suggestText.measureText(label)
            if (w > room) suggestText.textSize = size * room / w
            val cy = cell.rect.centerY() - (suggestText.descent() + suggestText.ascent()) / 2
            canvas.drawText(label, cell.rect.centerX(), cy, suggestText)
            suggestText.textSize = size
            return
        }
        if (cell.key == Key.ClipClear) {
            val armed = android.os.SystemClock.uptimeMillis() < clearArmedUntil
            val label = if (armed) t("Opravdu smazat?") else t(cell.label)
            val cy = cell.rect.centerY() - (noticePaint.descent() + noticePaint.ascent()) / 2
            canvas.drawText(label, cell.rect.centerX(), cy, if (armed) suggestBold else suggestText)
            return
        }
        val ty = cell.rect.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(shown(cell), cell.rect.centerX(), ty, text)
        if (hint && showHints) {
            hintFor(cell)?.let {
                canvas.drawText(it, cell.rect.right - 6 * d, cell.rect.top + 15 * d, hintPaint)
            }
        }
    }

    // ---- ikony ----

    /** Šipka ↵: svislá čára dolů, vodorovně doleva a hrot. */
    private fun drawEnterIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.strokeWidth = 3.2f * d
        val baseY = cy + s * 0.18f
        val head = s * 0.3f
        iconPath.reset()
        iconPath.moveTo(cx + s * 0.5f, cy - s * 0.45f)
        iconPath.lineTo(cx + s * 0.5f, baseY)
        iconPath.lineTo(cx - s * 0.5f, baseY)
        iconPath.moveTo(cx - s * 0.5f + head, baseY - head)
        iconPath.lineTo(cx - s * 0.5f, baseY)
        iconPath.lineTo(cx - s * 0.5f + head, baseY + head)
        canvas.drawPath(iconPath, iconPaint)
    }

    /** Schránka: deska s klipem nahoře a dvěma řádky textu. */
    private fun drawClipIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.strokeWidth = 2f * d
        canvas.drawRoundRect(cx - s * 0.36f, cy - s * 0.4f, cx + s * 0.36f, cy + s * 0.5f, 3 * d, 3 * d, iconPaint)
        canvas.drawRoundRect(cx - s * 0.17f, cy - s * 0.52f, cx + s * 0.17f, cy - s * 0.3f, 2 * d, 2 * d, iconPaint)
        canvas.drawLine(cx - s * 0.18f, cy - s * 0.02f, cx + s * 0.18f, cy - s * 0.02f, iconPaint)
        canvas.drawLine(cx - s * 0.18f, cy + s * 0.2f, cx + s * 0.1f, cy + s * 0.2f, iconPaint)
    }

    /** Změna jazyka: zeměkoule. */
    private fun drawGlobeIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.strokeWidth = 1.8f * d
        val r = s / 2
        canvas.drawCircle(cx, cy, r, iconPaint)
        canvas.drawOval(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r, iconPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, iconPaint)
        canvas.drawLine(cx - r * 0.86f, cy - r * 0.5f, cx + r * 0.86f, cy - r * 0.5f, iconPaint)
        canvas.drawLine(cx - r * 0.86f, cy + r * 0.5f, cx + r * 0.86f, cy + r * 0.5f, iconPaint)
    }

    /** Mikrofon. */
    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.strokeWidth = 2f * d
        val w = s * 0.3f
        canvas.drawRoundRect(cx - w / 2, cy - s * 0.5f, cx + w / 2, cy + s * 0.08f, w / 2, w / 2, iconPaint)
        val r = RectF(cx - s * 0.32f, cy - s * 0.25f, cx + s * 0.32f, cy + s * 0.25f)
        canvas.drawArc(r, 0f, 180f, false, iconPaint)
        canvas.drawLine(cx, cy + s * 0.25f, cx, cy + s * 0.48f, iconPaint)
    }

    // ---- hlas: lišta během nahrávání a přepisu ----

    /** Stav hlasu (nastavuje služba); klepnutí na lištu při nahrávání ho ukončí. */
    var voiceState: Int = VoiceState.OFF
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private fun drawVoiceStrip(canvas: Canvas) {
        val cy = stripH / 2f
        val ty = cy - (suggestText.descent() + suggestText.ascent()) / 2
        if (voiceState == VoiceState.LISTENING) {
            canvas.drawCircle(18 * d, cy, 6 * d, recPaint)
            canvas.drawText(t("Poslouchám – klepni sem, až domluvíš"), width / 2f, ty, suggestText)
        } else {
            canvas.drawText(t("Přepisuji…"), width / 2f, ty, suggestText)
        }
    }

    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5484D.toInt() }

    /** Nastavení: ozubené kolo. */
    private fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.strokeWidth = 2f * d
        val r = s * 0.3f
        canvas.drawCircle(cx, cy, r, iconPaint)
        canvas.drawCircle(cx, cy, r * 0.4f, iconPaint)
        for (k in 0 until 8) {
            val a = Math.PI * k / 4
            val c = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            canvas.drawLine(cx + c * r, cy + sn * r, cx + c * r * 1.45f, cy + sn * r * 1.45f, iconPaint)
        }
    }

    /** Zpět: zahnutá šipka doleva. */
    private fun drawUndoIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.strokeWidth = 2.2f * d
        val lx = cx - s * 0.42f
        val ly = cy - s * 0.15f
        val head = s * 0.22f
        iconPath.reset()
        iconPath.moveTo(lx, ly)
        iconPath.lineTo(cx + s * 0.08f, ly)
        iconPath.cubicTo(cx + s * 0.5f, ly, cx + s * 0.5f, cy + s * 0.42f, cx + s * 0.08f, cy + s * 0.42f)
        iconPath.lineTo(cx - s * 0.2f, cy + s * 0.42f)
        iconPath.moveTo(lx + head, ly - head)
        iconPath.lineTo(lx, ly)
        iconPath.lineTo(lx + head, ly + head)
        canvas.drawPath(iconPath, iconPaint)
    }

    // ---- lišta návrhů ----
    /** Tlačítko emoji v pravé části lišty návrhů. */
    private fun emojiButtonW() = EMOJI_BUTTON_DP * d
    private fun emojiButtonLeft() = width - emojiButtonW()
    /** Návrhy končí vlevo od nástrojů, když jsou nástroje vidět. */
    private fun slotsLeft() = 0f
    private fun slotsRight() = if (toolsVisible) toolsLeft() else moreButtonLeft()

    /** Tlačítko ⌄ (další návrhy) vlevo od emoji, jen při psaní slova a když je co ukázat. */
    private fun moreVisible() = !toolsVisible && more.isNotEmpty()
    private fun moreButtonW() = if (moreVisible()) MORE_BUTTON_DP * d else 0f
    private fun moreButtonLeft() = emojiButtonLeft() - moreButtonW()

    /** Panel dalších návrhů je opravdu vidět (jen s ⌄ v liště). */
    private fun moreShown() = moreOpen && moreVisible() && !collapsed()

    /** Šipka ⌄ / ⌃ pro panel dalších návrhů. */
    private fun drawMoreButton(canvas: Canvas) {
        if (!moreVisible()) return
        val left = moreButtonLeft()
        val w = moreButtonW()
        if (stripPressed == SLOT_MORE) {
            canvas.drawRoundRect(left + gap, gap, left + w - gap, stripH - gap, 8 * d, 8 * d, keyDown)
        }
        canvas.drawRect(left - d / 2, stripH * 0.25f, left + d / 2, stripH * 0.75f, divider)
        val cx = left + w / 2
        val cy = stripH / 2
        val a = stripH * 0.12f
        val dir = if (moreOpen) -1 else 1
        iconPaint.strokeWidth = 2f * d
        iconPath.reset()
        iconPath.moveTo(cx - a * 1.4f, cy - a * 0.6f * dir)
        iconPath.lineTo(cx, cy + a * 0.6f * dir)
        iconPath.lineTo(cx + a * 1.4f, cy - a * 0.6f * dir)
        canvas.drawPath(iconPath, iconPaint)
    }

    /** Buňky panelu: 3 sloupce, řádky jako řady kláves; vejde se, co se vejde. */
    private fun moreCells(): List<RectF> {
        val top = stripH + gap
        val bottom = (height - navInset).toFloat()
        val rowsFit = ((bottom - top) / (rowH + gap)).toInt().coerceAtLeast(1)
        val n = minOf(more.size, rowsFit * MORE_COLS)
        val cw = (width - gap * (MORE_COLS + 1)) / MORE_COLS.toFloat()
        return List(n) { i ->
            val r = i / MORE_COLS
            val c = i % MORE_COLS
            val x = gap + c * (cw + gap)
            val y = top + r * (rowH + gap)
            RectF(x, y, x + cw, y + rowH)
        }
    }

    private fun drawMore(canvas: Canvas) {
        val ty = rowH / 2f - (suggestText.descent() + suggestText.ascent()) / 2
        moreCells().forEachIndexed { i, r ->
            canvas.drawRoundRect(r, 8 * d, 8 * d, if (i == morePressed) keyDown else keyPaint)
            val s = more[i]
            val n = suggestText.breakText(s, true, r.width() - 12 * d, null)
            val shownText = if (n < s.length) s.substring(0, maxOf(n - 1, 1)) + "…" else s
            canvas.drawText(shownText, r.centerX(), r.top + ty, suggestText)
        }
    }

    private fun moreTouch(e: MotionEvent): Boolean {
        fun at(x: Float, y: Float) = moreCells().indexOfFirst { it.contains(x, y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                morePressed = at(e.x, e.y)
                if (morePressed >= 0) feedback(Key.Char(""))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (morePressed >= 0 && at(e.x, e.y) != morePressed) {
                    morePressed = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val i = morePressed
                morePressed = -1
                if (i >= 0 && i < more.size && at(e.x, e.y) == i) {
                    val word = more[i]
                    moreOpen = false
                    onSuggestionPicked?.invoke(word)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                morePressed = -1
                invalidate()
            }
        }
        return true
    }
    private fun slotWidth() = (slotsRight() - slotsLeft()) / 3f

    private fun drawEmojiButton(canvas: Canvas) {
        val left = emojiButtonLeft()
        if (stripPressed == SLOT_EMOJI) {
            canvas.drawRoundRect(left + gap, gap, width - gap, stripH - gap, 8 * d, 8 * d, keyDown)
        }
        canvas.drawRect(left - d / 2, stripH * 0.25f, left + d / 2, stripH * 0.75f, divider)
        val ty = stripH / 2f - (emojiButtonPaint.descent() + emojiButtonPaint.ascent()) / 2
        canvas.drawText(EMOJI_ICON, left + emojiButtonW() / 2, ty, emojiButtonPaint)
    }

    /** Nástroje vpravo v liště vedle emoji, když se nepíše slovo: schránka a zpět. */
    private fun toolW() = EMOJI_BUTTON_DP * d
    private val toolSlots: List<Int>
        get() = if (voiceReady) listOf(SLOT_EDIT, SLOT_CLIP, SLOT_UNDO, SLOT_MIC, SLOT_SETTINGS)
        else listOf(SLOT_EDIT, SLOT_CLIP, SLOT_UNDO, SLOT_SETTINGS)

    /** Mikrofon v liště jen s nahraným hlasovým modelem. */
    var voiceReady: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    private fun toolsLeft() = emojiButtonLeft() - toolW() * toolSlots.size

    private fun drawTools(canvas: Canvas) {
        val w = toolW()
        val s = stripH * 0.42f
        val start = toolsLeft()
        canvas.drawRect(start - d / 2, stripH * 0.25f, start + d / 2, stripH * 0.75f, divider)
        for ((i, slot) in toolSlots.withIndex()) {
            val left = start + i * w
            if (stripPressed == slot) {
                canvas.drawRoundRect(left + gap, gap, left + w - gap, stripH - gap, 8 * d, 8 * d, keyDown)
            }
            when (slot) {
                SLOT_CLIP -> drawClipIcon(canvas, left + w / 2, stripH / 2, s)
                SLOT_UNDO -> drawUndoIcon(canvas, left + w / 2, stripH / 2, s)
                SLOT_MIC -> drawMicIcon(canvas, left + w / 2, stripH / 2, s)
                SLOT_EDIT -> drawEditIcon(canvas, left + w / 2, stripH / 2, s, edit)
                else -> drawGearIcon(canvas, left + w / 2, stripH / 2, s)
            }
        }
    }

    /** Ikona panelu úprav: textový kurzor ⌶ se šipkami do stran; otevřený panel modře. */
    private fun drawEditIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, on: Boolean) {
        val c = iconPaint.color
        if (on) iconPaint.color = keyOn.color
        iconPaint.strokeWidth = 2f * d
        val h = s * 0.5f
        val serif = s * 0.14f
        val a = s * 0.18f
        iconPath.reset()
        iconPath.moveTo(cx, cy - h); iconPath.lineTo(cx, cy + h)
        iconPath.moveTo(cx - serif, cy - h); iconPath.lineTo(cx + serif, cy - h)
        iconPath.moveTo(cx - serif, cy + h); iconPath.lineTo(cx + serif, cy + h)
        iconPath.moveTo(cx - s * 0.35f, cy - a); iconPath.lineTo(cx - s * 0.35f - a, cy); iconPath.lineTo(cx - s * 0.35f, cy + a)
        iconPath.moveTo(cx + s * 0.35f, cy - a); iconPath.lineTo(cx + s * 0.35f + a, cy); iconPath.lineTo(cx + s * 0.35f, cy + a)
        canvas.drawPath(iconPath, iconPaint)
        iconPaint.color = c
    }

    private fun drawSuggestions(canvas: Canvas) {
        drawEmojiButton(canvas)
        if (toolsVisible) drawTools(canvas)
        drawMoreButton(canvas)
        val list = suggestions
        if (list.isEmpty()) {
            if (notice.isNotEmpty()) {
                val ny = stripH / 2f - (noticePaint.descent() + noticePaint.ascent()) / 2
                val room = slotsRight()
                val n = noticePaint.breakText(notice, true, room - 16 * d, null)
                val shownNotice = if (n < notice.length) notice.substring(0, maxOf(n - 1, 1)) + "…" else notice
                canvas.drawText(shownNotice, room / 2f, ny, noticePaint)
            }
            return
        }
        val slotW = slotWidth()
        val ty = stripH / 2f - (suggestText.descent() + suggestText.ascent()) / 2
        list.forEachIndexed { i, s ->
            val left = slotsLeft() + i * slotW
            if (i == stripPressed) {
                canvas.drawRoundRect(
                    left + gap, gap, left + slotW - gap, stripH - gap, 8 * d, 8 * d, keyDown
                )
            }
            val paint = if (i == highlight) suggestBold else suggestText
            if (i == highlight && i != stripPressed) {
                // oprava, která se použije mezerníkem: podbarvená
                canvas.drawRoundRect(
                    left + gap, gap, left + slotW - gap, stripH - gap, 8 * d, 8 * d, keyPaint
                )
            }
            val n = paint.breakText(s, true, slotW - 16 * d, null)
            val shownText = if (n < s.length) s.substring(0, maxOf(n - 1, 1)) + "…" else s
            canvas.drawText(shownText, left + slotW / 2, ty, paint)
            if (i > 0) {
                canvas.drawRect(left - d / 2, stripH * 0.25f, left + d / 2, stripH * 0.75f, divider)
            }
        }
    }

    // ---- lišta hledání emoji: ✕, hledaný text, výsledky ----

    private fun searchCloseW() = EMOJI_BUTTON_DP * d
    private fun searchQueryW() = width * 0.3f
    private fun searchResultW() = stripH
    private fun searchResultsLeft() = searchCloseW() + searchQueryW()
    private fun searchResultsShown() =
        minOf(emojiResults.size, ((width - searchResultsLeft()) / searchResultW()).toInt())

    private fun drawSearchStrip(canvas: Canvas) {
        // ✕ zavře hledání
        iconPaint.strokeWidth = 2f * d
        val cx = searchCloseW() / 2
        val r = stripH * 0.16f
        canvas.drawLine(cx - r, stripH / 2 - r, cx + r, stripH / 2 + r, iconPaint)
        canvas.drawLine(cx - r, stripH / 2 + r, cx + r, stripH / 2 - r, iconPaint)
        // hledaný text
        val qLeft = searchCloseW()
        canvas.drawRoundRect(qLeft, gap * 1.5f, qLeft + searchQueryW() - gap, stripH - gap * 1.5f, 10 * d, 10 * d, keyPaint)
        val q = if (emojiQuery.isEmpty()) (if (searchClip) t("Hledat ve schránce") else t("Hledat emoji")) else emojiQuery
        val paint = if (emojiQuery.isEmpty()) noticePaint else suggestText
        val room = searchQueryW() - 16 * d
        val n = paint.breakText(q, false, room, null)
        val shownQ = if (n < q.length) "…" + q.substring(q.length - n + 1) else q
        val ty = stripH / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(shownQ, qLeft + (searchQueryW() - gap) / 2, ty, paint)
        if (searchClip) {
            // počet nalezených; ↵ ukáže výsledky ve schránce
            val ny = stripH / 2f - (noticePaint.descent() + noticePaint.ascent()) / 2
            val n2 = clipShown.size
            canvas.drawText(
                "$n2 · " + t("↵ zobrazit"),
                searchResultsLeft() + (width - searchResultsLeft()) / 2, ny, noticePaint
            )
            return
        }
        // výsledky
        val ey = stripH / 2f - (tabPaint.descent() + tabPaint.ascent()) / 2
        for (i in 0 until searchResultsShown()) {
            val left = searchResultsLeft() + i * searchResultW()
            if (stripPressed == i) {
                canvas.drawRoundRect(left + gap, gap, left + searchResultW() - gap, stripH - gap, 8 * d, 8 * d, keyDown)
            }
            canvas.drawText(emojiResults[i], left + searchResultW() / 2, ey, tabPaint)
        }
        if (emojiQuery.isNotEmpty() && emojiResults.isEmpty()) {
            val ny = stripH / 2f - (noticePaint.descent() + noticePaint.ascent()) / 2
            canvas.drawText(t("Nic"), searchResultsLeft() + (width - searchResultsLeft()) / 2, ny, noticePaint)
        }
    }

    private fun searchSlotAt(x: Float): Int = when {
        x < searchCloseW() -> SLOT_SEARCH_CLOSE
        searchClip -> -1
        x < searchResultsLeft() -> -1
        else -> ((x - searchResultsLeft()) / searchResultW()).toInt().let { if (it < searchResultsShown()) it else -1 }
    }

    private fun searchStripTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stripPressed = searchSlotAt(e.x); invalidate() }
            MotionEvent.ACTION_UP -> {
                val picked = stripPressed
                stripPressed = -1
                if (picked == searchSlotAt(e.x)) {
                    if (picked == SLOT_SEARCH_CLOSE) {
                        if (searchClip) endClipSearch(false) else openEmoji()
                    } else if (picked >= 0 && !searchClip) {
                        pickEmoji(emojiResults[picked])
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { stripPressed = -1; invalidate() }
        }
        return true
    }

    private var stripLongFired = false
    private val stripLongPress = Runnable {
        val i = stripPressed
        if (i in suggestions.indices) {
            stripLongFired = true
            stripPressed = -1
            invalidate()
            onSuggestionLongPress?.invoke(suggestions[i])
        }
    }

    private fun slotAt(x: Float): Int {
        if (x >= emojiButtonLeft()) return SLOT_EMOJI
        if (moreVisible() && x >= moreButtonLeft()) return SLOT_MORE
        if (toolsVisible && x >= toolsLeft()) {
            return toolSlots[((x - toolsLeft()) / toolW()).toInt().coerceIn(0, toolSlots.size - 1)]
        }
        if (suggestions.isEmpty()) return -1
        val i = ((x - slotsLeft()) / slotWidth()).toInt().coerceIn(0, 2)
        return if (i < suggestions.size) i else -1
    }

    private fun stripTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stripLongFired = false
                stripPressed = slotAt(e.x)
                if (stripPressed >= 0) postDelayed(stripLongPress, LONG_PRESS_MS)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(stripLongPress)
                val picked = stripPressed
                val slot = slotAt(e.x)
                stripPressed = -1
                invalidate()
                if (picked >= SLOT_EMOJI && slot == picked) {
                    when (picked) {
                        SLOT_EMOJI -> handleKey(Key.Emoji)
                        SLOT_CLIP -> handleKey(Key.Clipboard)
                        SLOT_UNDO -> handleKey(Key.Undo)
                        SLOT_SETTINGS -> handleKey(Key.Settings)
                        SLOT_MIC -> handleKey(Key.Voice)
                        SLOT_MORE -> {
                            moreOpen = !moreOpen
                            morePressed = -1
                            invalidate()
                        }
                        SLOT_EDIT -> {
                            edit = !edit
                            editSelect = false
                            symbols = false
                            invalidate()
                        }
                    }
                } else if (!stripLongFired && picked >= 0 && slot == picked && picked < suggestions.size) {
                    onSuggestionPicked?.invoke(suggestions[picked])
                }
                stripLongFired = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(stripLongPress)
                stripPressed = -1
                stripLongFired = false
                invalidate()
            }
        }
        return true
    }

    // ---- emoji geometry ----
    private fun tabH() = EMOJI_TAB_DP * d
    private fun gridTop() = gap + tabH()
    private fun gridBottom() = emojiBottom[0].rect.top - gap
    private fun cellSize() = width.toFloat() / cols
    private fun maxScroll(): Float {
        val rowsN = (emojis.size + cols - 1) / cols
        return (rowsN * cellSize() - (gridBottom() - gridTop())).coerceAtLeast(0f)
    }

    private fun tabCount() = emojiCats.size + 1
    private fun tabW() = width.toFloat() / tabCount()

    private fun drawEmojiTabs(canvas: Canvas) {
        val w = tabW()
        val h = tabH()
        val ty = gap + h / 2 - (tabPaint.descent() + tabPaint.ascent()) / 2
        for (t in 0 until tabCount()) {
            val cx = (t + 0.5f) * w
            if (t == emojiTab) {
                canvas.drawRoundRect(t * w + gap, gap, (t + 1) * w - gap, gap + h, 8 * d, 8 * d, keyPaint)
            }
            if (t == 0) {
                drawClockIcon(canvas, cx, gap + h / 2, h * 0.42f)
            } else {
                canvas.drawText(emojiCats[t - 1].icon, cx, ty, tabPaint)
            }
        }
    }

    private fun drawClockIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.strokeWidth = 1.8f * d
        canvas.drawCircle(cx, cy, s / 2, iconPaint)
        canvas.drawLine(cx, cy, cx, cy - s * 0.3f, iconPaint)
        canvas.drawLine(cx, cy, cx + s * 0.22f, cy + s * 0.12f, iconPaint)
    }

    private fun drawEmoji(canvas: Canvas) {
        drawEmojiTabs(canvas)
        val cs = cellSize()
        if (emojis.isEmpty()) {
            val ty = (gridTop() + gridBottom()) / 2 - (noticePaint.descent() + noticePaint.ascent()) / 2
            canvas.drawText(t("Zatím žádná použitá emoji"), width / 2f, ty, noticePaint)
        }
        canvas.save()
        canvas.clipRect(0f, gridTop(), width.toFloat(), gridBottom())
        emojis.forEachIndexed { i, e ->
            val cx = (i % cols + 0.5f) * cs
            val cy = gridTop() + (i / cols) * cs - emojiScroll + cs / 2
            if (cy > gridTop() - cs && cy < gridBottom() + cs) {
                canvas.drawText(e, cx, cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2, emojiPaint)
            }
        }
        canvas.restore()
        emojiBottom.forEach { cell ->
            drawKey(canvas, cell, if (cell === pressed) keyDown else keyPaint, false)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bg)
        if (emoji) {
            drawEmoji(canvas)
            return
        }
        if (clip) {
            drawClipboard(canvas)
            return
        }
        when {
            voiceState != VoiceState.OFF -> drawVoiceStrip(canvas)
            emojiSearch -> drawSearchStrip(canvas)
            else -> drawSuggestions(canvas)
        }
        // fyzická klávesnice: jen lišta
        if (collapsed()) return
        if (moreShown()) {
            drawMore(canvas)
            return
        }
        drawOneHandPanel(canvas)
        rows.flatten().forEach { cell ->
            val paint = when {
                cell === pressed -> keyDown
                cell.key == Key.Shift && shiftOn -> keyOnSoft
                cell.key == Key.Edit(EditKey.SELECT) && editSelect -> keyOnSoft
                cell.key == Key.Edit(EditKey.INCOGNITO) && incognito -> keyOnSoft
                cell.key == Key.Enter -> keyOnSoft
                else -> keyPaint
            }
            drawKey(canvas, cell, paint, true)
        }
        if (gliding && glideCount > 1) {
            drawTrail(canvas, glideX, glideY, glideT, glideCount, glideStart, false)
        } else if (fadeCount > 1) {
            drawTrail(canvas, fadeX, fadeY, fadeT, fadeCount, fadeBase, true)
        }
        popup?.let { list ->
            canvas.drawRoundRect(popupRect, 12 * d, 12 * d, popupBg)
            list.forEach { cell ->
                drawKey(canvas, cell, if (cell === selected) keyDown else keyPaint, false)
            }
        }
        drawPreview(canvas)
    }

    /** Bublina se stisknutým znakem nad klávesou (jen znaky, ne při gestu a variantách). */
    private fun drawPreview(canvas: Canvas) {
        if (!showPreview || popup != null || gliding) return
        val p = pressed ?: return
        if (p.key !is Key.Char) return
        val w = maxOf(p.rect.width() * 1.25f, 44 * d)
        val h = rowH * 1.2f
        val bottom = p.rect.top - gap / 2
        var left = p.rect.centerX() - w / 2
        left = left.coerceIn(gap, width - gap - w)
        val r = RectF(left, bottom - h, left + w, bottom)
        canvas.drawRoundRect(r, keyRadius.coerceAtLeast(6 * d), keyRadius.coerceAtLeast(6 * d), popupBg)
        val ty = r.centerY() - (previewText.descent() + previewText.ascent()) / 2
        canvas.drawText(shown(p), r.centerX(), ty, previewText)
    }

    /** Klávesnice se zavřela: při dalším otevření začíná na písmenech (ne emoji, symboly, schránka…). */
    fun resetToLetters() {
        endEmojiSearch()
        symbols = false
        emoji = false
        clip = false
        edit = false
        editSelect = false
        moreOpen = false
        popup = null
        invalidate()
    }

    /** Nové pole: hledání emoji se zavře. */
    fun endEmojiSearch() {
        clipSelect = false
        clipSelected.clear()
        if (!emojiSearch) return
        searchClip = false
        emojiSearch = false
        emojiQuery = ""
        emojiResults = emptyList()
        invalidate()
    }

    private fun openEmoji() {
        recentEmoji = EmojiData.recent(context)
        emojiTab = 0
        emoji = true
        clip = false
        emojiSearch = false
        emojiScroll = 0f
        invalidate()
    }

    private fun pickEmoji(e: String) {
        onKey(Key.Char(e))
        if (!incognito) EmojiData.addRecent(context, e)
    }

    /** V hledání emoji píšou klávesy do hledaného textu. */
    private fun searchKey(key: Key) {
        when (key) {
            is Key.Char -> emojiQuery += key.label.lowercase()
            Key.Space -> if (emojiQuery.isNotEmpty() && !emojiQuery.endsWith(" ")) emojiQuery += " "
            Key.Backspace -> if (emojiQuery.isNotEmpty()) {
                emojiQuery = emojiQuery.substring(0, emojiQuery.offsetByCodePoints(emojiQuery.length, -1))
            }
            Key.Enter, Key.Emoji, Key.Letters -> {
                if (searchClip) endClipSearch(true) else openEmoji()
                return
            }
            else -> return
        }
        if (searchClip) {
            clipQuery = emojiQuery
        } else {
            emojiResults = EmojiData.search(emojiQuery, emojiCats)
        }
        invalidate()
    }

    private fun handleKey(key: Key) {
        if (emojiSearch) {
            searchKey(key)
            return
        }
        when (key) {
            Key.Symbols -> { symbols = true; emoji = false; clip = false; edit = false; invalidate() }
            Key.Letters -> { symbols = false; emoji = false; clip = false; edit = false; invalidate() }
            is Key.Edit -> when (key.id) {
                EditKey.SELECT -> { editSelect = !editSelect; invalidate() }
                EditKey.INCOGNITO -> onIncognito?.invoke()
                EditKey.COPY, EditKey.CUT -> { onKey(key); editSelect = false; invalidate() }
                else -> onKey(Key.Edit(key.id, editSelect))
            }
            Key.Emoji -> openEmoji()
            Key.EmojiSearch -> {
                searchClip = false
                emojiSearch = true
                emojiQuery = ""
                emojiResults = emptyList()
                emoji = false
                symbols = false
                invalidate()
            }
            is Key.ClipAction -> clipAction(key.id)
            Key.ClipClear -> {
                val now = android.os.SystemClock.uptimeMillis()
                if (now < clearArmedUntil) {
                    clearArmedUntil = 0L
                    onClipClearAll?.invoke()
                } else {
                    clearArmedUntil = now + CLEAR_CONFIRM_MS
                    postDelayed({ invalidate() }, CLEAR_CONFIRM_MS)
                }
                invalidate()
            }
            Key.Clipboard -> {
                clip = true
                emoji = false
                clipScroll = 0f
                onClipOpen?.invoke()
                invalidate()
            }
            else -> onKey(key)
        }
    }

    private fun emojiTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; lastY = e.y; moved = false
                pressed = emojiBottom.firstOrNull { it.rect.contains(e.x, e.y) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed == null) {
                    if (Math.abs(e.y - downY) > slop) moved = true
                    if (moved) {
                        emojiScroll = (emojiScroll + (lastY - e.y)).coerceIn(0f, maxScroll())
                        invalidate()
                    }
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                if (p != null) {
                    if (p.rect.contains(e.x, e.y)) handleKey(p.key)
                } else if (!moved && e.y < gridTop()) {
                    val t = (e.x / tabW()).toInt().coerceIn(0, tabCount() - 1)
                    if (t != emojiTab) {
                        emojiTab = t
                        if (t == 0) recentEmoji = EmojiData.recent(context)
                        emojiScroll = 0f
                    }
                } else if (!moved && e.y >= gridTop() && e.y <= gridBottom()) {
                    val cs = cellSize()
                    val col = (e.x / cs).toInt()
                    val row = ((e.y - gridTop() + emojiScroll) / cs).toInt()
                    val idx = row * cols + col
                    if (col in 0 until cols && idx in emojis.indices) {
                        pickEmoji(emojis[idx])
                    }
                }
                pressed = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }

    // ---- schránka ----

    private var clipScroll = 0f
    private var clipLongFired = false
    private var clipPressed = -1
    private fun clipRowH() = CLIP_ROW_DP * d

    /** Pruh s hledaným textem nad seznamem (klepnutím se hledání zruší). */
    private fun clipHeaderH() = if (clipQuery.isBlank()) 0f else CLIP_HEADER_DP * d
    private fun clipListTop() = gridTop() + clipHeaderH()

    private fun clipMaxScroll(): Float =
        (clipShown.size * clipRowH() - (gridBottom() - clipListTop())).coerceAtLeast(0f)

    private fun clipIndexAt(y: Float): Int {
        if (y < clipListTop() || y > gridBottom()) return -1
        val i = ((y - clipListTop() + clipScroll) / clipRowH()).toInt()
        return if (i in clipShown.indices) i else -1
    }

    private val clipLongPress = Runnable {
        val list = clipShown
        val i = clipPressed
        if (i in list.indices && !moved) {
            // podržení: výběr více položek
            clipLongFired = true
            clipPressed = -1
            clipSelect = true
            clipSelected.add(list[i])
            invalidate()
        }
    }

    private fun drawClipboard(canvas: Canvas) {
        val top = gridTop()
        val bottom = gridBottom()
        val list = clipShown
        if (clipQuery.isNotBlank()) {
            val hh = clipHeaderH()
            val r = RectF(gap, top, width - gap, top + hh - gap / 2)
            canvas.drawRoundRect(r, 8 * d, 8 * d, keyPaint)
            val label = "🔍 $clipQuery  ·  ${list.size}  ·  ✕ " + t("zrušit")
            val ty = r.centerY() - (noticePaint.descent() + noticePaint.ascent()) / 2
            canvas.drawText(label, r.centerX(), ty, noticePaint)
        }
        val listTop = clipListTop()
        if (list.isEmpty()) {
            val ny = (listTop + bottom) / 2 - (noticePaint.descent() + noticePaint.ascent()) / 2
            if (clipQuery.isNotBlank()) {
                canvas.drawText(t("Nic nenalezeno"), width / 2f, ny, noticePaint)
            } else {
                canvas.drawText(t("Schránka je prázdná – zkopírovaný text se objeví tady"), width / 2f, ny, noticePaint)
                val hy = ny + 22 * d
                canvas.drawText(t("Klepnutím vložíš, špendlíkem připneš, podržením vybereš"), width / 2f, hy, noticePaint)
            }
        } else {
            val rh = clipRowH()
            canvas.save()
            canvas.clipRect(0f, listTop, width.toFloat(), bottom)
            list.forEachIndexed { i, item ->
                val y = listTop + i * rh - clipScroll
                if (y + rh < listTop || y > bottom) return@forEachIndexed
                val r = RectF(gap, y + gap / 2, width - gap, y + rh - gap / 2)
                val chosen = clipSelect && item in clipSelected
                canvas.drawRoundRect(r, 8 * d, 8 * d, if (i == clipPressed || chosen) keyDown else keyPaint)
                if (chosen) canvas.drawRoundRect(r.left, r.top, r.left + 4 * d, r.bottom, 2 * d, 2 * d, keyOn)
                val line = item.replace('\n', ' ').replace('\t', ' ').trim()
                drawPinIcon(canvas, r.right - clipPinW() / 2, r.centerY(), rh * 0.36f, item in pinnedClips)
                val room = r.width() - 24 * d - clipPinW()
                val n = clipText.breakText(line, true, room, null)
                val shownLine = if (n < line.length) line.substring(0, maxOf(n - 1, 1)) + "…" else line
                val ty = r.centerY() - (clipText.descent() + clipText.ascent()) / 2
                canvas.drawText(shownLine, r.left + 12 * d, ty, clipText)
            }
            canvas.restore()
        }
        clipBottom.forEach { cell ->
            drawKey(canvas, cell, if (cell === pressed) keyDown else keyPaint, false)
        }
    }

    /** Akce schránky (hledání, výběr). */
    private fun clipAction(id: Int) {
        when (id) {
            CLIP_SEARCH -> {
                searchClip = true
                emojiSearch = true
                emojiQuery = clipQuery
                emojiResults = emptyList()
                clip = false
                emoji = false
                symbols = false
            }
            CLIP_SEL_CANCEL -> {
                clipSelect = false
                clipSelected.clear()
            }
            CLIP_SEL_PIN -> {
                // vybrané nepřipnuté připne; když jsou všechny připnuté, odepne je
                val all = clipSelected.all { it in pinnedClips }
                clipSelected.filter { (it in pinnedClips) == all }.forEach { onClipPin?.invoke(it) }
                clipSelect = false
                clipSelected.clear()
            }
            CLIP_SEL_DELETE -> {
                clipSelected.toList().forEach { onClipRemove?.invoke(it) }
                clipSelect = false
                clipSelected.clear()
            }
        }
        invalidate()
    }

    /** Návrat z hledání do schránky (s filtrem). */
    private fun endClipSearch(keepQuery: Boolean) {
        clipQuery = if (keepQuery) emojiQuery.trim() else ""
        searchClip = false
        emojiSearch = false
        emojiQuery = ""
        clip = true
        clipScroll = 0f
        invalidate()
    }

    /** Šířka zóny se špendlíkem vpravo v řádku schránky. */
    private fun clipPinW() = CLIP_PIN_DP * d

    private fun drawPinIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, on: Boolean) {
        iconPaint.strokeWidth = 1.8f * d
        val headY = cy - s * 0.2f
        if (on) {
            canvas.drawCircle(cx, headY, s * 0.28f, keyOn)
        }
        canvas.drawCircle(cx, headY, s * 0.28f, iconPaint)
        canvas.drawLine(cx, headY + s * 0.28f, cx, cy + s * 0.5f, iconPaint)
    }

    private fun clipTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; lastY = e.y; moved = false
                clipLongFired = false
                pressed = clipBottom.firstOrNull { it.rect.contains(e.x, e.y) }
                clipPressed = if (pressed == null) clipIndexAt(e.y) else -1
                if (clipPressed >= 0) postDelayed(clipLongPress, CLIP_LONG_PRESS_MS)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed == null) {
                    if (Math.abs(e.y - downY) > slop) {
                        moved = true
                        removeCallbacks(clipLongPress)
                        clipPressed = -1
                    }
                    if (moved) {
                        clipScroll = (clipScroll + (lastY - e.y)).coerceIn(0f, clipMaxScroll())
                        invalidate()
                    }
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(clipLongPress)
                val p = pressed
                if (p != null) {
                    if (p.rect.contains(e.x, e.y)) handleKey(p.key)
                } else if (!moved && clipQuery.isNotBlank() && e.y >= gridTop() && e.y < clipListTop()) {
                    clipQuery = ""
                    clipScroll = 0f
                } else if (!moved && !clipLongFired && clipPressed >= 0 && clipPressed in clipShown.indices && clipSelect) {
                    val item = clipShown[clipPressed]
                    if (!clipSelected.remove(item)) clipSelected.add(item)
                    if (clipSelected.isEmpty()) clipSelect = false
                } else if (!moved && !clipLongFired && clipPressed >= 0 && clipPressed in clipShown.indices &&
                    e.x > width - gap - clipPinW()
                ) {
                    onClipPin?.invoke(clipShown[clipPressed])
                } else if (!moved && !clipLongFired && clipPressed >= 0 && clipPressed in clipShown.indices) {
                    val item = clipShown[clipPressed]
                    // po vložení zpátky na písmena
                    clip = false
                    clipQuery = ""
                    onClipPicked?.invoke(item)
                }
                pressed = null
                clipPressed = -1
                clipLongFired = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(clipLongPress)
                pressed = null
                clipPressed = -1
                invalidate()
            }
        }
        return true
    }

    // ---- backspace a mezerník ----

    private fun startSpecial(cell: Cell?, e: MotionEvent) {
        resetSpecial()
        if (emojiSearch) return
        when (cell?.key) {
            Key.Backspace -> {
                postDelayed(deleteRepeat, DELETE_HOLD_MS)
                deleteArmed = true
                deleteStartX = e.x
            }
            Key.Space -> {
                spaceArmed = true
                spaceStartX = e.x
                spaceLastX = e.x
            }
            else -> Unit
        }
    }

    private var spaceLastTime = 0L
    private var spacePrevX = 0f

    /**
     * Tah po mezerníku: pomalu po znacích, rychlým tahem po celých slovech.
     */
    private fun trackCursor(e: MotionEvent) {
        if (!cursorMode) {
            if (Math.abs(e.x - spaceStartX) < CURSOR_START_DP * d) return
            cursorMode = true
            spaceLastX = spaceStartX
            spacePrevX = e.x
            spaceLastTime = e.eventTime
        }
        val dt = (e.eventTime - spaceLastTime).coerceAtLeast(1L)
        val speed = Math.abs(e.x - spacePrevX) / d / dt  // dp za ms
        spacePrevX = e.x
        spaceLastTime = e.eventTime
        val byWord = speed > WORD_SPEED_DP_PER_MS
        // vyšší rychlost = kratší kus tahu na jeden krok (výchozí 85 = dřívější chování)
        val step = (if (byWord) CURSOR_WORD_STEP_DP else CURSOR_STEP_DP) * d *
            Prefs.CURSOR_SPEED_DEFAULT / cursorSpeed.toFloat()
        while (e.x - spaceLastX >= step) {
            onKey(if (byWord) Key.WordRight else Key.CursorRight)
            spaceLastX += step
        }
        while (spaceLastX - e.x >= step) {
            onKey(if (byWord) Key.WordLeft else Key.CursorLeft)
            spaceLastX -= step
        }
    }

    /**
     * Tah z ⌫ doleva: čím dál prst, tím víc slov se označí; zpátky doprava se označení zmenší.
     */
    private fun trackDeleteSwipe(e: MotionEvent) {
        if (deleteRepeating) return
        val dist = deleteStartX - e.x
        if (!deleteSwipe) {
            if (dist < CURSOR_START_DP * d) return
            deleteSwipe = true
            removeCallbacks(deleteRepeat)
        }
        val words = if (dist < CURSOR_START_DP * d) 0
        else ((dist - CURSOR_START_DP * d) / (CURSOR_WORD_STEP_DP * d)).toInt() + 1
        if (words != deleteWords) {
            deleteWords = words
            onKey(Key.DeleteSwipe(words))
        }
    }

    private fun resetSpecial() {
        removeCallbacks(deleteRepeat)
        deleteRepeating = false
        spaceArmed = false
        cursorMode = false
        deleteArmed = false
        deleteSwipe = false
        deleteWords = 0
    }

    // ---- gesta ----

    /** Gesto může začít jen na písmenu v písmenné vrstvě. */
    private fun startGlide(cell: Cell?, e: MotionEvent) {
        resetGlide()
        if (!gestureEnabled || symbols || emoji || emojiSearch || onGesture == null) return
        if (cell == null || cell.key !is Key.Char || !cell.label[0].isLetter()) return
        glideArmed = true
        glideStart = e.eventTime
        addGlidePoint(e.x, e.y, e.eventTime)
    }

    private fun trackGlide(e: MotionEvent) {
        for (h in 0 until e.historySize) {
            addGlidePoint(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h))
        }
        addGlidePoint(e.x, e.y, e.eventTime)
        if (!gliding) {
            val start = pressed?.rect
            val dx = e.x - glideX[0]
            val dy = e.y - glideY[0]
            val far = Math.hypot(dx.toDouble(), dy.toDouble()) > glideStartDistance()
            if (far && (start == null || !start.contains(e.x, e.y))) {
                gliding = true
                removeCallbacks(longPress)
                pressed = null
            }
        }
        if (gliding) invalidate()
    }

    private fun glideStartDistance(): Float {
        val w = letterRows[1].firstOrNull()?.rect?.width() ?: (32 * d)
        return w * 0.6f
    }

    private fun addGlidePoint(x: Float, y: Float, time: Long) {
        if (glideCount == glideX.size) {
            if (glideCount >= MAX_GLIDE_POINTS) return
            glideX = glideX.copyOf(glideCount * 2)
            glideY = glideY.copyOf(glideCount * 2)
            glideT = glideT.copyOf(glideCount * 2)
        }
        glideX[glideCount] = x
        glideY[glideCount] = y
        glideT[glideCount] = (time - glideStart).toInt().coerceAtLeast(0)
        glideCount++
    }

    // ---- stopa gesta: u prstu silná, ke konci se ztenčuje a mizí ----

    private var fadeX = FloatArray(0)
    private var fadeY = FloatArray(0)
    private var fadeT = IntArray(0)
    private var fadeCount = 0
    /** Začátek dobledávajícího gesta (časy bodů jsou od něj). */
    private var fadeBase = 0L

    /** Stopa gesta jako v Blacklinu (silná u prstu, ke konci užší, postupně bledne). */
    private val gestureTrail = GestureTrail(d)

    private fun drawTrail(
        canvas: Canvas, xs: FloatArray, ys: FloatArray, ts: IntArray, n: Int, base: Long, fading: Boolean
    ) {
        gestureTrail.color = trailColor
        val since = (android.os.SystemClock.uptimeMillis() - base).toInt()
        val alive = gestureTrail.draw(canvas, xs, ys, ts, n, since)
        if (alive) {
            postInvalidateOnAnimation()
        } else if (fading) {
            fadeCount = 0
        }
    }

    /** Konec gesta: stopa se přesune do dobledávání. */
    private fun startTrailFade() {
        if (glideCount < 2) return
        fadeX = glideX.copyOf(glideCount)
        fadeY = glideY.copyOf(glideCount)
        fadeT = glideT.copyOf(glideCount)
        fadeCount = glideCount
        fadeBase = glideStart
        postInvalidateOnAnimation()
    }

    private fun resetGlide() {
        glideArmed = false
        gliding = false
        glideCount = 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (emoji) return emojiTouch(e)
        if (voiceState != VoiceState.OFF && e.y < stripH) {
            if (e.actionMasked == MotionEvent.ACTION_UP && voiceState == VoiceState.LISTENING) onKey(Key.Voice)
            return true
        }
        if (emojiSearch && (stripPressed != -1 || (e.actionMasked == MotionEvent.ACTION_DOWN && e.y < stripH))) {
            return searchStripTouch(e)
        }
        if (clip) return clipTouch(e)
        if (stripPressed >= 0 || stripLongFired ||
            (e.actionMasked == MotionEvent.ACTION_DOWN && e.y < stripH &&
                (suggestions.isNotEmpty() || e.x >= emojiButtonLeft() || (toolsVisible && e.x >= toolsLeft())))
        ) {
            return stripTouch(e)
        }
        // fyzická klávesnice: pod lištou nic není
        if (collapsed()) return true
        // panel dalších návrhů místo kláves
        if (moreShown() || morePressed >= 0) return moreTouch(e)
        if (oneHandPressed != 0 ||
            (e.actionMasked == MotionEvent.ACTION_DOWN &&
                (oneHandSwap.contains(e.x, e.y) || oneHandExit.contains(e.x, e.y)))
        ) {
            return oneHandTouch(e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(longPress)
                pressed = rows.flatten().firstOrNull { it.rect.contains(e.x, e.y) }
                val p = pressed
                lastTouchX = e.x
                pendingTap = null
                tapDownX = e.x
                tapDownY = e.y
                if (p != null && p.key is Key.Char && variantsFor(p.label) != null) {
                    postDelayed(longPress, LONG_PRESS_MS)
                }
                editRepeating = false
                removeCallbacks(editRepeat)
                if (p != null && p.key is Key.Edit && p.label.length == 1) postDelayed(editRepeat, LONG_PRESS_MS)
                if (p != null) feedback(p.key)
                startGlide(p, e)
                startSpecial(p, e)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = e.x
                val list = popup
                if (list != null) {
                    selectVariant(list, e.x)
                    invalidate()
                } else if (glideArmed) {
                    trackGlide(e)
                } else if (spaceArmed) {
                    trackCursor(e)
                } else if (deleteArmed) {
                    trackDeleteSwipe(e)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                removeCallbacks(editRepeat)
                val list = popup
                val consumed = deleteRepeating || cursorMode || deleteSwipe || editRepeating
                editRepeating = false
                if (deleteSwipe) onKey(Key.DeleteSwipeEnd)
                if (gliding) {
                    addGlidePoint(e.x, e.y, e.eventTime)
                    startTrailFade()
                    if (glideCount >= 2) onGesture?.invoke(glideX, glideY, glideT, glideCount)
                } else if (consumed) {
                    // podržený backspace nebo posun kurzoru: klávesa se už nepíše
                } else if (list != null) {
                    selected?.let { handleKey(it.key) }
                } else {
                    pressed?.let {
                        // písmeno ťuknuté na písmenné vrstvě: služba pošle polohu do návrhů
                        if (it.key is Key.Char && rows === letterRows) {
                            pendingTap = tapDownX.toInt() to tapDownY.toInt()
                        }
                        handleKey(it.key)
                    }
                }
                popup = null; selected = null; pressed = null
                resetGlide()
                resetSpecial()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                removeCallbacks(editRepeat)
                editRepeating = false
                if (deleteSwipe) onKey(Key.DeleteSwipe(0))
                popup = null; selected = null; pressed = null
                resetGlide()
                resetSpecial()
                invalidate()
            }
        }
        return true
    }

    private companion object {
        /** Česká horní řada (jako na české klávesnici, místo + a = je ů). */
        const val CZECH_ROW = "ěščřžýáíéů"

        /** Značka anonymního režimu na mezerníku. */
        const val INCOGNITO_MARK = "🕶"

        const val MAX_GLIDE_POINTS = 4096

        /** Výchozí výška řady kláves (100 %). */
        const val BASE_ROW_DP = 46f

        /** Dlouhý stisk klávesy nebo návrhu. */
        const val LONG_PRESS_MS = 350L

        /** Šířka tlačítka emoji vpravo v liště návrhů. */
        const val EMOJI_BUTTON_DP = 40f
        const val EMOJI_ICON = "\uD83D\uDE42"

        /** stripPressed: tlačítka v liště (návrhy mají 0–2). */
        const val SLOT_EMOJI = 3
        const val SLOT_CLIP = 4
        const val SLOT_UNDO = 5
        const val SLOT_SEARCH_CLOSE = 6

        /** Akce schránky. */
        const val CLIP_SEARCH = 1
        const val CLIP_SEL_CANCEL = 2
        const val CLIP_SEL_PIN = 3
        const val CLIP_SEL_DELETE = 4
        const val CLIP_HEADER_DP = 30f

        /** Bílá na modrých klávesách. */
        const val ON_ACCENT = 0xFFFFFFFF.toInt()

        /** Stopa gesta: jak dlouho bod žije a šířka u prstu. */
        const val CLEAR_CONFIRM_MS = 3000L
        const val SLOT_SETTINGS = 7
        const val SLOT_MIC = 8
        const val SLOT_EDIT = 9
        const val SLOT_MORE = 10

        /** Šířka tlačítka ⌄ v liště a počet sloupců panelu dalších návrhů. */
        const val MORE_BUTTON_DP = 34f
        const val MORE_COLS = 3

        /** Opakování podržené šipky v panelu úprav. */
        const val EDIT_REPEAT_MS = 70L


        /** Označení mezerníku v rozložení (kreslí se jako vlajka + Pekey). */
        const val SPACE_LABEL = "mezerník"

        /** Výška lišty záložek emoji. */
        const val EMOJI_TAB_DP = 40f

        /** Výška řádku v panelu schránky. */
        const val CLIP_ROW_DP = 52f
        const val CLIP_LONG_PRESS_MS = 500L
        const val CLIP_PIN_DP = 44f

        /** Šířka jednoho políčka v liště variant. */
        const val POPUP_CELL_DP = 48f

        /** Po jak dlouhém podržení ⌫ se začne mazat po slovech. */
        const val DELETE_HOLD_MS = 400L

        /** Rychlost mazání dalších slov. */
        const val DELETE_REPEAT_MS = 220L

        /** Jak daleko je třeba po mezerníku táhnout, než se začne hýbat kurzor. */
        const val CURSOR_START_DP = 16f

        /** O kolik se posune prst na jeden znak. */
        const val CURSOR_STEP_DP = 12f

        /** Rychlý tah (dp/ms) posouvá kurzor po slovech, jedno slovo na tento kus tahu. */
        const val WORD_SPEED_DP_PER_MS = 0.9f
        const val CURSOR_WORD_STEP_DP = 28f

        /** Šířka kláves v režimu jedné ruky (podíl šířky obrazovky). */
        const val ONE_HAND_RATIO = 0.82f
    }
}

/** Režimy číselné klávesnice. */
object NumMode {
    const val OFF = 0
    const val NUMBER = 1
    const val PHONE = 2
}

/** Stavy hlasového zadávání v liště. */
object VoiceState {
    const val OFF = 0
    const val LISTENING = 1
    const val WORKING = 2
}

/** Akce panelu úprav textu (klávesa Key.Edit). */
object EditKey {
    const val SELECT_ALL = 1
    const val COPY = 2
    const val CUT = 3
    const val PASTE = 4
    const val HOME = 5
    const val END = 6
    const val UP = 7
    const val DOWN = 8
    const val LEFT = 9
    const val RIGHT = 10
    const val SELECT = 11
    const val DOC_START = 12
    const val DOC_END = 13
    /** Anonymní režim: nic se neučí ani neukládá do historie emoji. */
    const val INCOGNITO = 14
}

/** Druh textového pole: běžný text, e-mail, webová adresa. */
object FieldMode {
    const val TEXT = 0
    const val EMAIL = 1
    const val URL = 2

    /** Klávesa s doménou v e-mailu a adrese (podržením další domény). */
    const val DOMAIN = ".cz"
}
