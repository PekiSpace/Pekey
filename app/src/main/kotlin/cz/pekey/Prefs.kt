package cz.pekey

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/** Uživatelská nastavení klávesnice. Sdílí je aplikace (MainActivity) i služba klávesnice. */
object Prefs {

    const val FILE = "pekey_prefs"

    const val THEME = "theme"
    const val HEIGHT = "height"
    const val WIDTH = "width"
    const val HINTS = "hints"
    const val LEARN = "learn_words"
    const val AUTOCORRECT = "autocorrect"
    const val PREDICT = "predict"
    const val GESTURE_LIB = "gesture_lib"
    const val SWEET_SPOTS = "sweet_spots"
    const val NUMBER_ROW = "number_row"
    const val ONE_HAND = "one_hand"
    const val BOTTOM_LEFT = "bottom_left"
    const val BOTTOM_RIGHT = "bottom_right"
    const val SHAPE = "key_shape"
    const val FONT = "font"
    const val PREVIEW = "key_preview"

    const val ONE_HAND_OFF = "off"
    const val ONE_HAND_LEFT = "left"
    const val ONE_HAND_RIGHT = "right"

    const val SHAPE_ROUNDED = "rounded"
    const val SHAPE_ROUND = "round"
    const val SHAPE_SQUARE = "square"
    const val SHAPE_FLAT = "flat"

    const val FONT_DEFAULT = "default"
    const val FONT_MEDIUM = "medium"
    const val FONT_CONDENSED = "condensed"

    /** Znaky, které jdou dát na klávesy vedle mezerníku. */
    val BOTTOM_CHOICES = listOf(",", ".", "?", "!", "-", "'", ":", "@", "/")

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

    /** Šířka kláves v procentech šířky obrazovky (100 = celá), klávesy uprostřed. */
    const val WIDTH_MIN = 60
    const val WIDTH_MAX = 100
    fun width(ctx: Context): Int = of(ctx).getInt(WIDTH, 100).coerceIn(WIDTH_MIN, WIDTH_MAX)
    fun hints(ctx: Context): Boolean = of(ctx).getBoolean(HINTS, true)
    fun learn(ctx: Context): Boolean = of(ctx).getBoolean(LEARN, true)
    fun autocorrect(ctx: Context): Boolean = of(ctx).getBoolean(AUTOCORRECT, true)
    fun predict(ctx: Context): Boolean = of(ctx).getBoolean(PREDICT, true)

    /** Gesta rozpoznává knihovna .so (true), nebo vlastní engine (false, výchozí). */
    fun gestureLib(ctx: Context): Boolean = of(ctx).getBoolean(GESTURE_LIB, false)

    /**
     * Jak se knihovně předávají středy kláves (sweet spots): 0 = vůbec (výchozí),
     * 1 = jako AOSP (dosah 0,17 × úhlopříčka klávesy), 2 = široký dosah (0,6 × šířka).
     */
    fun sweetSpots(ctx: Context): Int = of(ctx).getInt(SWEET_SPOTS, 0).coerceIn(0, 2)
    fun numberRow(ctx: Context): Boolean = of(ctx).getBoolean(NUMBER_ROW, true)
    fun oneHand(ctx: Context): String = of(ctx).getString(ONE_HAND, ONE_HAND_OFF) ?: ONE_HAND_OFF
    fun bottomLeft(ctx: Context): String =
        of(ctx).getString(BOTTOM_LEFT, ",")?.takeIf { it in BOTTOM_CHOICES } ?: ","
    fun bottomRight(ctx: Context): String =
        of(ctx).getString(BOTTOM_RIGHT, ".")?.takeIf { it in BOTTOM_CHOICES } ?: "."
    fun shape(ctx: Context): String = of(ctx).getString(SHAPE, SHAPE_ROUNDED) ?: SHAPE_ROUNDED
    fun font(ctx: Context): String = of(ctx).getString(FONT, FONT_DEFAULT) ?: FONT_DEFAULT
    fun preview(ctx: Context): Boolean = of(ctx).getBoolean(PREVIEW, true)

    /** Všechno, co ovlivňuje vzhled a rozložení klávesnice. */
    fun look(ctx: Context) = Look(
        palette = palette(ctx),
        height = height(ctx),
        width = width(ctx),
        hints = hints(ctx),
        numberRow = numberRow(ctx),
        oneHand = oneHand(ctx),
        bottomLeft = bottomLeft(ctx),
        bottomRight = bottomRight(ctx),
        shape = shape(ctx),
        font = font(ctx),
        preview = preview(ctx)
    )

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

/** Vzhled a rozložení klávesnice v jednom balíku (pro PekeyKeyboardView). */
data class Look(
    val palette: Palette,
    val height: Int,
    val width: Int,
    val hints: Boolean,
    val numberRow: Boolean,
    val oneHand: String,
    val bottomLeft: String,
    val bottomRight: String,
    val shape: String,
    val font: String,
    val preview: Boolean
)

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
