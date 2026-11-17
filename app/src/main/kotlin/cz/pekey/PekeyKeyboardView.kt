package cz.pekey

import android.content.Context
import android.graphics.Canvas
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
                invalidate()
            }
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

    /** Podržený návrh (např. zapomenout naučené slovo). */
    var onSuggestionLongPress: ((String) -> Unit)? = null

    /**
     * Jazyk ("cs", "en"): určuje pořadí variant dlouhého stisku.
     * Česky je první diakritika (á), anglicky symbol (@).
     */
    var language: String = "cs"

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
    private var bottomLeft = ","
    private var bottomRight = "."
    private var oneHand = Prefs.ONE_HAND_OFF

    private fun bottom(switch: Key, label: String) = listOf(
        Cell(switch, label, 1.4f),
        c(bottomLeft, 1.1f),
        Cell(Key.Space, "mezerník", 4.4f),
        c(bottomRight, 1.1f),
        Cell(Key.Enter, "↵", 2.1f)
    )

    private fun numberRow() = "1234567890".map { c(it.toString()) }

    private fun buildLetterRows(): List<List<Cell>> {
        val rows = ArrayList<List<Cell>>()
        if (numberRowOn) rows.add(numberRow())
        rows.add("qwertzuiop".map { c(it.toString()) })
        rows.add("asdfghjkl".map { c(it.toString()) })
        rows.add(
            listOf(Cell(Key.Shift, "⇧", 1.5f)) +
                "yxcvbnm".map { c(it.toString()) } +
                listOf(Cell(Key.Backspace, "⌫", 1.5f))
        )
        rows.add(bottom(Key.Symbols, "#+="))
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

    private val emojiBottom = listOf(
        Cell(Key.Letters, "ABC", 1.4f),
        Cell(Key.EmojiSearch, "🔍", 1.2f),
        Cell(Key.Space, "mezerník", 4.4f),
        Cell(Key.Backspace, "⌫", 1.5f)
    )

    private var symbols = false
    private var emoji = false
    private var clip = false
    private val rows: List<List<Cell>>
        get() = when {
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
        if (numericRows.isEmpty()) return
        val area = rowCount() * (rowH + gap) - gap
        val h = (area - gap * (numericRows.size - 1)) / numericRows.size
        numericRows.forEachIndexed { r, row ->
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
        "/" to listOf("\\", "|")
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
        textAlign = Paint.Align.CENTER; textSize = 22 * d
    }
    private val suggestBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 18 * d
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val noticePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 13 * d
    }
    private val divider = Paint()
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
                Prefs.SHAPE_ROUNDED, Prefs.FONT_DEFAULT, true
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
        popupBg.color = palette.popup
        text.color = palette.text
        hintPaint.color = palette.hint
        suggestText.color = palette.text
        suggestBold.color = palette.text
        noticePaint.color = palette.notice
        divider.color = palette.divider
        iconPaint.color = palette.text
        clipText.color = palette.text
        previewText.color = palette.text
        trailPaint.color = palette.trail
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
        val layoutChanged = old == null || old.height != l.height || old.width != l.width ||
            old.numberRow != l.numberRow ||
            old.oneHand != l.oneHand || old.bottomLeft != l.bottomLeft || old.bottomRight != l.bottomRight
        if (layoutChanged) {
            numberRowOn = l.numberRow
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

    /** Číslice na horní řadě písmen, když je číselná řada vypnutá. */
    private val topDigits = "qwertzuiop".zip("1234567890").associate { (k, v) -> k.toString() to v.toString() }

    /**
     * Varianty klávesy seřazené pro aktuální jazyk; první je ta nejpoužívanější.
     * Bez číselné řady: na horní řadě je číslice hned za diakritikou (česky) nebo první.
     */
    private fun variantsFor(label: String): List<String>? {
        val base = variants[label]
        val digit = if (!numberRowOn) topDigits[label] else null
        if (base == null && digit == null) return null
        val list = base.orEmpty()
        val letters = list.filter { it[0].isLetter() }
        val others = list.filter { !it[0].isLetter() }
        return if (language == "cs") {
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
        (stripH + rowH * rowCount() + gap * (rowCount() + 1)).toInt()

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
        if (w > 0 && h > 0) onGeometry?.invoke(buildGeometry(w, h))
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

    private fun shown(cell: Cell): String =
        if (shiftOn && cell.key is Key.Char) cell.label.uppercase() else cell.label

    private fun hintFor(cell: Cell): String? {
        if (cell.key !is Key.Char || !cell.label[0].isLetterOrDigit()) return null
        if (!numberRowOn) topDigits[cell.label]?.let { return it }
        return variantsFor(cell.label)?.firstOrNull { !it[0].isLetter() }
    }

    private fun drawKey(canvas: Canvas, cell: Cell, paint: Paint, hint: Boolean) {
        // bez rámečků: běžná klávesa se nepodbarví, jen stisknutá a zvýrazněná
        if (!(flatKeys && paint === keyPaint)) {
            canvas.drawRoundRect(cell.rect, keyRadius, keyRadius, paint)
        }
        if (cell.key == Key.Enter) {
            drawEnterIcon(canvas, cell.rect.centerX(), cell.rect.centerY(), rowH * 0.46f)
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
    private fun slotsRight() = if (toolsVisible) toolsLeft() else emojiButtonLeft()
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
    private fun toolsLeft() = emojiButtonLeft() - toolW() * 2

    private fun drawTools(canvas: Canvas) {
        val w = toolW()
        val s = stripH * 0.5f
        val start = toolsLeft()
        canvas.drawRect(start - d / 2, stripH * 0.25f, start + d / 2, stripH * 0.75f, divider)
        for ((i, slot) in listOf(SLOT_CLIP, SLOT_UNDO).withIndex()) {
            val left = start + i * w
            if (stripPressed == slot) {
                canvas.drawRoundRect(left + gap, gap, left + w - gap, stripH - gap, 8 * d, 8 * d, keyDown)
            }
            if (slot == SLOT_CLIP) drawClipIcon(canvas, left + w / 2, stripH / 2, s)
            else drawUndoIcon(canvas, left + w / 2, stripH / 2, s)
        }
    }

    private fun drawSuggestions(canvas: Canvas) {
        drawEmojiButton(canvas)
        if (toolsVisible) drawTools(canvas)
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
        val q = if (emojiQuery.isEmpty()) "Hledat emoji" else emojiQuery
        val paint = if (emojiQuery.isEmpty()) noticePaint else suggestText
        val room = searchQueryW() - 16 * d
        val n = paint.breakText(q, false, room, null)
        val shownQ = if (n < q.length) "…" + q.substring(q.length - n + 1) else q
        val ty = stripH / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(shownQ, qLeft + (searchQueryW() - gap) / 2, ty, paint)
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
            canvas.drawText("Nic", searchResultsLeft() + (width - searchResultsLeft()) / 2, ny, noticePaint)
        }
    }

    private fun searchSlotAt(x: Float): Int = when {
        x < searchCloseW() -> SLOT_SEARCH_CLOSE
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
                    if (picked == SLOT_SEARCH_CLOSE) openEmoji()
                    else if (picked >= 0) pickEmoji(emojiResults[picked])
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
        if (toolsVisible && x >= toolsLeft()) {
            return if (x < toolsLeft() + toolW()) SLOT_CLIP else SLOT_UNDO
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
            canvas.drawText("Zatím žádná použitá emoji", width / 2f, ty, noticePaint)
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
        if (emojiSearch) drawSearchStrip(canvas) else drawSuggestions(canvas)
        drawOneHandPanel(canvas)
        rows.flatten().forEach { cell ->
            val paint = when {
                cell === pressed -> keyDown
                cell.key == Key.Shift && shiftOn -> keyOn
                cell.key == Key.Enter -> keyOn
                else -> keyPaint
            }
            drawKey(canvas, cell, paint, true)
        }
        if (gliding && glideCount > 1) {
            trail.reset()
            trail.moveTo(glideX[0], glideY[0])
            for (i in 1 until glideCount) trail.lineTo(glideX[i], glideY[i])
            canvas.drawPath(trail, trailPaint)
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

    /** Nové pole: hledání emoji se zavře. */
    fun endEmojiSearch() {
        if (!emojiSearch) return
        emojiSearch = false
        emojiQuery = ""
        emojiResults = emptyList()
        invalidate()
    }

    private fun openEmoji() {
        recentEmoji = EmojiData.recent(context)
        emojiTab = if (recentEmoji.isEmpty()) 1 else 0
        emoji = true
        clip = false
        emojiSearch = false
        emojiScroll = 0f
        invalidate()
    }

    private fun pickEmoji(e: String) {
        onKey(Key.Char(e))
        EmojiData.addRecent(context, e)
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
                openEmoji()
                return
            }
            else -> return
        }
        emojiResults = EmojiData.search(emojiQuery, emojiCats)
        invalidate()
    }

    private fun handleKey(key: Key) {
        if (emojiSearch) {
            searchKey(key)
            return
        }
        when (key) {
            Key.Symbols -> { symbols = true; emoji = false; clip = false; invalidate() }
            Key.Letters -> { symbols = false; emoji = false; clip = false; invalidate() }
            Key.Emoji -> openEmoji()
            Key.EmojiSearch -> {
                emojiSearch = true
                emojiQuery = ""
                emojiResults = emptyList()
                emoji = false
                symbols = false
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

    private fun clipMaxScroll(): Float =
        (clipItems.size * clipRowH() - (gridBottom() - gridTop())).coerceAtLeast(0f)

    private fun clipIndexAt(y: Float): Int {
        if (y < gridTop() || y > gridBottom()) return -1
        val i = ((y - gridTop() + clipScroll) / clipRowH()).toInt()
        return if (i in clipItems.indices) i else -1
    }

    private val clipLongPress = Runnable {
        val i = clipPressed
        if (i in clipItems.indices && !moved) {
            clipLongFired = true
            clipPressed = -1
            onClipRemove?.invoke(clipItems[i])
            invalidate()
        }
    }

    private fun drawClipboard(canvas: Canvas) {
        val top = gridTop()
        val bottom = gridBottom()
        if (clipItems.isEmpty()) {
            val ny = (top + bottom) / 2 - (noticePaint.descent() + noticePaint.ascent()) / 2
            canvas.drawText("Schránka je prázdná – zkopírovaný text se objeví tady", width / 2f, ny, noticePaint)
            val hy = ny + 22 * d
            canvas.drawText("Klepnutím text vložíš, špendlíkem připneš, podržením smažeš", width / 2f, hy, noticePaint)
        } else {
            val rh = clipRowH()
            canvas.save()
            canvas.clipRect(0f, top, width.toFloat(), bottom)
            clipItems.forEachIndexed { i, item ->
                val y = top + i * rh - clipScroll
                if (y + rh < top || y > bottom) return@forEachIndexed
                val r = RectF(gap, y + gap / 2, width - gap, y + rh - gap / 2)
                canvas.drawRoundRect(r, 8 * d, 8 * d, if (i == clipPressed) keyDown else keyPaint)
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
        emojiBottom.forEach { cell ->
            drawKey(canvas, cell, if (cell === pressed) keyDown else keyPaint, false)
        }
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
                pressed = emojiBottom.firstOrNull { it.rect.contains(e.x, e.y) }
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
                } else if (!moved && !clipLongFired && clipPressed >= 0 && clipPressed in clipItems.indices &&
                    e.x > width - gap - clipPinW()
                ) {
                    onClipPin?.invoke(clipItems[clipPressed])
                } else if (!moved && !clipLongFired && clipPressed >= 0 && clipPressed in clipItems.indices) {
                    val item = clipItems[clipPressed]
                    // po vložení zpátky na písmena
                    clip = false
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
        val step = (if (byWord) CURSOR_WORD_STEP_DP else CURSOR_STEP_DP) * d
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

    private fun resetGlide() {
        glideArmed = false
        gliding = false
        glideCount = 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (emoji) return emojiTouch(e)
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
                if (p != null && p.key is Key.Char && variantsFor(p.label) != null) {
                    postDelayed(longPress, LONG_PRESS_MS)
                }
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
                val list = popup
                val consumed = deleteRepeating || cursorMode || deleteSwipe
                if (deleteSwipe) onKey(Key.DeleteSwipeEnd)
                if (gliding) {
                    addGlidePoint(e.x, e.y, e.eventTime)
                    if (glideCount >= 2) onGesture?.invoke(glideX, glideY, glideT, glideCount)
                } else if (consumed) {
                    // podržený backspace nebo posun kurzoru: klávesa se už nepíše
                } else if (list != null) {
                    selected?.let { handleKey(it.key) }
                } else {
                    pressed?.let { handleKey(it.key) }
                }
                popup = null; selected = null; pressed = null
                resetGlide()
                resetSpecial()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
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
        const val MAX_GLIDE_POINTS = 4096

        /** Výchozí výška řady kláves (100 %). */
        const val BASE_ROW_DP = 46f

        /** Dlouhý stisk klávesy nebo návrhu. */
        const val LONG_PRESS_MS = 350L

        /** Šířka tlačítka emoji vpravo v liště návrhů. */
        const val EMOJI_BUTTON_DP = 52f
        const val EMOJI_ICON = "\uD83D\uDE42"

        /** stripPressed: tlačítka v liště (návrhy mají 0–2). */
        const val SLOT_EMOJI = 3
        const val SLOT_CLIP = 4
        const val SLOT_UNDO = 5
        const val SLOT_SEARCH_CLOSE = 6

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
