package cz.pekey

import android.content.Context
import android.graphics.Paint
import java.text.Normalizer

/**
 * Emoji po kategoriích, naposledy použité a hledání (anglické názvy z Unicode + česká klíčová slova).
 * Vše offline, naposledy použité se ukládají jen v telefonu.
 */
object EmojiData {

    class Category(val icon: String, val name: String, val items: List<String>)

    private fun range(from: Int, to: Int): List<String> =
        (from..to).map { String(Character.toChars(it)) }

    private fun list(s: String): List<String> = s.split(" ").filter { it.isNotEmpty() }

    private fun flag(code: String): String =
        code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    /** Kategorie v pořadí lišty. Znaky, které telefon neumí vykreslit, se vynechají. */
    fun categories(paint: Paint): List<Category> {
        fun ok(e: String) = paint.hasGlyph(e)
        fun cat(icon: String, name: String, items: List<String>) =
            Category(icon, name, items.filter(::ok).distinct())
        return listOf(
            cat(
                "😀", "Smajlíci",
                range(0x1F600, 0x1F637) + range(0x1F641, 0x1F644) + range(0x1F910, 0x1F915) +
                    range(0x1F917, 0x1F92F) + range(0x1F970, 0x1F976) + list("🥸 🥺 🧐 🫠 🫡 🫢 🫣 🫤 🥹 ☺️ ☹️") +
                    range(0x1F648, 0x1F64A) + list("💩 👻 💀 ☠️ 👽 🤖 🎃") + range(0x1F638, 0x1F640)
            ),
            cat(
                "👋", "Lidé a gesta",
                range(0x1F446, 0x1F450) + list("✋ ✌️ ☝️ ✊ ✍️ 🖐️ 🖖 🖕 🤌 🤏 🫰 🫵 🫶 🫱 🫲") +
                    range(0x1F918, 0x1F91F) + range(0x1F64B, 0x1F64F) + list("💪 🦵 🦶 👂 👃 🧠 👀 👁️ 👅 👄 💋") +
                    range(0x1F466, 0x1F469) + range(0x1F474, 0x1F478) + list("🧑 🧒 🧓 🧔 👱 👮 👷 💂 🕵️ 👼 🤶 🎅 🦸 🦹 🧙 🧚 🧛 🧜 🧝") +
                    list("💁 🙅 🙆 🤦 🤷 💆 💇 🚶 🏃 💃 🕺 👯 🧘 🛀 🛌 👫 👬 👭 💏 💑 👪")
            ),
            cat(
                "🐶", "Zvířata a příroda",
                range(0x1F400, 0x1F43F) + range(0x1F980, 0x1F9AE) + range(0x1F330, 0x1F344) +
                    list("💐 🌸 💮 🏵️ 🌹 🥀 🍀 🍁 🍂 🍃 🌍 🌎 🌏 🌙 🌚 🌛 🌜 🌝 🌞 ⭐ 🌟 🌠 ☀️ ⛅ ☁️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 🌪️ 🌫️ 🌈 ☔ ⚡ 🔥 💧 🌊")
            ),
            cat(
                "🍔", "Jídlo a pití",
                range(0x1F345, 0x1F37F) + range(0x1F950, 0x1F96F) + range(0x1F9C0, 0x1F9CB) + list("☕ 🫖 🫐 🫒 🫓 🫔 🫕")
            ),
            cat(
                "⚽", "Aktivity",
                list("⚽ ⚾ 🥎 🏀 🏐 🏈 🏉 🎾 🥏 🎳 🏏 🏑 🏒 🥍 🏓 🏸 🥊 🥋 🥅 ⛳ ⛸️ 🎣 🤿 🎽 🎿 🛷 🥌 🎯 🪀 🪁 🎱 🔮 🎮 🕹️ 🎰 🎲 🧩 ♟️ 🎭 🎨 🧵 🧶 🎼 🎤 🎧 🎷 🎸 🎹 🎺 🎻 🥁 🎬 🏆 🥇 🥈 🥉 🏅 🎖️ 🎗️ 🎟️ 🎫 🎪 🤹 🚴 🚵 🏊 🏄 🚣 🧗 🏋️ ⛹️ 🤸 🤺 🏇 ⛷️ 🏂 🏌️") +
                    range(0x1F380, 0x1F393)
            ),
            cat(
                "🚗", "Cestování a místa",
                range(0x1F680, 0x1F6C5) + list("⛽ 🛑 🚧 ⚓ ⛵ 🛶 ✈️ 🛩️ 🛫 🛬 🪂 💺 🛰️ 🛸 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🛕 🕋 ⛩️ 🌁 🌃 🏙️ 🌄 🌅 🌆 🌇 🌉 🎑")
            ),
            cat(
                "💡", "Předměty",
                range(0x1F4A1, 0x1F4FF).filter { it != "💯" } + list("⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 💿 📀 🎥 📷 📸 📹 🔋 🔌 💡 🔦 🕯️ 🧯 🛢️ 💸 💵 💴 💶 💷 🪙 💰 💳 💎 ⚖️ 🧰 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ 🧱 ⛓️ 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🔑 🗝️ 🔒 🔓 🚪 🪑 🚽 🚿 🛁 🧴 🧷 🧹 🧺 🧻 🧼 🧽 🛒 🎁 🎈 🎀 🎊 🎉 ✉️ 📦 🏷️ 📝 ✏️ ✒️ 🖊️ 🖋️ 📌 📍 ✂️ 🗑️ 🔍 🔎 💊 💉 🩹 🩺 🧬 🦠 🧪 🌡️")
            ),
            cat(
                "❤️", "Symboly",
                list("❤️ 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💌 💯 💢 💥 💫 💦 💨 🕳️ 💬 💭 🗯️ 💤 ✨ 🎶 🎵 ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ ⚠️ 🚫 ⛔ 📵 🔞 ☢️ ☣️ ⬆️ ↗️ ➡️ ↘️ ⬇️ ↙️ ⬅️ ↖️ ↕️ ↔️ 🔄 🔃 🔙 🔚 🔛 🔜 🔝 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ ☯️ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⛎ 🔀 🔁 🔂 ▶️ ⏩ ⏭️ ⏯️ ◀️ ⏪ ⏮️ 🔼 ⏫ 🔽 ⏬ ⏸️ ⏹️ ⏺️ 🎦 🔅 🔆 📶 📳 📴 ♻️ 🔱 📛 🔰 ⭕ ✳️ ✴️ ❇️ ©️ ®️ ™️ #️⃣ *️⃣ 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🆗 🆒 🆕 🆓 🆙 🆘 🔴 🟠 🟡 🟢 🔵 🟣 🟤 ⚫ ⚪ 🟥 🟧 🟨 🟩 🟦 🟪 🟫 ⬛ ⬜ 🔶 🔷 🔸 🔹 🔺 🔻 💠 🔘 🏁 🚩 🎌 🏴 🏳️")
            ),
            cat(
                "🏳️", "Vlajky",
                list("cz sk eu de at pl hu ua gb ie us ca fr it es pt nl be lu ch dk se no fi is ee lv lt ro bg hr si rs ba me mk al gr cy mt tr il jp cn kr in th vn au nz br ar mx")
                    .map(::flag)
            )
        )
    }

