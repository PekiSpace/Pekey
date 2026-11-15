package cz.pekey

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/** Uživatelská nastavení klávesnice. Sdílí je aplikace (MainActivity) i služba klávesnice. */
object Prefs {

    const val FILE = "pekey_prefs"

    const val THEME = "theme"
    const val HEIGHT = "height"
    const val HINTS = "hints"
    const val VIBRATE = "vibrate"
    const val VIBRATE_STRENGTH = "vibrate_strength"
    const val SOUND = "sound"
    const val SOUND_VOLUME = "sound_volume"
    const val LEARN = "learn_words"

    const val THEME_SYSTEM = "system"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
    const val THEME_BLACK = "black"

    /** Výška kláves v procentech výchozí výšky. */
    const val HEIGHT_MIN = 70
    const val HEIGHT_MAX = 140

    fun of(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun theme(ctx: Context): String = of(ctx).getString(THEME, THEME_SYSTEM) ?: THEME_SYSTEM
    fun height(ctx: Context): Int = of(ctx).getInt(HEIGHT, 100).coerceIn(HEIGHT_MIN, HEIGHT_MAX)
    fun hints(ctx: Context): Boolean = of(ctx).getBoolean(HINTS, true)
    fun vibrate(ctx: Context): Boolean = of(ctx).getBoolean(VIBRATE, true)
    fun vibrateStrength(ctx: Context): Int = of(ctx).getInt(VIBRATE_STRENGTH, 40).coerceIn(5, 100)
    fun sound(ctx: Context): Boolean = of(ctx).getBoolean(SOUND, false)
    fun soundVolume(ctx: Context): Int = of(ctx).getInt(SOUND_VOLUME, 50).coerceIn(5, 100)
    fun learn(ctx: Context): Boolean = of(ctx).getBoolean(LEARN, true)

    /** Barvy podle zvoleného motivu; „podle systému“ se řídí tmavým režimem Androidu. */
    fun palette(ctx: Context): Palette = when (theme(ctx)) {
        THEME_DARK -> Palette.DARK
        THEME_LIGHT -> Palette.LIGHT
        THEME_BLACK -> Palette.BLACK
        else -> {
            val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) Palette.DARK else Palette.LIGHT
        }
    }
}

/** Barvy klávesnice. */
class Palette(
    val background: Int,
    val key: Int,
    val keyDown: Int,
    val accent: Int,
    val popup: Int,
    val text: Int,
    val hint: Int,
    val notice: Int,
    val divider: Int,
    val trail: Int
) {
    companion object {
        val DARK = Palette(
            background = 0xFF1B1B1F.toInt(),
            key = 0xFF2E2F36.toInt(),
            keyDown = 0xFF4A4C57.toInt(),
            accent = 0xFF6F63C9.toInt(),
            popup = 0xFF14141A.toInt(),
            text = 0xFFEDEDF2.toInt(),
            hint = 0xFF9A9BA8.toInt(),
            notice = 0xFF6B6C78.toInt(),
            divider = 0xFF3A3B44.toInt(),
            trail = 0xCC8F84E8.toInt()
        )

        val LIGHT = Palette(
            background = 0xFFE4E5EB.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyDown = 0xFFC6C8D1.toInt(),
            accent = 0xFFB3A9F2.toInt(),
            popup = 0xFFF4F4F8.toInt(),
            text = 0xFF1C1C22.toInt(),
            hint = 0xFF6E6F7A.toInt(),
            notice = 0xFF8A8B96.toInt(),
            divider = 0xFFC9CBD3.toInt(),
            trail = 0xCC6F63C9.toInt()
        )

        val BLACK = Palette(
            background = 0xFF000000.toInt(),
            key = 0xFF1A1A1F.toInt(),
            keyDown = 0xFF3A3B44.toInt(),
            accent = 0xFF6F63C9.toInt(),
            popup = 0xFF0C0C10.toInt(),
            text = 0xFFEDEDF2.toInt(),
            hint = 0xFF8A8B98.toInt(),
            notice = 0xFF5E5F6A.toInt(),
            divider = 0xFF2A2B32.toInt(),
            trail = 0xCC8F84E8.toInt()
        )
    }
}
