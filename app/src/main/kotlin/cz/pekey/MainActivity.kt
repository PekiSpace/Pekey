package cz.pekey

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Úvodní obrazovka a zároveň nastavení klávesnice (otevírá se i z nastavení Androidu). */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var learnedButton: TextView
    private val suggester by lazy { Suggester(this) }
    private val prefs by lazy { Prefs.of(this) }
    private val userWords by lazy { UserWords.get(this) }


    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        header()

        // ---- zapnutí ----
        card("Zapnutí").let { c ->
            button(c, "1. Povolit Pekey", primary = true) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            button(c, "2. Vybrat Pekey", primary = true) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            c.addView(EditText(this).apply {
                hint = "Zkušební pole"
                setTextColor(TEXT)
                setHintTextColor(MUTED)
                backgroundTintList = ColorStateList.valueOf(ACCENT)
                layoutParams = params(8)
            })
        }

        // ---- klávesnice ----
        card("Klávesnice").let { c ->
            chips(
                c, "Motiv",
                listOf(
                    Prefs.THEME_SYSTEM to "Podle systému",
                    Prefs.THEME_DARK to "Tmavý",
                    Prefs.THEME_LIGHT to "Světlý",
                    Prefs.THEME_BLACK to "Černý"
                ),
                Prefs.theme(this)
            ) { prefs.edit().putString(Prefs.THEME, it).apply() }
            slider(c, "Výška kláves", Prefs.HEIGHT_MIN, Prefs.HEIGHT_MAX, 5, Prefs.height(this), "%") {
                prefs.edit().putInt(Prefs.HEIGHT, it).apply()
            }
            slider(c, "Šířka klávesnice", Prefs.WIDTH_MIN, Prefs.WIDTH_MAX, 5, Prefs.width(this), "%") {
                prefs.edit().putInt(Prefs.WIDTH, it).apply()
            }
            toggle(c, "Číselná řada", Prefs.numberRow(this)) {
                prefs.edit().putBoolean(Prefs.NUMBER_ROW, it).apply()
            }
        }

        // ---- psaní ----
        card("Psaní").let { c ->
            toggle(c, "Jazyk podle aplikace", Prefs.appLang(this)) {
                prefs.edit().putBoolean(Prefs.APP_LANG, it).apply()
            }
            toggle(c, "Předpověď dalšího slova", Prefs.predict(this)) {
                prefs.edit().putBoolean(Prefs.PREDICT, it).apply()
            }
            toggle(c, "Automatické opravy", Prefs.autocorrect(this)) {
                prefs.edit().putBoolean(Prefs.AUTOCORRECT, it).apply()
            }
            toggle(c, "Učit se nová slova", Prefs.learn(this)) {
                prefs.edit().putBoolean(Prefs.LEARN, it).apply()
            }
            learnedButton = button(c, "Naučená slova") { showLearned() }
            button(c, "Zapomenout naučená slova a historii psaní") {
                AlertDialog.Builder(this)
                    .setMessage("Opravdu zapomenout všechna naučená slova i to, co často píšeš?")
                    .setPositiveButton("Zapomenout") { _, _ ->
                        userWords.clear()
                        refreshLearned()
                    }
                    .setNegativeButton("Zrušit", null)
                    .show()
            }
            button(c, "Kontrola pravopisu v telefonu") {
                try {
                    startActivity(Intent("android.settings.SPELL_CHECKER_SETTINGS"))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            }
        }

        // ---- další nastavení (sbalené) ----
        val more = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        val toggleMore = button(root, "Další nastavení ▾") {}
        toggleMore.setOnClickListener {
            val open = more.visibility != android.view.View.VISIBLE
            more.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            toggleMore.text = if (open) "Další nastavení ▴" else "Další nastavení ▾"
        }
        root.addView(more)

        card("Vzhled kláves", more).let { c ->
            chips(
                c, "Tvar",
                listOf(
                    Prefs.SHAPE_ROUNDED to "Zaoblené",
                    Prefs.SHAPE_ROUND to "Kulaté",
                    Prefs.SHAPE_SQUARE to "Hranaté",
                    Prefs.SHAPE_FLAT to "Bez rámečků"
                ),
                Prefs.shape(this)
            ) { prefs.edit().putString(Prefs.SHAPE, it).apply() }
            chips(
                c, "Písmo",
                listOf(
                    Prefs.FONT_DEFAULT to "Výchozí",
                    Prefs.FONT_MEDIUM to "Polotučné",
                    Prefs.FONT_CONDENSED to "Úzké"
                ),
                Prefs.font(this)
            ) { prefs.edit().putString(Prefs.FONT, it).apply() }
            toggle(c, "Symboly v rozích kláves", Prefs.hints(this)) {
                prefs.edit().putBoolean(Prefs.HINTS, it).apply()
            }
            toggle(c, "Bublina nad stisknutou klávesou", Prefs.preview(this)) {
                prefs.edit().putBoolean(Prefs.PREVIEW, it).apply()
            }
        }

        card("Rozložení", more).let { c ->
            chips(
                c, "Jedna ruka",
                listOf(
                    Prefs.ONE_HAND_OFF to "Vypnuto",
                    Prefs.ONE_HAND_LEFT to "Vlevo",
                    Prefs.ONE_HAND_RIGHT to "Vpravo"
                ),
                Prefs.oneHand(this)
            ) { prefs.edit().putString(Prefs.ONE_HAND, it).apply() }
            chips(
                c, "Klávesa vlevo od mezerníku",
                Prefs.BOTTOM_CHOICES.map { it to it },
                Prefs.bottomLeft(this)
            ) { prefs.edit().putString(Prefs.BOTTOM_LEFT, it).apply() }
            chips(
                c, "Klávesa vpravo od mezerníku",
                Prefs.BOTTOM_CHOICES.map { it to it },
                Prefs.bottomRight(this)
            ) { prefs.edit().putString(Prefs.BOTTOM_RIGHT, it).apply() }
        }

        card("Gesta", more).let { c ->
            chips(
                c, "Rozpoznávání gest",
                listOf("own" to "Vlastní", "lib" to "Knihovna (zkušebně)"),
                if (Prefs.gestureLib(this)) "lib" else "own"
            ) { prefs.edit().putBoolean(Prefs.GESTURE_LIB, it == "lib").apply() }
            chips(
                c, "Varianta knihovny gest",
                listOf("0" to "A", "1" to "B", "2" to "C"),
                Prefs.sweetSpots(this).toString()
            ) { prefs.edit().putInt(Prefs.SWEET_SPOTS, it.toInt()).apply() }
            button(c, "Otestovat návrhy") {
                AlertDialog.Builder(this)
                    .setMessage(suggester.selfTest())
                    .setPositiveButton("OK", null)
                    .show()
            }
            button(c, "Otestovat knihovnu") {
                AlertDialog.Builder(this)
                    .setMessage(GestureLib.load(this))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        // ---- patička ----
        root.addView(TextView(this).apply {
            text = "Pekey\n© 2026\n@pekispace"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            layoutParams = params(32)
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(root)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        refreshLearned()
    }

    override fun onResume() {
        super.onResume()
        refreshLearned()
    }

    override fun onDestroy() {
        suggester.close()
        super.onDestroy()
    }

    // ---- stavebnice obrazovky ve stylu Pekey ----

    private fun params(topDp: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun header() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = params(16)
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = "Pekey"
            textSize = 28f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        texts.addView(TextView(this).apply {
            text = "Offline klávesnice · nic neposílá z telefonu"
            textSize = 13f
            setTextColor(MUTED)
        })
        row.addView(texts)
        root.addView(row)
    }

    /** Karta s nadpisem; vrací obsah karty, do kterého se přidávají prvky. */
    private fun card(title: String, parent: LinearLayout = root): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            layoutParams = params(16)
        }
        c.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(ACCENT_LIGHT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        parent.addView(c)
        return c
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(MUTED)
            layoutParams = params(4)
        })
    }

    private fun label(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(TEXT)
            layoutParams = params(12)
        })
    }

    private fun button(
        parent: LinearLayout,
        text: String,
        primary: Boolean = false,
        onClick: () -> Unit
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (primary) WHITE else TEXT)
            background = rounded(if (primary) ACCENT else SURFACE, 14)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            isClickable = true
            isFocusable = true
            layoutParams = params(8)
            setOnClickListener { onClick() }
            parent.addView(this)
        }

    private fun toggle(parent: LinearLayout, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        parent.addView(Switch(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(TEXT)
            isChecked = checked
            thumbTintList = ColorStateList(states, intArrayOf(ACCENT_LIGHT, 0xFFB9BAC4.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(ACCENT, 0xFF4A4C57.toInt()))
            layoutParams = params(8)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
    }

    /** Volba z několika možností jako řada „čipů“; vybraný je ve zvýrazňovací barvě. */
    private fun chips(
        parent: LinearLayout,
        title: String,
        options: List<Pair<String, String>>,
        current: String,
        onChange: (String) -> Unit
    ) {
        label(parent, title)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        var selected = if (options.any { it.first == current }) current else options.first().first
        fun paint() {
            for (v in views) {
                val on = v.tag == selected
                v.background = rounded(if (on) ACCENT else SURFACE, 18)
                v.setTextColor(if (on) WHITE else TEXT)
            }
        }
        for ((value, text) in options) {
            val chip = TextView(this).apply {
                this.text = text
                tag = value
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = dp(44)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    selected = value
                    paint()
                    onChange(value)
                }
            }
            views.add(chip)
            row.addView(chip)
        }
        paint()
        parent.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = params(6)
            addView(row)
        })
    }

    /** Popisek s hodnotou a posuvník od [from] do [to] v krocích [step]. */
    private fun slider(
        parent: LinearLayout,
        label: String,
        from: Int,
        to: Int,
        step: Int,
        value: Int,
        unit: String,
        onChange: (Int) -> Unit
    ) {
        val caption = TextView(this).apply {
            textSize = 15f
            setTextColor(TEXT)
            layoutParams = params(12)
        }
        fun show(v: Int) {
            caption.text = "$label: $v $unit"
        }
        show(value)
        val steps = (to - from) / step
        val bar = SeekBar(this)
        bar.max = steps
        bar.progress = ((value - from) / step).coerceIn(0, steps)
        bar.progressTintList = ColorStateList.valueOf(ACCENT)
        bar.thumbTintList = ColorStateList.valueOf(ACCENT_LIGHT)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val v = from + p * step
                show(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        parent.addView(caption)
        parent.addView(bar)
    }

    // ---- naučená slova ----

    private fun refreshLearned() {
        if (!::learnedButton.isInitialized) return
        learnedButton.text = "Naučená slova (${userWords.all().size})"
    }

    private fun showLearned() {
        val list = userWords.all()
        if (list.isEmpty()) {
            Toast.makeText(this, "Zatím žádná naučená slova", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "${it.word}   (${it.lang}, ${it.count}×)" }.toTypedArray()
        val checked = BooleanArray(list.size)
        AlertDialog.Builder(this)
            .setTitle("Vyber slova k zapomenutí")
            .setMultiChoiceItems(labels, checked) { _, i, value -> checked[i] = value }
            .setPositiveButton("Zapomenout vybraná") { _, _ ->
                list.forEachIndexed { i, e -> if (checked[i]) userWords.remove(e.word, e.lang) }
                refreshLearned()
            }
            .setNegativeButton("Zavřít", null)
            .show()
    }

    private companion object {
        // barvy obrazovky nastavení (styl Pekey, vždy tmavý)
        val BACKGROUND = 0xFF121216.toInt()
        val CARD = 0xFF1C1C22.toInt()
        val SURFACE = 0xFF2E2F36.toInt()
        val ACCENT = 0xFF6F63C9.toInt()
        val ACCENT_LIGHT = 0xFFA79DF0.toInt()
        val TEXT = 0xFFEDEDF2.toInt()
        val MUTED = 0xFF9A9BA8.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
