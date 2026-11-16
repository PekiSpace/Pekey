package cz.pekey

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Úvodní obrazovka a zároveň nastavení klávesnice (otevírá se i z nastavení Androidu). */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var learnedButton: TextView? = null
    private var setupCard: LinearLayout? = null
    private val prefs by lazy { Prefs.of(this) }
    private val userWords by lazy { UserWords.get(this) }

    /** Otevřená sekce (null = hlavní nabídka). */
    private var section: Section? = null

    private enum class Section(val title: String, val subtitle: String) {
        LOOK("Vzhled", "Motiv, tvar kláves, písmo"),
        LAYOUT("Rozložení", "Výška, šířka, číselná řada, jedna ruka"),
        TYPING("Psaní", "Jazyk, předpověď, opravy"),
        WORDS("Naučená slova", "Učení, seznam, zapomenutí")
    }

    /** Systémové zpět (Android 13+) vrací ze sekce do hlavní nabídky. */
    private val backCallback: Any? by lazy {
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { showMain() } else null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // zkušební volby knihovny gest už v nastavení nejsou: vždy vlastní engine
        prefs.edit().remove(Prefs.GESTURE_LIB).remove(Prefs.SWEET_SPOTS).apply()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(root)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        showMain()
    }

    override fun onResume() {
        super.onResume()
        refreshLearned()
        refreshSetup()
    }

    /** Výběr klávesnice je dialog, aktivita se po něm neobnoví, jen dostane zpět fokus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetup()
    }

    @Deprecated("Android 12 a starší")
    override fun onBackPressed() {
        if (section != null) showMain() else super.onBackPressed()
    }

    private fun setBackHandling(inSection: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback as OnBackInvokedCallback
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        if (inSection) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb
            )
        }
    }

    // ---- hlavní nabídka ----

    private fun showMain() {
        section = null
        setBackHandling(false)
        root.removeAllViews()
        learnedButton = null
        header()

        setupCard = card("Zapnutí").also { it.tag = null }
        refreshSetup()

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = params(16)
        }
        Section.values().forEachIndexed { i, s ->
            if (i > 0) list.addView(android.view.View(this).apply {
                setBackgroundColor(SURFACE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { marginStart = dp(16); marginEnd = dp(16) }
            })
            list.addView(sectionRow(s))
        }
        root.addView(list)

        footer()
        scroll.scrollTo(0, 0)
    }

    private fun sectionRow(s: Section): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(CARD, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSection(s) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = s.title
            textSize = 17f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        texts.addView(TextView(this).apply {
            text = s.subtitle
            textSize = 13f
            setTextColor(MUTED)
        })
        row.addView(texts)
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(ACCENT_LIGHT)
        })
        return row
    }

    private fun footer() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        root.addView(TextView(this).apply {
            text = "Pekey keyboard ${version ?: ""}\n© 2026\n@pekispace".replace(" \n", "\n")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            layoutParams = params(32)
        })
    }

    // ---- sekce ----

    private fun showSection(s: Section) {
        section = s
        setBackHandling(true)
        root.removeAllViews()
        setupCard = null
        learnedButton = null
        sectionHeader(s.title)
        val c = card(s.title).also { it.removeViewAt(0) }
        when (s) {
            Section.LOOK -> buildLook(c)
            Section.LAYOUT -> buildLayout(c)
            Section.TYPING -> buildTyping(c)
            Section.WORDS -> buildWords(c)
        }
        footer()
        refreshLearned()
        scroll.scrollTo(0, 0)
    }

    /** Horní řádek sekce: šipka zpět a název. */
    private fun sectionHeader(title: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = params(12)
        }
        row.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(ACCENT_LIGHT)
            background = rounded(SURFACE, 22)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            isClickable = true
            setOnClickListener { showMain() }
        })
        row.addView(TextView(this).apply {
            text = title
            textSize = 26f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), 0, 0, 0)
        })
        root.addView(row)
    }

    private fun buildLook(c: LinearLayout) {
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
        chips(
            c, "Tvar kláves",
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

    private fun buildLayout(c: LinearLayout) {
        slider(c, "Výška kláves", Prefs.HEIGHT_MIN, Prefs.HEIGHT_MAX, 5, Prefs.height(this), "%") {
            prefs.edit().putInt(Prefs.HEIGHT, it).apply()
        }
        slider(c, "Šířka klávesnice", Prefs.WIDTH_MIN, Prefs.WIDTH_MAX, 5, Prefs.width(this), "%") {
            prefs.edit().putInt(Prefs.WIDTH, it).apply()
        }
        toggle(c, "Číselná řada", Prefs.numberRow(this)) {
            prefs.edit().putBoolean(Prefs.NUMBER_ROW, it).apply()
        }
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

    private fun buildTyping(c: LinearLayout) {
        toggle(c, "Jazyk podle aplikace", Prefs.appLang(this)) {
            prefs.edit().putBoolean(Prefs.APP_LANG, it).apply()
        }
        toggle(c, "Předpověď dalšího slova", Prefs.predict(this)) {
            prefs.edit().putBoolean(Prefs.PREDICT, it).apply()
        }
        toggle(c, "Automatické opravy", Prefs.autocorrect(this)) {
            prefs.edit().putBoolean(Prefs.AUTOCORRECT, it).apply()
        }
        button(c, "Kontrola pravopisu v telefonu") {
            try {
                startActivity(Intent("android.settings.SPELL_CHECKER_SETTINGS"))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
    }

    private fun buildWords(c: LinearLayout) {
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
    }

    // ---- zapnutí ----

    private fun isEnabled(): Boolean =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.any { it.packageName == packageName }

    private fun isSelected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true

    /** Karta Zapnutí ukazuje jen krok, který ještě chybí, a zkušební pole. */
    private fun refreshSetup() {
        val setupCard = setupCard ?: return
        val enabled = isEnabled()
        val selected = enabled && isSelected()
        val state = (if (enabled) 1 else 0) + (if (selected) 1 else 0)
        if (setupCard.tag == state) return
        setupCard.tag = state
        while (setupCard.childCount > 1) setupCard.removeViewAt(1)
        when {
            !enabled -> button(setupCard, "1. Povolit Pekey", primary = true) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            !selected -> button(setupCard, "2. Vybrat Pekey", primary = true) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            else -> note(setupCard, "Pekey je zapnutá a vybraná.")
        }
        setupCard.addView(EditText(this).apply {
            hint = "Zkušební pole"
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            backgroundTintList = ColorStateList.valueOf(ACCENT)
            layoutParams = params(8)
        })
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
            text = "Pekey keyboard"
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
            thumbTintList = ColorStateList(states, intArrayOf(WHITE, SWITCH_OFF_THUMB))
            trackTintList = ColorStateList(states, intArrayOf(ACCENT, SWITCH_OFF_TRACK))
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
        val learnedButton = learnedButton ?: return
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
        // barvy obrazovky nastavení: černá, bílá, modrá
        val BACKGROUND = 0xFF000000.toInt()
        val CARD = 0xFF101114.toInt()
        val SURFACE = 0xFF1E2026.toInt()
        val ACCENT = 0xFF2D7BFF.toInt()
        val ACCENT_LIGHT = 0xFF7DB0FF.toInt()
        val TEXT = 0xFFFFFFFF.toInt()
        val MUTED = 0xFF8E9099.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val SWITCH_OFF_THUMB = 0xFFB8BAC2.toInt()
        val SWITCH_OFF_TRACK = 0xFF3A3D45.toInt()
    }
}
