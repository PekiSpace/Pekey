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
import java.io.File
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
        TYPING("Psaní", "Jazyk, předpověď, opravy, zkratky"),
        WORDS("Naučená slova", "Učení, seznam, zapomenutí"),
        DATA("Slovníky a záloha", "Další jazyky, export a import tvých dat")
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
        // nastavení se neposílá systémovým službám (Content Capture, automatické vyplňování)
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.importantForContentCapture = android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        scroll.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContentView(scroll)
        showMain()
        // klávesnice poslala žádost o mikrofon
        if (intent?.getBooleanExtra(EXTRA_ASK_MIC, false) == true) askMic()
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
            text = "Pekey ${version ?: ""}\n© 2026\n@pekispace".replace(" \n", "\n")
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
            Section.DATA -> buildData(c)
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
        toggle(c, "Angličtina na QWERTY", Prefs.enQwerty(this)) {
            prefs.edit().putBoolean(Prefs.EN_QWERTY, it).apply()
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
        button(c, "Jazyky klávesnice") { openLanguages() }
        toggle(c, "Jazyk podle aplikace", Prefs.appLang(this)) {
            prefs.edit().putBoolean(Prefs.APP_LANG, it).apply()
        }
        toggle(c, "Předpověď dalšího slova", Prefs.predict(this)) {
            prefs.edit().putBoolean(Prefs.PREDICT, it).apply()
        }
        toggle(c, "Automatické opravy", Prefs.autocorrect(this)) {
            prefs.edit().putBoolean(Prefs.AUTOCORRECT, it).apply()
        }
        button(c, "Textové zkratky") { showShortcuts() }
        chips(
            c, "Historie schránky se maže po",
            listOf("10" to "10 min", "60" to "1 hodině", "1440" to "1 dni", "0" to "restartu"),
            Prefs.clipKeep(this).toString()
        ) { prefs.edit().putString(Prefs.CLIP_KEEP, it).apply() }
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

    /** Otevře v Androidu výběr jazyků přímo pro Pekey. */
    private fun openLanguages() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val me = imm.inputMethodList.firstOrNull { it.packageName == packageName }
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)
            .putExtra(Settings.EXTRA_INPUT_METHOD_ID, me?.id)
            .putExtra(Intent.EXTRA_TITLE, "Jazyky Pekey")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    // ---- slovníky a záloha ----

    private fun buildData(c: LinearLayout) {
        note(c, "Přibalené slovníky: čeština a angličtina. Pro další jazyk nahraj slovník " +
            "ve formátu AOSP (.dict nebo .bin), třeba main_de.dict. Osobní slovník z Gboardu " +
            "(PersonalDictionary.zip nebo dictionary.txt) přidá tvoje slova a zkratky.")
        button(c, "Přidat slovník (.dict, .bin, .txt, Gboard .zip)", primary = true) {
            pickFile(REQ_DICT, arrayOf("*/*"))
        }
        val added = File(filesDir, "dicts").listFiles { f -> f.isFile && f.name.endsWith(".dict") }
            ?.sortedBy { it.name }.orEmpty()
        for (f in added) {
            val lang = f.name.removePrefix("main_").removeSuffix(".dict")
            button(c, "Slovník: ${langName(lang)} (${f.length() / 1_000_000} MB) – smazat") {
                AlertDialog.Builder(this)
                    .setMessage("Smazat slovník ${langName(lang)}?")
                    .setPositiveButton("Smazat") { _, _ ->
                        f.delete()
                        showSection(Section.DATA)
                    }
                    .setNegativeButton("Zrušit", null)
                    .show()
            }
        }
        note(c, "Záloha: naučená slova, co často píšeš, textové zkratky, připnutá schránka " +
            "a naposledy použité emoji. Soubor si ulož a v novém telefonu ho nahraj.")
        note(c, "Hlasové zadávání: model Whisper (soubor .bin, doporučený ggml-small-q5_1.bin, 190 MB). " +
            "Přepis běží jen v telefonu, nic se neposílá.")
        val model = Voice.modelFile(this)
        if (Voice.hasModel(this)) {
            button(c, "Hlasový model (${model.length() / 1_000_000} MB) – smazat") {
                AlertDialog.Builder(this)
                    .setMessage("Smazat hlasový model?")
                    .setPositiveButton("Smazat") { _, _ ->
                        Voice.release()
                        model.delete()
                        showSection(Section.DATA)
                    }
                    .setNegativeButton("Zrušit", null)
                    .show()
            }
        } else {
            button(c, "Přidat hlasový model (.bin)", primary = true) { pickFile(REQ_VOICE, arrayOf("*/*")) }
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            button(c, "Povolit mikrofon pro hlasové zadávání") { askMic() }
        }
        button(c, "Exportovat moje data") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, "pekey-zaloha.pekey")
            startActivityForResult(i, REQ_EXPORT)
        }
        button(c, "Importovat moje data") { pickFile(REQ_IMPORT, arrayOf("*/*")) }
    }

    private fun pickFile(req: Int, types: Array<String>) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, types)
        startActivityForResult(i, req)
    }

    private fun langName(code: String): String = when (code.substringBefore('_')) {
        "cs" -> "čeština"; "en" -> "angličtina"; "de" -> "němčina"; "fr" -> "francouzština"
        "it" -> "italština"; "pl" -> "polština"; "uk" -> "ukrajinština"; "sk" -> "slovenština"
        else -> code
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_DICT -> importDict(uri)
            REQ_VOICE -> importVoiceModel(uri)
            REQ_EXPORT -> exportData(uri)
            REQ_IMPORT -> importData(uri)
        }
    }

    /** Slovník AOSP: ověří hlavičku, z ní zjistí jazyk a uloží jako main_<jazyk>.dict. */
    private fun importDict(uri: android.net.Uri) {
        val tmp = File(cacheDir, "import.dict")
        try {
            contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val head = ByteArray(minOf(tmp.length(), 4096L).toInt())
            tmp.inputStream().use { it.read(head) }
            val magic = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
                ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            if (head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                // Gboard: PersonalDictionary.zip s dictionary.txt uvnitř
                importWordList(unzipText(tmp) ?: run { toast("V ZIPu není textový slovník"); return })
                return
            }
            if (head.size < 8 || magic != DICT_MAGIC) {
                // textový seznam slov (Gboard dictionary.txt nebo slovo na řádek)
                importWordList(tmp.readText())
                return
            }
            val text = String(head, Charsets.ISO_8859_1)
            val lang = Regex("main:([a-z]{2,3})").find(text)?.groupValues?.get(1)
            if (lang == null) {
                toast("Ve slovníku chybí jazyk (main:xx)")
                return
            }
            val dir = File(filesDir, "dicts").apply { mkdirs() }
            val target = File(dir, "main_$lang.dict")
            target.delete()
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
            toast("Slovník přidán: ${langName(lang)}")
            showSection(Section.DATA)
        } catch (e: Exception) {
            toast("Slovník se nepodařilo nahrát: ${e.message}")
        } finally {
            tmp.delete()
        }
    }

    private fun importVoiceModel(uri: android.net.Uri) {
        val target = Voice.modelFile(this)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        toast("Nahrávám model, chvíli to trvá…")
        Thread {
            val msg = try {
                contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val head = ByteArray(4)
                tmp.inputStream().use { it.read(head) }
                if (!Voice.isWhisperModel(head)) {
                    tmp.delete()
                    "Tohle není model Whisper (ggml .bin)"
                } else {
                    Voice.release()
                    target.delete()
                    tmp.renameTo(target)
                    "Hlasový model přidán (${target.length() / 1_000_000} MB)"
                }
            } catch (e: Exception) {
                tmp.delete()
                "Model se nepodařilo nahrát: ${e.message}"
            }
            runOnUiThread {
                toast(msg)
                if (section == Section.DATA) showSection(Section.DATA)
            }
        }.start()
    }

    private fun askMic() {
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val ok = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        toast(if (ok) "Mikrofon povolen – klepni v klávesnici na 🎤" else "Bez mikrofonu hlasové zadávání nepůjde")
        if (section == Section.DATA) showSection(Section.DATA)
    }

    /** První .txt soubor ze ZIPu (export osobního slovníku Gboardu). */
    private fun unzipText(f: File): String? =
        java.util.zip.ZipInputStream(f.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { !it.isDirectory && it.name.endsWith(".txt", ignoreCase = true) }
                ?.let { zip.readBytes().toString(Charsets.UTF_8) }
        }

    /**
     * Osobní slovník jako text. Formát Gboardu: první řádek „# Gboard Dictionary version:1“,
     * pak řádky oddělené tabulátorem: zkratka, slovo nebo výraz, jazyk (např. cs-CZ).
     * Jinak prostý seznam: slovo na řádek (volitelně tabulátor a jazyk).
     * Slova se naučí, položky se zkratkou se přidají do textových zkratek.
     */
    private fun importWordList(text: String) {
        val fallbackLang = (resources.configuration.locales[0]?.language ?: "cs")
            .let { if (it == "cs" || it == "en") it else "cs" }
        var words = 0
        var shortcuts = 0
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split('\t')
            val (shortcut, value, locale) = when (p.size) {
                1 -> Triple("", p[0], "")
                2 -> Triple("", p[0], p[1])
                else -> Triple(p[0], p[1], p[2])
            }
            val v = value.trim()
            if (v.isEmpty()) continue
            val lang = locale.trim().lowercase().substringBefore('-').substringBefore('_').ifEmpty { fallbackLang }
            if (shortcut.isNotBlank() && !shortcut.contains(' ')) {
                Shortcuts.put(this, shortcut.trim(), v)
                shortcuts++
            } else if (!v.contains(' ')) {
                userWords.learnNow(v, lang)
                words++
            }
        }
        refreshLearned()
        toast(
            if (words + shortcuts == 0) "V souboru jsem nenašel žádná slova"
            else "Nahráno slov: $words" + if (shortcuts > 0) ", zkratek: $shortcuts" else ""
        )
    }

    private fun exportData(uri: android.net.Uri) {
        try {
            val json = org.json.JSONObject()
            json.put("format", "pekey-backup-1")
            json.put("words", userWords.exportText())
            json.put("shortcuts", org.json.JSONObject(Shortcuts.all(this)))
            val clipPrefs = getSharedPreferences("pekey_clipboard", MODE_PRIVATE)
            json.put("pinned", clipPrefs.getString("pinned", "") ?: "")
            val emojiPrefs = getSharedPreferences("pekey_emoji", MODE_PRIVATE)
            json.put("emoji", emojiPrefs.getString("recent", "") ?: "")
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toString().toByteArray()) }
            toast("Data uložena")
        } catch (e: Exception) {
            toast("Export se nepodařil: ${e.message}")
        }
    }

    private fun importData(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = org.json.JSONObject(text)
            if (json.optString("format") != "pekey-backup-1") {
                toast("Tohle není záloha Pekey")
                return
            }
            val words = userWords.importText(json.optString("words"))
            json.optJSONObject("shortcuts")?.let { sc ->
                sc.keys().forEach { k -> Shortcuts.put(this, k, sc.getString(k)) }
            }
            val pinned = json.optString("pinned")
            if (pinned.isNotEmpty()) {
                val prefs = getSharedPreferences("pekey_clipboard", MODE_PRIVATE)
                val old = prefs.getString("pinned", "") ?: ""
                val sep = "\u0000"
                val merged = (old.split(sep) + pinned.split(sep)).filter { it.isNotEmpty() }.distinct()
                prefs.edit().putString("pinned", merged.joinToString(sep)).apply()
            }
            val emoji = json.optString("emoji")
            if (emoji.isNotEmpty()) {
                getSharedPreferences("pekey_emoji", MODE_PRIVATE).edit().putString("recent", emoji).apply()
            }
            refreshLearned()
            toast("Data nahrána, nových slov: $words")
        } catch (e: Exception) {
            toast("Import se nepodařil: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- textové zkratky ----

    private fun showShortcuts() {
        val all = Shortcuts.all(this)
        val keys = all.keys.toList()
        val lines = keys.map { k ->
            val t = all.getValue(k).replace('\n', ' ')
            "$k  →  " + if (t.length > 40) t.take(39) + "…" else t
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Textové zkratky")
            .setPositiveButton("Přidat") { _, _ -> editShortcut() }
            .setNegativeButton("Zavřít", null)
        if (keys.isEmpty()) {
            b.setMessage(
                "Napíšeš zkratku, třeba „adr“, a v návrzích se nabídne celý text. Klepnutím na zkratku ji upravíš.\n\n" +
                    "Do textu můžeš dát značky: %date%, %date:short%, %tomorrow%, %time%, %day%, %greeting%, " +
                    "%clipboard%, %clipboard:singleline%, %bullets_3%, %list_3% a %cursor% (kam skočí kurzor)."
            )
        } else {
            b.setItems(lines.toTypedArray()) { _, i -> editShortcut(keys[i], all.getValue(keys[i])) }
        }
        b.show()
    }

    private fun editShortcut(key: String? = null, text: String? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val keyField = EditText(this).apply {
            hint = "Zkratka (např. adr)"
            setSingleLine()
            setText(key ?: "")
        }
        val textField = EditText(this).apply {
            hint = "Celý text (můžeš použít %date%, %clipboard%, %cursor%…)"
            minLines = 2
            setText(text ?: "")
        }
        box.addView(keyField)
        box.addView(textField)
        val b = AlertDialog.Builder(this)
            .setTitle(if (key == null) "Nová zkratka" else "Upravit zkratku")
            .setView(box)
            .setPositiveButton("Uložit") { _, _ ->
                val k = keyField.text.toString().trim()
                val t = textField.text.toString()
                if (k.isEmpty() || k.contains(' ') || t.isEmpty()) {
                    Toast.makeText(this, "Zkratka musí být jedno slovo a text nesmí být prázdný", Toast.LENGTH_LONG).show()
                } else {
                    if (key != null && key != k.lowercase()) Shortcuts.remove(this, key)
                    Shortcuts.put(this, k, t)
                }
                showShortcuts()
            }
            .setNegativeButton("Zrušit") { _, _ -> showShortcuts() }
        if (key != null) {
            b.setNeutralButton("Smazat") { _, _ ->
                Shortcuts.remove(this, key)
                showShortcuts()
            }
        }
        b.show()
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

    companion object {
        /** Klávesnice žádá o oprávnění k mikrofonu. */
        const val EXTRA_ASK_MIC = "cz.pekey.ASK_MIC"

        private const val REQ_DICT = 41
        private const val REQ_EXPORT = 42
        private const val REQ_IMPORT = 43
        private const val REQ_VOICE = 44
        private const val REQ_MIC = 45
        private const val DICT_MAGIC = 0x9BC13AFE.toInt()

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