    // ---- naposledy použité ----

    private const val PREFS = "pekey_emoji"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 32

    fun recent(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, "")!!.split("\n").filter { it.isNotEmpty() }

    fun addRecent(ctx: Context, emoji: String) {
        val list = (listOf(emoji) + recent(ctx).filter { it != emoji }).take(MAX_RECENT)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT, list.joinToString("\n")).apply()
    }

    // ---- hledání ----

    /** Česká klíčová slova k nejčastějším emoji (vlastní výběr). */
    private val czech: Map<String, String> = mapOf(
        "😀" to "smich usmev radost", "😂" to "smich slzy smichu", "🤣" to "smich valim se",
        "😊" to "usmev stesti", "🙂" to "usmev", "😉" to "mrk mrknuti", "😍" to "laska zamilovany srdicka",
        "🥰" to "laska zamilovany", "😘" to "pusa polibek", "😋" to "mnam dobrota jidlo", "😎" to "pohoda bryle cool",
        "🤔" to "premysleni hmm", "😐" to "neutralni", "🙄" to "obraceni oci", "😴" to "spanek spim unava",
        "😢" to "smutek placu slza", "😭" to "plac breceni smutek", "😡" to "vztek zlost", "🤬" to "nadavky vztek",
        "😱" to "hruza strach", "😳" to "ostych zcervenani", "🥺" to "prosim smutne oci", "🤯" to "vybuch hlavy sok",
        "🥳" to "oslava party", "😇" to "andel nevinny", "🤗" to "objeti", "🤢" to "zle nevolnost", "🤮" to "zvraceni",
        "🤒" to "nemoc horecka", "😷" to "rousko nemoc", "🥶" to "zima mraz", "🥵" to "horko vedro",
        "👍" to "palec nahoru ok souhlas", "👎" to "palec dolu nesouhlas", "👌" to "ok v poradku",
        "👋" to "mavani ahoj cau", "👏" to "potlesk tleskani bravo", "🙏" to "prosim diky modlitba",
        "💪" to "sila sval", "🤝" to "podani ruky dohoda", "✌️" to "mir vitezstvi", "🤞" to "drzim palce stesti",
        "❤️" to "srdce laska", "💔" to "zlomene srdce", "💕" to "srdicka laska", "🔥" to "ohen hori super",
        "✨" to "jiskry trpyt", "⭐" to "hvezda", "🎉" to "oslava party gratulace", "🎁" to "darek",
        "🎂" to "dort narozeniny", "🏆" to "pohar vitez", "🍺" to "pivo", "🍻" to "pivo nazdravi",
        "🍷" to "vino", "☕" to "kafe kava", "🍕" to "pizza", "🍔" to "burger hamburger", "🍟" to "hranolky",
        "🍰" to "dort zakusek", "🍎" to "jablko", "🍌" to "banan", "🍓" to "jahoda", "🐶" to "pes pejsek",
        "🐱" to "kocka kocicka", "🐭" to "mys", "🐻" to "medved", "🦊" to "liska", "🐷" to "prase",
        "🐴" to "kun", "🐔" to "slepice", "🐟" to "ryba", "🌞" to "slunce", "☀️" to "slunce slunecno",
        "🌈" to "duha", "☔" to "dest destnik", "❄️" to "snih vlocka zima", "⛅" to "oblacno",
        "🚗" to "auto", "🚲" to "kolo", "✈️" to "letadlo", "🏠" to "dum domov", "💤" to "spanek",
        "💯" to "sto procent", "✅" to "hotovo ano", "❌" to "ne krizek spatne", "⚠️" to "pozor varovani",
        "💩" to "hovinko", "👀" to "oci divam", "🤷" to "nevim pokrceni", "🤦" to "facepalm",
        "😅" to "uf pot nervozni", "😬" to "trapne", "😏" to "usklebek", "🤪" to "blazen silenec",
        "⚽" to "fotbal mic", "🏒" to "hokej", "🎮" to "hra hry", "🎵" to "hudba", "📱" to "mobil telefon",
        "💻" to "pocitac notebook", "💰" to "penize", "💸" to "penize utraceni", "🔑" to "klic",
        "🇨🇿" to "cesko cesko vlajka", "🇸🇰" to "slovensko vlajka"
    )

    private fun plain(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun englishName(e: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < e.length) {
            val cp = e.codePointAt(i)
            if (cp != 0xFE0F && cp != 0x200D) {
                val n = try {
                    android.icu.lang.UCharacter.getName(cp)
                } catch (t: Throwable) {
                    null
                }
                if (n != null) sb.append(n).append(' ')
            }
            i += Character.charCount(cp)
        }
        return sb.toString().lowercase()
    }

    private val indexCache = HashMap<String, List<String>>()

    private fun words(e: String): List<String> =
        indexCache.getOrPut(e) {
            (plain(czech[e] ?: "") + " " + englishName(e)).split(' ', '-').filter { it.isNotEmpty() }
        }

    /** Emoji, jejichž české nebo anglické slovo začíná na hledaný text (bez diakritiky). */
    fun search(query: String, cats: List<Category>, limit: Int = 40): List<String> {
        val q = plain(query.trim())
        if (q.isEmpty()) return emptyList()
        val parts = q.split(' ').filter { it.isNotEmpty() }
        val all = cats.flatMap { it.items }.distinct()
        // česká slova první
        val (cz, rest) = all.filter { e -> parts.all { p -> words(e).any { it.startsWith(p) } } }
            .partition { e -> czech[e]?.let { c -> parts.all { p -> plain(c).split(' ').any { it.startsWith(p) } } } == true }
        return (cz + rest).take(limit)
    }
}
