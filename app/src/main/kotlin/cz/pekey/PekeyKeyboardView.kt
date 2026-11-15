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

    /** Podržený návrh (např. zapomenout naučené slovo). */
    var onSuggestionLongPress: ((String) -> Unit)? = null

    /** Stisk klávesy (vibrace a zvuk). */
    var onPress: ((Key) -> Unit)? = null

    /** Psaní gesty; vypíná se v polích bez návrhů (hesla, e-maily…). */
    var gestureEnabled: Boolean = true

    /** Dokončené gesto: souřadnice bodů a jejich počet. */
    var onGesture: ((FloatArray, FloatArray, Int) -> Unit)? = null

    private class Cell(val key: Key, val label: String, val weight: Float, val rect: RectF = RectF())

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun c(s: String, w: Float = 1f) = Cell(Key.Char(s), s, w)

    private fun bottom(switch: Key, label: String) = listOf(
        Cell(switch, label, 1.3f),
        Cell(Key.Emoji, "\uD83D\uDE42", 1.3f),
        c(",", 1.1f),
        Cell(Key.Space, "mezerník", 3.8f),
        c(".", 1.1f),
        Cell(Key.Enter, "↵", 1.5f)
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
        noticePaint.color = palette.notice
        divider.color = palette.divider
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

    private val longPress = Runnable {
        if (gliding) return@Runnable
        val p = pressed ?: return@Runnable
        val list = variants[p.label] ?: return@Runnable
        val cw = 48 * d
        val total = cw * list.size
        var x = p.rect.centerX() - total / 2
        x = x.coerceIn(gap * 2, (width - gap * 2 - total).coerceAtLeast(gap * 2))
        val top = p.rect.top - rowH - gap
        popup = list.mapIndexed { i, v ->
            Cell(Key.Char(v), v, 1f, RectF(x + i * cw, top, x + (i + 1) * cw, top + rowH))
        }
        popupRect = RectF(x - gap, top - gap / 2, x + total + gap, top + rowH + gap / 2)
        selected = popup?.first()
        invalidate()
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
        return variants[cell.label]?.firstOrNull { !it[0].isLetter() }
    }

    private fun drawKey(canvas: Canvas, cell: Cell, paint: Paint, hint: Boolean) {
        canvas.drawRoundRect(cell.rect, 8 * d, 8 * d, paint)
        val ty = cell.rect.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(shown(cell), cell.rect.centerX(), ty, text)
        if (hint && showHints) {
            hintFor(cell)?.let {
                canvas.drawText(it, cell.rect.right - 6 * d, cell.rect.top + 15 * d, hintPaint)
            }
        }
    }

    // ---- lišta návrhů ----
    private fun drawSuggestions(canvas: Canvas) {
        val list = suggestions
        if (list.isEmpty()) {
            if (notice.isNotEmpty()) {
                val ny = stripH / 2f - (noticePaint.descent() + noticePaint.ascent()) / 2
                canvas.drawText(notice, width / 2f, ny, noticePaint)
            }
            return
        }
        val slotW = width / 3f
        val ty = stripH / 2f - (suggestText.descent() + suggestText.ascent()) / 2
        list.forEachIndexed { i, s ->
            val left = i * slotW
            if (i == stripPressed) {
                canvas.drawRoundRect(
                    left + gap, gap, left + slotW - gap, stripH - gap, 8 * d, 8 * d, keyDown
                )
            }
            val n = suggestText.breakText(s, true, slotW - 16 * d, null)
            val shownText = if (n < s.length) s.substring(0, maxOf(n - 1, 1)) + "…" else s
            canvas.drawText(shownText, left + slotW / 2, ty, suggestText)
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
        val i = (x / (width / 3f)).toInt().coerceIn(0, 2)
        return if (i < suggestions.size) i else -1
    }

    private fun stripTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stripLongFired = false
                stripPressed = slotAt(e.x)
                if (stripPressed >= 0) {
                    onPress?.invoke(Key.Char(suggestions[stripPressed]))
                    postDelayed(stripLongPress, LONG_PRESS_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(stripLongPress)
                val picked = stripPressed
                val slot = slotAt(e.x)
                stripPressed = -1
                invalidate()
                if (!stripLongFired && picked >= 0 && slot == picked && picked < suggestions.size) {
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
        drawSuggestions(canvas)
        rows.flatten().forEach { cell ->
            val paint = when {
                cell.key == Key.Shift && shiftOn -> keyOn
                cell === pressed -> keyDown
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
            Key.Symbols -> { symbols = true; emoji = false; invalidate() }
            Key.Letters -> { symbols = false; emoji = false; invalidate() }
            Key.Emoji -> { emoji = true; emojiScroll = 0f; invalidate() }
            else -> onKey(key)
        }
    }

    private fun emojiTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; lastY = e.y; moved = false
                pressed = emojiBottom.firstOrNull { it.rect.contains(e.x, e.y) }
                pressed?.let { onPress?.invoke(it.key) }
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
                        onPress?.invoke(Key.Char(emojis[idx]))
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
        if (stripPressed >= 0 || stripLongFired ||
            (e.actionMasked == MotionEvent.ACTION_DOWN && e.y < stripH && suggestions.isNotEmpty())
        ) {
            return stripTouch(e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(longPress)
                pressed = rows.flatten().firstOrNull { it.rect.contains(e.x, e.y) }
                val p = pressed
                if (p != null) onPress?.invoke(p.key)
                if (p != null && p.key is Key.Char && variants.containsKey(p.label)) {
                    postDelayed(longPress, LONG_PRESS_MS)
                }
                startGlide(p, e)
                startSpecial(p, e)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val list = popup
                if (list != null) {
                    selected = list.minByOrNull { Math.abs(it.rect.centerX() - e.x) }
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
