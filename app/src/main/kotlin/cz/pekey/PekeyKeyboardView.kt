package cz.pekey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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

    /** Podržený návrh (např. zapomenout naučené slovo). */
    var onSuggestionLongPress: ((String) -> Unit)? = null

    /**
     * Jazyk ("cs", "en"): určuje pořadí variant dlouhého stisku.
     * Česky je první diakritika (á), anglicky symbol (@).
     */
    var language: String = "cs"

    /** Psaní gesty; vypíná se v polích bez návrhů (hesla, e-maily…). */
    var gestureEnabled: Boolean = true

    /** Dokončené gesto: souřadnice bodů a jejich počet. */
    var onGesture: ((FloatArray, FloatArray, Int) -> Unit)? = null

    private class Cell(val key: Key, val label: String, val weight: Float, val rect: RectF = RectF())

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun c(s: String, w: Float = 1f) = Cell(Key.Char(s), s, w)

    private fun bottom(switch: Key, label: String) = listOf(
        Cell(switch, label, 1.4f),
        c(",", 1.1f),
        Cell(Key.Space, "mezerník", 4.4f),
        c(".", 1.1f),
        Cell(Key.Enter, "↵", 2.1f)
    )

    private fun numberRow() = "1234567890".map { c(it.toString()) }

    private val letterRows: List<List<Cell>> = listOf(
        numberRow(),
        "qwertzuiop".map { c(it.toString()) },
        "asdfghjkl".map { c(it.toString()) },
        listOf(Cell(Key.Shift, "⇧", 1.5f)) +
            "yxcvbnm".map { c(it.toString()) } +
            listOf(Cell(Key.Backspace, "⌫", 1.5f)),
        bottom(Key.Symbols, "#+=")
    )

    private val symbolRows: List<List<Cell>> = listOf(
        numberRow(),
        "@#\$%&-+()".map { c(it.toString()) },
        "[]{}<>=_€".map { c(it.toString()) },
        listOf(c("/", 1.5f)) +
            "*\"':;!?".map { c(it.toString()) } +
            listOf(Cell(Key.Backspace, "⌫", 1.5f)),
        bottom(Key.Letters, "ABC")
    )

    private val emojiBottom = listOf(
        Cell(Key.Letters, "ABC", 1.5f),
        Cell(Key.Space, "mezerník", 5f),
        Cell(Key.Backspace, "⌫", 1.5f)
    )

    private var symbols = false
    private var emoji = false
    private var clip = false
    private val rows: List<List<Cell>> get() = if (symbols) symbolRows else letterRows

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
        "," to listOf("/")
    )

    private val emojis: List<String> = (
        "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥳 " +
        "😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 " +
        "🤫 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 " +
        "👍 👎 👌 🤞 🤟 🤘 👈 👉 👆 👇 👋 🤚 ✋ 🖖 👏 🙌 🤝 🙏 💪 " +
        "💛 💚 💙 💜 🖤 💔 💕 💖 💗 💓 🔥 ✨ ⭐ 🌟 💥 🎉 🎊 🎁 🏆 🍺 🍻 🍕 🍔 🍟 ☕ 🍰 " +
        "🐶 🐱 🐭 🐻 🦊 🌞 🌈 ⛅ ☔"
        ).split(" ").filter { it.isNotEmpty() } +
        listOf("\u2764\uFE0F", "\u270C\uFE0F", "\u2600\uFE0F")

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
    private val gap = 4 * d

    /** Výška řady kláves; mění se v nastavení. */
    private var rowH = BASE_ROW_DP * d

    /** Volný pruh nad klávesami: lišta návrhů a varianty dlouhého stisku. */
    private val stripH get() = rowH + gap

    /** Symboly v rozích kláves. */
    private var showHints = true

    private var heightPercent = 100

    init {
        applySettings(Palette.DARK, 100, true)
    }

    /** Motiv, výška kláves (v % výchozí) a symboly v rozích. */
    fun applySettings(palette: Palette, height: Int, hints: Boolean) {
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
        trailPaint.color = palette.trail
        showHints = hints

        if (height != heightPercent) {
            heightPercent = height
            rowH = BASE_ROW_DP * d * height / 100f
            // nižší klávesy: o něco menší písmo, aby se vešlo
            val scale = (height / 100f).coerceIn(0.8f, 1.15f)
            text.textSize = 22 * d * scale
            hintPaint.textSize = 12 * d * scale
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
    private var spaceStartX = 0f
    private var spaceLastX = 0f

    /** Poloha prstu ve chvíli, kdy se otevřela lišta variant. */
    private var popupAnchorX = 0f
    private var lastTouchX = 0f

    /** Varianty klávesy seřazené pro aktuální jazyk; první je ta nejpoužívanější. */
    private fun variantsFor(label: String): List<String>? {
        val list = variants[label] ?: return null
        if (language == "cs") return list
        // mimo češtinu nejdřív symboly, česká diakritika až za nimi
        return list.filter { !it[0].isLetter() } + list.filter { it[0].isLetter() }
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
        (stripH + rowH * letterRows.size + gap * (letterRows.size + 1)).toInt()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), measuredHeightFor())
    }

    private fun placeRow(row: List<Cell>, r: Int, w: Int) {
        val total = row.sumOf { it.weight.toDouble() }.toFloat()
        val unit = (w - gap * (row.size + 1)) / total
        var x = gap
        val y = stripH + gap + r * (rowH + gap)
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
        letterRows.forEachIndexed { r, row -> placeRow(row, r, w) }
        symbolRows.forEachIndexed { r, row -> placeRow(row, r, w) }
        placeRow(emojiBottom, letterRows.size - 1, w)
        if (w > 0 && h > 0) onGeometry?.invoke(buildGeometry(w, h))
    }

    /** Polohy písmenných kláves pro nativní knihovnu (návrhy, později gesta). */
    private fun buildGeometry(w: Int, h: Int): KeyGeometry {
        val keys = letterRows.drop(1).flatten()
            .filter { it.key is Key.Char && it.label[0].isLetter() }
        return KeyGeometry(
            width = w,
            height = h,
            keyWidth = keys.first().rect.width().toInt(),
            keyHeight = keys.first().rect.height().toInt(),
            codes = IntArray(keys.size) { keys[it].label[0].code },
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
        return variantsFor(cell.label)?.firstOrNull { !it[0].isLetter() }
    }

    private fun drawKey(canvas: Canvas, cell: Cell, paint: Paint, hint: Boolean) {
        canvas.drawRoundRect(cell.rect, 8 * d, 8 * d, paint)
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
    private fun slotWidth() = emojiButtonLeft() / 3f

    private fun drawEmojiButton(canvas: Canvas) {
        val left = emojiButtonLeft()
        if (stripPressed == SLOT_EMOJI) {
            canvas.drawRoundRect(left + gap, gap, width - gap, stripH - gap, 8 * d, 8 * d, keyDown)
        }
        canvas.drawRect(left - d / 2, stripH * 0.25f, left + d / 2, stripH * 0.75f, divider)
        val ty = stripH / 2f - (emojiButtonPaint.descent() + emojiButtonPaint.ascent()) / 2
        canvas.drawText(EMOJI_ICON, left + emojiButtonW() / 2, ty, emojiButtonPaint)
    }

    /** Nástroje vlevo v liště, když nejsou návrhy: schránka a zpět. */
    private fun toolW() = EMOJI_BUTTON_DP * d
    private fun toolsRight() = toolW() * 2

    private fun drawTools(canvas: Canvas) {
        val w = toolW()
        val s = stripH * 0.5f
        for ((i, slot) in listOf(SLOT_CLIP, SLOT_UNDO).withIndex()) {
            val left = i * w
            if (stripPressed == slot) {
                canvas.drawRoundRect(left + gap, gap, left + w - gap, stripH - gap, 8 * d, 8 * d, keyDown)
            }
            if (slot == SLOT_CLIP) drawClipIcon(canvas, left + w / 2, stripH / 2, s)
            else drawUndoIcon(canvas, left + w / 2, stripH / 2, s)
        }
    }

    private fun drawSuggestions(canvas: Canvas) {
        drawEmojiButton(canvas)
        val list = suggestions
        if (list.isEmpty()) {
            drawTools(canvas)
            if (notice.isNotEmpty()) {
                val ny = stripH / 2f - (noticePaint.descent() + noticePaint.ascent()) / 2
                val room = emojiButtonLeft() - toolsRight()
                val n = noticePaint.breakText(notice, true, room - 16 * d, null)
                val shownNotice = if (n < notice.length) notice.substring(0, maxOf(n - 1, 1)) + "…" else notice
                canvas.drawText(shownNotice, toolsRight() + room / 2f, ny, noticePaint)
            }
            return
        }
        val slotW = slotWidth()
        val ty = stripH / 2f - (suggestText.descent() + suggestText.ascent()) / 2
        list.forEachIndexed { i, s ->
            val left = i * slotW
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
        if (suggestions.isEmpty()) {
            return when {
                x < toolW() -> SLOT_CLIP
                x < toolsRight() -> SLOT_UNDO
                else -> -1
            }
        }
        val i = (x / slotWidth()).toInt().coerceIn(0, 2)
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
    private fun gridTop() = gap
    private fun gridBottom() = emojiBottom[0].rect.top - gap
    private fun cellSize() = width.toFloat() / cols
    private fun maxScroll(): Float {
        val rowsN = (emojis.size + cols - 1) / cols
        return (rowsN * cellSize() - (gridBottom() - gridTop())).coerceAtLeast(0f)
    }

    private fun drawEmoji(canvas: Canvas) {
        val cs = cellSize()
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
        drawSuggestions(canvas)
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
    }

    private fun handleKey(key: Key) {
        when (key) {
            Key.Symbols -> { symbols = true; emoji = false; clip = false; invalidate() }
            Key.Letters -> { symbols = false; emoji = false; clip = false; invalidate() }
            Key.Emoji -> { emoji = true; clip = false; emojiScroll = 0f; invalidate() }
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
                } else if (!moved && e.y >= gridTop() && e.y <= gridBottom()) {
                    val cs = cellSize()
                    val col = (e.x / cs).toInt()
                    val row = ((e.y - gridTop() + emojiScroll) / cs).toInt()
                    val idx = row * cols + col
                    if (col in 0 until cols && idx in emojis.indices) {
                        onKey(Key.Char(emojis[idx]))
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
            canvas.drawText("Klepnutím text vložíš, podržením ho smažeš", width / 2f, hy, noticePaint)
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
                val room = r.width() - 24 * d
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
        when (cell?.key) {
            Key.Backspace -> postDelayed(deleteRepeat, DELETE_HOLD_MS)
            Key.Space -> {
                spaceArmed = true
                spaceStartX = e.x
                spaceLastX = e.x
            }
            else -> Unit
        }
    }

    private fun trackCursor(e: MotionEvent) {
        val step = CURSOR_STEP_DP * d
        if (!cursorMode) {
            if (Math.abs(e.x - spaceStartX) < CURSOR_START_DP * d) return
            cursorMode = true
            spaceLastX = spaceStartX
        }
        while (e.x - spaceLastX >= step) {
            onKey(Key.CursorRight)
            spaceLastX += step
        }
        while (spaceLastX - e.x >= step) {
            onKey(Key.CursorLeft)
            spaceLastX -= step
        }
    }

    private fun resetSpecial() {
        removeCallbacks(deleteRepeat)
        deleteRepeating = false
        spaceArmed = false
        cursorMode = false
    }

    // ---- gesta ----

    /** Gesto může začít jen na písmenu v písmenné vrstvě. */
    private fun startGlide(cell: Cell?, e: MotionEvent) {
        resetGlide()
        if (!gestureEnabled || symbols || emoji || onGesture == null) return
        if (cell == null || cell.key !is Key.Char || !cell.label[0].isLetter()) return
        glideArmed = true
        addGlidePoint(e.x, e.y)
    }

    private fun trackGlide(e: MotionEvent) {
        for (h in 0 until e.historySize) addGlidePoint(e.getHistoricalX(h), e.getHistoricalY(h))
        addGlidePoint(e.x, e.y)
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

    private fun addGlidePoint(x: Float, y: Float) {
        if (glideCount == glideX.size) {
            if (glideCount >= MAX_GLIDE_POINTS) return
            glideX = glideX.copyOf(glideCount * 2)
            glideY = glideY.copyOf(glideCount * 2)
        }
        glideX[glideCount] = x
        glideY[glideCount] = y
        glideCount++
    }

    private fun resetGlide() {
        glideArmed = false
        gliding = false
        glideCount = 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (emoji) return emojiTouch(e)
        if (clip) return clipTouch(e)
        if (stripPressed >= 0 || stripLongFired ||
            (e.actionMasked == MotionEvent.ACTION_DOWN && e.y < stripH &&
                (suggestions.isNotEmpty() || e.x >= emojiButtonLeft() || e.x < toolsRight()))
        ) {
            return stripTouch(e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(longPress)
                pressed = rows.flatten().firstOrNull { it.rect.contains(e.x, e.y) }
                val p = pressed
                lastTouchX = e.x
                if (p != null && p.key is Key.Char && variants.containsKey(p.label)) {
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
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val list = popup
                val consumed = deleteRepeating || cursorMode
                if (gliding) {
                    addGlidePoint(e.x, e.y)
                    if (glideCount >= 2) onGesture?.invoke(glideX, glideY, glideCount)
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

        /** Výška řádku v panelu schránky. */
        const val CLIP_ROW_DP = 52f
        const val CLIP_LONG_PRESS_MS = 500L

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
    }
}
