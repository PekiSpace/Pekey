package cz.pekey

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibrace a zvuk při stisku klávesy podle nastavení. */
class Feedback(ctx: Context) {

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var vibrateOn = true
    private var effect: VibrationEffect? = null
    private var soundOn = false
    private var volume = 0.5f

    /** Načte aktuální nastavení (volá se při každém otevření klávesnice). */
    fun load(ctx: Context) {
        vibrateOn = Prefs.vibrate(ctx) && vibrator?.hasVibrator() == true
        val strength = Prefs.vibrateStrength(ctx) / 100f
        effect = if (vibrateOn) {
            val ms = (6 + 24 * strength).toLong()
            val amplitude = if (vibrator?.hasAmplitudeControl() == true) {
                (255 * strength).toInt().coerceIn(1, 255)
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            VibrationEffect.createOneShot(ms, amplitude)
        } else {
            null
        }
        soundOn = Prefs.sound(ctx)
        volume = Prefs.soundVolume(ctx) / 100f
    }

    fun press(key: Key) {
        if (vibrateOn) {
            effect?.let { runCatching { vibrator?.vibrate(it) } }
        }
        // ve vibračním a tichém režimu se nehraje
        if (soundOn && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            val fx = when (key) {
                Key.Backspace, Key.DeleteWord -> AudioManager.FX_KEYPRESS_DELETE
                Key.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
                Key.Enter -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, volume)
        }
    }
}
