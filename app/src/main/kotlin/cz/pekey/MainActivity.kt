package cz.pekey

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var learnedButton: Button
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
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // ---- zapnutí ----
        heading("Pekey")
        button("1. Povolit Pekey") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("2. Vybrat Pekey") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        root.addView(EditText(this).apply { hint = "Zkušební pole – tady si změny vyzkoušíš" })

        // ---- vzhled ----
        section("Vzhled")
        themeChooser()
        slider(
            "Výška kláves", Prefs.HEIGHT_MIN, Prefs.HEIGHT_MAX, 5, Prefs.height(this), "%"
        ) { prefs.edit().putInt(Prefs.HEIGHT, it).apply() }
        toggle("Symboly v rozích kláves", Prefs.hints(this)) {
            prefs.edit().putBoolean(Prefs.HINTS, it).apply()
        }

        // ---- psaní ----
        section("Psaní")
        toggle("Automatické opravy", Prefs.autocorrect(this)) {
            prefs.edit().putBoolean(Prefs.AUTOCORRECT, it).apply()
        }
        note(
            "Překlep se při mezerníku nebo interpunkci opraví na zvýrazněný návrh " +
                "(třeba dekuji → děkuji). Backspace hned po opravě vrátí, co jsi napsal."
        )

        // ---- naučená slova ----
        section("Naučená slova")
        toggle("Učit se nová slova", Prefs.learn(this)) {
            prefs.edit().putBoolean(Prefs.LEARN, it).apply()
        }
        note(
            "Slovo, které není ve slovníku, se začne nabízet, když ho napíšeš dvakrát, " +
                "nebo hned, když v liště návrhů klepneš na přesně napsané slovo. " +
                "Podržením návrhu naučené slovo zapomeneš. Nic se neposílá mimo telefon."
        )
        learnedButton = button("Naučená slova") { showLearned() }
        button("Zapomenout všechna naučená slova") {
            AlertDialog.Builder(this)
                .setMessage("Opravdu zapomenout všechna naučená slova?")
                .setPositiveButton("Zapomenout") { _, _ ->
                    userWords.clear()
                    refreshLearned()
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }

        // ---- doplňky ----
        section("Doplňky (volitelné)")
        button("Přidat slovník (.dict)") { pick(reqDict) }
        // knihovna .so se nemaže: bez ní nefungují návrhy a znovu přidat už nejde
        button("Smazat přidané slovníky") {
            deleteAll(dictDir)
            refresh()
        }
        button("Otestovat knihovnu") {
            AlertDialog.Builder(this)
                .setMessage(GestureLib.load(this))
                .setPositiveButton("OK", null)
                .show()
        }
        button("Otestovat návrhy") {
            AlertDialog.Builder(this)
                .setMessage(suggester.selfTest())
                .setPositiveButton("OK", null)
                .show()
        }
        status = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        root.addView(status)

        val scroll = ScrollView(this).apply { addView(root) }
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

    // ---- stavebnice obrazovky ----

    private fun params(topDp: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun heading(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = params(8)
        })
    }

    private fun section(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = params(28)
        })
    }

    private fun note(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.7f
            layoutParams = params(4)
        })
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            layoutParams = params(4)
            setOnClickListener { onClick() }
            root.addView(this)
        }

    private fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val sw = Switch(this).apply {
            this.text = text
            textSize = 16f
            isChecked = checked
            layoutParams = params(8)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        root.addView(sw)
        return sw
    }

    /** Popisek s hodnotou a posuvník od [from] do [to] v krocích [step]. Vrací obal obou. */
    private fun slider(
        label: String,
        from: Int,
        to: Int,
        step: Int,
        value: Int,
        unit: String,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = params(8)
        }
        val caption = TextView(this).apply { textSize = 15f }
        fun show(v: Int) {
            caption.text = "$label: $v $unit"
        }
        show(value)
        val steps = (to - from) / step
        val bar = SeekBar(this)
        bar.max = steps
        bar.progress = ((value - from) / step).coerceIn(0, steps)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val v = from + p * step
                show(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        box.addView(caption)
        box.addView(bar)
        root.addView(box)
        return box
    }

    private fun themeChooser() {
        root.addView(TextView(this).apply {
            text = "Motiv"
            textSize = 15f
            layoutParams = params(8)
        })
        val options = listOf(
            Prefs.THEME_SYSTEM to "Podle systému",
            Prefs.THEME_DARK to "Tmavý",
            Prefs.THEME_LIGHT to "Světlý",
            Prefs.THEME_BLACK to "Černý (OLED)"
        )
        val current = Prefs.theme(this)
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = params(0)
        }
        options.forEachIndexed { i, (value, text) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                this.text = text
                tag = value
            }
            group.addView(rb)
            if (value == current || (i == 0 && options.none { it.first == current })) {
                group.check(rb.id)
            }
        }
        group.setOnCheckedChangeListener { g, id ->
            val value = g.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            prefs.edit().putString(Prefs.THEME, value).apply()
        }
        root.addView(group)
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

    // ---- doplňky ----

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
        when (requestCode) {
            reqDict -> import(uri, dictDir, ".dict")
        }
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
                if (extension == ".so") target.setReadOnly()
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
}
