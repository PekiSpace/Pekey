package cz.pekey

import android.content.Context

/**
 * Textové zkratky: napíšeš zkratku (např. „adr“) a v návrzích se nabídne celý text.
 * Ukládají se jen v telefonu, v soukromých datech klávesnice.
 */
object Shortcuts {

    private const val PREFS = "pekey_shortcuts"

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
}
