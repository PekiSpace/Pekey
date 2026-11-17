package cz.pekey

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Překlady textů Pekey podle jazyka telefonu. Zdrojem je čeština přímo v kódu; překlady
 * jsou v assets/i18n/<jazyk>.json jako „český text → překlad“. Jazyk bez překladu dostane
 * angličtinu, chybějící text zůstane česky.
 */
object Tr {
    private var lang = ""
    private var map: Map<String, String> = emptyMap()
    private var fallback: Map<String, String> = emptyMap()

    /** Načte překlad pro jazyk telefonu (jen poprvé nebo po změně jazyka). */
    fun init(ctx: Context) {
        val l = Locale.getDefault().language.let { if (it == "nb" || it == "nn") "no" else it }
        if (l == lang) return
        lang = l
        if (l == "cs") {
            map = emptyMap()
            fallback = emptyMap()
            return
        }
        fallback = load(ctx, "en")
        map = if (l == "en") fallback else load(ctx, l).ifEmpty { fallback }
    }

    private fun load(ctx: Context, l: String): Map<String, String> = try {
        val json = ctx.assets.open("i18n/$l.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    fun t(cs: String, vararg args: Any?): String {
        val s = map[cs] ?: fallback[cs] ?: cs
        return if (args.isEmpty()) s else try {
            String.format(s, *args)
        } catch (_: Exception) {
            String.format(cs, *args)
        }
    }
}

/** Přeložený text (čeština je zdroj). */
fun t(cs: String, vararg args: Any?): String = Tr.t(cs, *args)
