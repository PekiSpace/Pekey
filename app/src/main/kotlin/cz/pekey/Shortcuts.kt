/*
 * Textové zkratky. Značky (%date%, %clipboard%…) a jejich úpravy převzaté z Blackline Keyboard
 * (TextExpanderUtils), zkrácené pro Pekey.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package cz.pekey

import android.content.ClipboardManager
import android.content.Context
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Textové zkratky: napíšeš zkratku (např. „adr“) a v návrzích se nabídne celý text.
 * Ukládají se jen v telefonu, v soukromých datech klávesnice.
 *
 * Značky v textu: %date%, %date:short%, %date:format(d. M. yyyy)%, %tomorrow%, %time%, %time12%,
 * %day%, %month%, %year%, %week%, %greeting%, %battery%, %clipboard% (i s úpravami
 * :singleline :trim :lower :upper :title :slug :snake :camel :unquote :nourl), %bullets_N%, %list_N%
 * a %cursor% (kam se po vložení postaví kurzor).
 */
object Shortcuts {

    private const val PREFS = "pekey_shortcuts"
    const val CURSOR = "%cursor%"

    /** Zkratka (malými písmeny) → text. */
    fun all(ctx: Context): Map<String, String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            .sortedBy { it.first }
            .toMap()

    fun expand(ctx: Context, typed: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(typed.lowercase(), null)

    fun put(ctx: Context, shortcut: String, text: String) {
        val key = shortcut.trim().lowercase()
        if (key.isEmpty() || text.isEmpty()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, text).apply()
    }

    fun remove(ctx: Context, shortcut: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(shortcut).apply()
    }

    /** Jak se zkratka ukáže v liště návrhů (značky jen naznačené). */
    fun preview(template: String): String =
        template.replace(CURSOR, "").replace('\n', ' ').trim()

    // ---- značky ----

    private val DATE = Regex("%date(?::([a-zA-Z0-9_():, ./-]+))?%")
    private val TOMORROW = Regex("%tomorrow(?::([a-zA-Z0-9_():, ./-]+))?%")
    private val CLIPBOARD = Regex("%clipboard(?::([a-zA-Z0-9_():, -]+))?%")
    private val BULLETS = Regex("%bullets(?:_(\\d+))?%")
    private val LIST = Regex("%list(?:_(\\d+))?%")

    private val NEWLINES = Regex("[\\r\\n]+")
    private val WHITESPACE = Regex("\\s+")
    private val NON_ALNUM_DASH = Regex("[^\\w\\s-]")
    private val NON_ALNUM = Regex("[^\\w\\s]")
    private val SPACE_UNDERSCORE = Regex("[\\s_]+")
    private val SPACE_DASH = Regex("[\\s-]+")
    private val SPACE_UNDERSCORE_DASH = Regex("[\\s_-]+")
    private val MULTI_DASH = Regex("-+")
    private val MULTI_UNDERSCORE = Regex("_+")
    private val URL = Regex("https?://\\S+")
    private val MULTI_SPACE = Regex("\\s{2,}")

    /** Rozbalí značky v textu zkratky. %cursor% nechá na místě (řeší ho vkládání). */
    fun render(ctx: Context, template: String): String {
        var r = template
        val loc = Locale.getDefault()
        val now = Date()
        if (r.contains("%date")) r = DATE.replace(r) { date(now, it.groups[1]?.value) }
        if (r.contains("%tomorrow")) {
            val t = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time
            r = TOMORROW.replace(r) { date(t, it.groups[1]?.value) }
        }
        if (r.contains("%clipboard")) {
            val clip = clipboardText(ctx)
            r = CLIPBOARD.replace(r) { m -> m.groups[1]?.value?.let { modify(clip, it) } ?: clip }
        }
        r = r.replace("%time12%", SimpleDateFormat("hh:mm a", loc).format(now))
            .replace("%time%", SimpleDateFormat("HH:mm", loc).format(now))
            .replace("%day%", SimpleDateFormat("EEEE", loc).format(now))
            .replace("%month%", SimpleDateFormat("LLLL", loc).format(now))
            .replace("%year%", SimpleDateFormat("yyyy", loc).format(now))
            .replace("%week%", SimpleDateFormat("w", loc).format(now))
        if (r.contains("%greeting%")) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            r = r.replace(
                "%greeting%",
                when (h) {
                    in 5..9 -> "Dobré ráno"
                    in 10..17 -> "Dobrý den"
                    in 18..21 -> "Dobrý večer"
                    else -> "Dobrou noc"
                }
            )
        }
        if (r.contains("%battery%")) {
            val level = try {
                (ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
                    ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            } catch (_: Exception) {
                -1
            }
            r = r.replace("%battery%", if (level >= 0) "$level %" else "")
        }
        if (r.contains("%bullets")) r = BULLETS.replace(r) { lines(it, "• ") { _ -> "• " } }
        if (r.contains("%list")) r = LIST.replace(r) { lines(it, "1. ") { i -> "$i. " } }
        return r
    }

    private fun lines(m: MatchResult, first: String, prefix: (Int) -> String): String {
        val n = m.groups[1]?.value?.toIntOrNull() ?: 3
        if (n <= 0) return ""
        val sb = StringBuilder(first).append(CURSOR)
        for (i in 2..n) sb.append('\n').append(prefix(i))
        return sb.toString()
    }

    private fun clipboardText(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
        if (t.length > 5000) t.substring(0, 5000) else t
    } catch (_: Exception) {
        ""
    }

    private fun tokens(mods: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        for (ch in mods) {
            if (ch == '(') depth++ else if (ch == ')') depth--
            if (ch == ':' && depth == 0) {
                if (cur.isNotBlank()) out.add(cur.toString().trim())
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    private fun date(d: Date, mods: String?): String {
        val loc = Locale.getDefault()
        if (mods.isNullOrBlank()) return DateFormat.getDateInstance(DateFormat.MEDIUM, loc).format(d)
        var out: String? = null
        val rest = ArrayList<String>()
        for (t in tokens(mods)) {
            when {
                t.startsWith("format(", true) && t.endsWith(")") -> out = runCatching {
                    SimpleDateFormat(t.substring(7, t.length - 1).trim(), loc).format(d)
                }.getOrNull()
                t.equals("short", true) -> out = DateFormat.getDateInstance(DateFormat.SHORT, loc).format(d)
                t.equals("medium", true) -> out = DateFormat.getDateInstance(DateFormat.MEDIUM, loc).format(d)
                t.equals("long", true) -> out = DateFormat.getDateInstance(DateFormat.LONG, loc).format(d)
                t.equals("full", true) -> out = DateFormat.getDateInstance(DateFormat.FULL, loc).format(d)
                t.equals("iso", true) -> out = SimpleDateFormat("yyyy-MM-dd", loc).format(d)
                else -> rest.add(t)
            }
        }
        val s = out ?: DateFormat.getDateInstance(DateFormat.MEDIUM, loc).format(d)
        return if (rest.isEmpty()) s else modify(s, rest.joinToString(":"))
    }

    /** Úpravy textu za dvojtečkou: %clipboard:singleline:lower% apod. */
    fun modify(input: String, mods: String): String {
        val loc = Locale.getDefault()
        var t = input
        for (m in tokens(mods)) {
            t = when (m.lowercase(Locale.US)) {
                "singleline", "oneline" -> t.replace(NEWLINES, " ")
                "trim" -> t.trim()
                "lower" -> t.lowercase(loc)
                "upper" -> t.uppercase(loc)
                "title" -> t.split(WHITESPACE).joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(loc) } }
                "slug", "kebab" -> t.trim().lowercase(loc).replace(NON_ALNUM_DASH, "")
                    .replace(SPACE_UNDERSCORE, "-").replace(MULTI_DASH, "-")
                "snake" -> t.trim().lowercase(loc).replace(NON_ALNUM, "")
                    .replace(SPACE_DASH, "_").replace(MULTI_UNDERSCORE, "_")
                "camel" -> t.trim().split(SPACE_UNDERSCORE_DASH).mapIndexed { i, w ->
                    if (i == 0) w.lowercase(loc) else w.replaceFirstChar { it.titlecase(loc) }
                }.joinToString("")
                "unquote" -> t.trim().let {
                    if (it.length >= 2 && ((it.startsWith('"') && it.endsWith('"')) || (it.startsWith('\'') && it.endsWith('\''))))
                        it.substring(1, it.length - 1) else it
                }
                "nourl" -> t.replace(URL, "").replace(MULTI_SPACE, " ")
                else -> t
            }
        }
        return t
    }
}
