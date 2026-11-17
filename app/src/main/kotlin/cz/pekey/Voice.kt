package cz.pekey

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Hlasové zadávání: nahrávání z mikrofonu a přepis modelem Whisper (whisper.cpp) přímo v telefonu.
 * Model si uživatel nahraje v nastavení (soubor .bin); nic se nikam neposílá.
 */
object Voice {

    private val loaded: Boolean = try {
        System.loadLibrary("pekeyvoice")
        true
    } catch (_: Throwable) {
        // build bez nativní knihovny (např. z CoGo): hlas není k dispozici
        false
    }

    @JvmStatic private external fun nativeInit(path: String): Long
    @JvmStatic private external fun nativeTranscribe(
        ctx: Long, samples: FloatArray, lang: String, threads: Int, prompt: String
    ): String
    @JvmStatic private external fun nativeFree(ctx: Long)

    const val SAMPLE_RATE = 16000
    /** Whisper zpracuje najednou nejvýš 30 s. */
    private const val MAX_SECONDS = 30
    private const val MAGIC_GGML = 0x67676d6c

    fun available(): Boolean = loaded

    fun modelFile(ctx: Context): File = File(File(ctx.filesDir, "voice"), "whisper.bin")

    fun hasModel(ctx: Context): Boolean = modelFile(ctx).let { it.isFile && it.length() > 1_000_000 }

    /** Je to model Whisperu ve formátu ggml? (prvních 4 bajty) */
    fun isWhisperModel(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val v = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8) or
            ((head[2].toInt() and 0xFF) shl 16) or ((head[3].toInt() and 0xFF) shl 24)
        return v == MAGIC_GGML
    }

    // ---- model ----

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var handle = 0L
    private var handlePath: String? = null

    /** Uvolní model (např. po smazání v nastavení nebo při nedostatku paměti). */
    fun release() {
        worker.execute {
            if (handle != 0L) nativeFree(handle)
            handle = 0L
            handlePath = null
        }
    }

    private fun ensureModel(ctx: Context): Boolean {
        val f = modelFile(ctx)
        val key = "${f.path}:${f.length()}:${f.lastModified()}"
        if (handle != 0L && handlePath == key) return true
        if (handle != 0L) nativeFree(handle)
        handle = nativeInit(f.path)
        handlePath = if (handle != 0L) key else null
        return handle != 0L
    }

    // ---- nahrávání ----

    private var record: AudioRecord? = null
    private var recThread: Thread? = null
    @Volatile private var recording = false
    private var buffer = ShortArray(0)
    @Volatile private var length = 0

    val isRecording get() = recording

    /** Začne nahrávat; false = mikrofon nejde otevřít (chybí oprávnění). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2
            )
        } catch (_: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        buffer = ShortArray(SAMPLE_RATE * MAX_SECONDS)
        length = 0
        record = r
        recording = true
        r.startRecording()
        recThread = Thread {
            val chunk = ShortArray(min / 2)
            while (recording) {
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val room = buffer.size - length
                if (room <= 0) {
                    // 30 s: dál se nenahrává, přepíše se, co je
                    main.post { onLimit?.invoke() }
                    break
                }
                val take = minOf(n, room)
                System.arraycopy(chunk, 0, buffer, length, take)
                length += take
            }
        }.also { it.start() }
        return true
    }

    /** Po 30 s nahrávání (služba nahrávání ukončí). */
    var onLimit: (() -> Unit)? = null

    /** Konec nahrávání a přepis; výsledek (i prázdný) přijde v hlavním vlákně. */
    fun stopAndTranscribe(ctx: Context, lang: String, prompt: String, done: (String?) -> Unit) {
        if (!recording) return
        recording = false
        recThread?.join(500)
        record?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        record = null
        val n = length
        val pcm = FloatArray(n) { buffer[it] / 32768f }
        buffer = ShortArray(0)
        if (n < SAMPLE_RATE / 3) {
            main.post { done("") }
            return
        }
        val appCtx = ctx.applicationContext
        worker.execute {
            val text = try {
                if (!ensureModel(appCtx)) null
                else {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                    nativeTranscribe(handle, pcm, lang, threads, prompt).trim()
                }
            } catch (_: Throwable) {
                null
            }
            main.post { done(text) }
        }
    }

    /** Zruší nahrávání bez přepisu. */
    fun cancel() {
        if (!recording) return
        recording = false
        recThread?.join(500)
        record?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        record = null
        buffer = ShortArray(0)
    }
}
