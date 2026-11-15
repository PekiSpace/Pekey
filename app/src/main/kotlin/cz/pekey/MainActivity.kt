package cz.pekey

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import java.io.File

/** Úvodní obrazovka a zároveň nastavení klávesnice (otevírá se i z nastavení Androidu). */
class MainActivity : Activity() {

    private val reqDict = 1

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var learnedButton: TextView
    private val suggester by lazy { Suggester(this) }
    private val prefs by lazy { Prefs.of(this) }
    private val userWords by lazy { UserWords.get(this) }

    private val dictDir: File get() = File(filesDir, "dicts").apply { mkdirs() }
    private val libDir: File get() = File(filesDir, "lib").apply { mkdirs() }

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
                hint = "Zkušební pole – tady si změny vyzkoušíš"
                setTextColor(TEXT)
                setHintTextColor(MUTED)
                backgroundTintList = ColorStateList.valueOf(ACCENT)
                layoutParams = params(8)
            })
        }

        // ---- vzhled ----
        card("Vzhled").let { c ->
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
            slider(c, "Výška kláves", Prefs.HEIGHT_MIN, Prefs.HEIGHT_MAX, 5, Prefs.height(this), "%") {
                prefs.edit().putInt(Prefs.HEIGHT, it).apply()
            }
            toggle(c, "Symboly v rozích kláves", Prefs.hints(this)) {
                prefs.edit().putBoolean(Prefs.HINTS, it).apply()
            }
            toggle(c, "Bublina nad stisknutou klávesou", Prefs.preview(this)) {
                prefs.edit().putBoolean(Prefs.PREVIEW, it).apply()
            }
        }

        // ---- rozložení ----
        card("Rozložení").let { c ->
            toggle(c, "Číselná řada", Prefs.numberRow(this)) {
                prefs.edit().putBoolean(Prefs.NUMBER_ROW, it).apply()
            }
            note(c, "Bez číselné řady jsou číslice v rozích horní řady písmen (podržením).")
            chips(
                c, "Jedna ruka",
                listOf(
                    Prefs.ONE_HAND_OFF to "Vypnuto",
                    Prefs.ONE_HAND_LEFT to "Vlevo",
                    Prefs.ONE_HAND_RIGHT to "Vpravo"
                ),
                Prefs.oneHand(this)
            ) { prefs.edit().putString(Prefs.ONE_HAND, it).apply() }
            note(c, "Na klávesnici se pak dá přesunout na druhou stranu (⇆) nebo zrušit (⤢).")
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
            note(c, "Tah po mezerníku posouvá kurzor po znacích, rychlý tah po celých slovech.")
        }

        // ---- psaní ----
        card("Psaní").let { c ->
            toggle(c, "Předpověď dalšího slova", Prefs.predict(this)) {
                prefs.edit().putBoolean(Prefs.PREDICT, it).apply()
            }
            toggle(c, "Automatické opravy", Prefs.autocorrect(this)) {
                prefs.edit().putBoolean(Prefs.AUTOCORRECT, it).apply()
            }
            note(
                c,
                "Překlep se při mezerníku nebo interpunkci opraví na zvýrazněný návrh " +
                    "(třeba dekuji → děkuji). Backspace hned po opravě vrátí, co jsi napsal."
            )
        }

        // ---- naučená slova ----
        card("Naučená slova").let { c ->
            toggle(c, "Učit se nová slova", Prefs.learn(this)) {
                prefs.edit().putBoolean(Prefs.LEARN, it).apply()
            }
            note(
                c,
                "Slovo, které není ve slovníku, se začne nabízet, když ho napíšeš dvakrát, " +
                    "nebo hned, když v liště návrhů klepneš na přesně napsané slovo. " +
                    "Podržením návrhu naučené slovo zapomeneš. Pekey si pamatuje i to, " +
                    "která slova píšeš často (lepší gesta). Nic se neposílá mimo telefon."
            )
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

        // ---- slovníky ----
        card("Slovníky a knihovna").let { c ->
            button(c, "Přidat slovník (.dict)") { pick(reqDict) }
            // knihovna .so se nemaže: bez ní nefungují návrhy a znovu přidat už nejde
            button(c, "Smazat přidané slovníky") {
                deleteAll(dictDir)
                refresh()
            }
            button(c, "Otestovat knihovnu") {
                AlertDialog.Builder(this)
                    .setMessage(GestureLib.load(this))
                    .setPositiveButton("OK", null)
                    .show()
            }
            button(c, "Otestovat návrhy") {
                AlertDialog.Builder(this)
                    .setMessage(suggester.selfTest())
                    .setPositiveButton("OK", null)
                    .show()
            }
            status = TextView(this).apply {
                setTextColor(MUTED)
                textSize = 13f
                layoutParams = params(12)
            }
            c.addView(status)
        }

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
        refresh()
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
    private fun card(title: String): LinearLayout {
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
        root.addView(c)
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

    // ---- slovníky ----

    private fun pick(req: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, req)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == reqDict) import(uri, dictDir, ".dict")
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun deleteAll(dir: File) {
        dir.listFiles()?.forEach {
            it.setWritable(true)
            it.delete()
        }
    }

    private fun import(uri: Uri, dir: File, extension: String) {
        val name = displayName(uri)?.substringAfterLast('/')
        if (name == null || !name.endsWith(extension)) {
            Toast.makeText(this, "Soubor musí mít příponu $extension", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            val message = try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (target.exists()) {
                    target.setWritable(true)
                    target.delete()
                }
                check(tmp.renameTo(target)) { "Soubor se nepodařilo uložit" }
                "Přidáno: $name"
            } catch (e: Exception) {
                tmp.delete()
                "Chyba: ${e.message}"
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun listing(dir: File): String {
        val files = dir.listFiles { f -> !f.name.endsWith(".tmp") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return "  (nic)"
        return files.joinToString("\n") { "  ${it.name}  (${it.length() / 1024} kB)" }
    }

    private fun refresh() {
        status.text = "Přidané slovníky:\n${listing(dictDir)}\n\nKnihovna pro návrhy:\n${listing(libDir)}"
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
