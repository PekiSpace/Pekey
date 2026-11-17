package cz.pekey

import android.content.Context
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils

/** Nativní knihovna pro návrhy slov: otevřený AOSP LatinIME sestavený s Pekey. */
object GestureLib {
    private var loaded = false

    fun load(@Suppress("UNUSED_PARAMETER") ctx: Context): String {
        return try {
            if (!loaded) {
                System.loadLibrary("jni_latinime")
                loaded = true
            }
            val score = BinaryDictionaryUtils.calcNormalizedScoreNative(
                intArrayOf(97, 98), intArrayOf(97, 98), 1000
            )
            "Knihovna je načtená a metody odpovídají.\nZkouška: $score"
        } catch (t: Throwable) {
            "Chyba: ${t.javaClass.simpleName}\n${t.message}"
        }
    }
}
