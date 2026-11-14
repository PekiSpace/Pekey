package cz.pekey

import android.content.Context
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils
import java.io.File

object GestureLib {
    private var loaded = false

    /** Načte přidanou knihovnu z úložiště aplikace, jinak tu přibalenou v APK. */
    fun load(ctx: Context): String {
        return try {
            if (!loaded) {
                val added = File(ctx.filesDir, "lib").listFiles()?.firstOrNull { it.name.endsWith(".so") }
                if (added != null) {
                    added.setReadOnly()
                    System.load(added.absolutePath)
                } else {
                    System.loadLibrary("jni_latinimegoogle")
                }
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