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
import kotlin.math.sqrt

/**
 * Hlasové zadávání: nahrávání z mikrofonu a přepis modelem Whisper (whisper.cpp) přímo v telefonu.
 * Model si uživatel nahraje v nastavení (soubor .bin); nic se nikam neposílá.
 *
 * Přepis běží po větách už během mluvení: po krátké pauze se hotový kus pošle modelu
 * a jeho text přijde hned (onPartial). Po ukončení se přepisuje jen poslední kus.
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

    /** Nejdelší nahrávka najednou. */
    private const val MAX_SECONDS = 300

    /** Whisper zpracuje nejvýš 30 s; delší kus se rozdělí i bez pauzy. */
    private const val MAX_CHUNK = SAMPLE_RATE * 25

    /** Kus se odešle po pauze aspoň takhle dlouhé… */
    private const val PAUSE = SAMPLE_RATE * 6 / 10

    /** …a jen když má aspoň tolik zvuku. */
    private const val MIN_CHUNK = SAMPLE_RATE * 2

    /** Kus s méně řečí se zahodí (Whisper by z ticha vymýšlel text). */
    private const val MIN_SPEECH = SAMPLE_RATE * 3 / 10

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

    // stav dělení na kusy (jen nahrávací vlákno)
    private var chunkStart = 0
    private var chunkSpeech = 0
    private var silenceRun = 0
    private var noiseFloor = 0.0

    // nastavení přepisu pro probíhající nahrávku
    private var appCtx: Context? = null
    private var language = "cs"
    private var basePrompt = ""
    private var lastText = ""
    @Volatile private var failed = false

    val isRecording get() = recording

    /** Text hotového kusu během mluvení (hlavní vlákno). */
    var onPartial: ((String) -> Unit)? = null

    /** Po nejdelší povolené nahrávce (služba nahrávání ukončí). */
    var onLimit: (() -> Unit)? = null

    /**
     * Začne nahrávat; false = mikrofon nejde otevřít (chybí oprávnění).
     * [lang] a [prompt] platí pro přepis kusů během mluvení.
     */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context, lang: String, prompt: String): Boolean {
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
        appCtx = ctx.applicationContext
        language = lang
        basePrompt = prompt
        lastText = ""
        failed = false
        buffer = ShortArray(SAMPLE_RATE * MAX_SECONDS)
        length = 0
        chunkStart = 0
        chunkSpeech = 0
        silenceRun = 0
        noiseFloor = 0.0
        record = r
        recording = true
        r.startRecording()
        // model se načte hned, ať první kus nečeká
        worker.execute { runCatching { appCtx?.let { ensureModel(it) } } }
        recThread = Thread {
            val chunk = ShortArray(min / 2)
            while (recording) {
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val room = buffer.size - length
                if (room <= 0) {
                    main.post { onLimit?.invoke() }
                    break
                }
                val take = minOf(n, room)
                System.arraycopy(chunk, 0, buffer, length, take)
                length += take
                track(chunk, take)
            }
        }.also { it.start() }
        return true
    }

    /** Hlídá pauzy v řeči a po pauze pošle hotový kus k přepisu. */
    private fun track(chunk: ShortArray, n: Int) {
        var sum = 0.0
        for (i in 0 until n) sum += chunk[i] * chunk[i].toDouble()
        val rms = sqrt(sum / n)
        // hladina šumu: rychle dolů, pomalu nahoru
        noiseFloor = if (noiseFloor == 0.0 || rms < noiseFloor) rms else noiseFloor + (rms - noiseFloor) * 0.002
        val speech = rms > maxOf(noiseFloor * 3, 250.0)
        if (speech) {
            chunkSpeech += n
            silenceRun = 0
        } else {
            silenceRun += n
        }
        val size = length - chunkStart
        val pause = silenceRun >= PAUSE && size >= MIN_CHUNK
        if (pause || size >= MAX_CHUNK) {
            val end = length
            submit(chunkStart, end, chunkSpeech, final = false)
            chunkStart = end
            chunkSpeech = 0
            silenceRun = 0
        }
    }

    /** Pošle kus nahrávky k přepisu; výsledky chodí ve stejném pořadí. */
    private fun submit(from: Int, to: Int, speech: Int, final: Boolean, done: ((String?) -> Unit)? = null) {
        val pcm = if (speech >= MIN_SPEECH && to - from >= SAMPLE_RATE / 3) {
            FloatArray(to - from) { buffer[from + it] / 32768f }
        } else {
            null
        }
        val ctx = appCtx
        worker.execute {
            val text = if (pcm == null || ctx == null) {
                ""
            } else {
                try {
                    if (!ensureModel(ctx)) null
                    else {
                        // velká jádra: víc vláken přepis spíš zpomalí
                        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                        // konec předchozího kusu jako nápověda: navazující věta, interpunkce
                        val prompt = (basePrompt + " " + lastText.takeLast(200)).trim()
                        nativeTranscribe(handle, pcm, language, threads, prompt).trim()
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            if (text == null) failed = true
            if (!text.isNullOrEmpty()) lastText = text
            main.post {
                if (final) {
                    done?.invoke(if (failed && text.isNullOrEmpty()) null else text.orEmpty())
                } else if (!text.isNullOrEmpty()) {
                    onPartial?.invoke(text)
                }
            }
        }
    }

    private fun stopRecording() {
        recording = false
        recThread?.join(500)
        record?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        record = null
    }

    /**
     * Konec nahrávání a přepis posledního kusu; výsledek (i prázdný, null = chyba)
     * přijde v hlavním vlákně až po všech kusech z průběhu.
     */
    fun stopAndTranscribe(done: (String?) -> Unit) {
        if (!recording) return
        stopRecording()
        val end = length
        // zbytek po poslední pauze; řeč se nepočítala jen pro nahrávky bez pauzy
        submit(chunkStart, end, maxOf(chunkSpeech, if (chunkStart == 0) end else 0), final = true) { text ->
            buffer = ShortArray(0)
            done(text)
        }
    }

    /** Zruší nahrávání bez přepisu. */
    fun cancel() {
        if (!recording) return
        stopRecording()
        onPartial = null
        buffer = ShortArray(0)
    }
}
