package com.dddylan.slick

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.audiofx.Visualizer
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The audio contract every scene reads: three bands (kick, snare, hat) as energies.
 * Sources push as often as they can; the page reads once per frame and gets the
 * loudest value since its last read, so short transients are never lost between frames.
 */
object AudioEngine {
    /** Band edges in Hz. They match the web scene's analyser bands. */
    val BANDS = arrayOf(40f to 140f, 230f to 1900f, 3300f to 14000f)

    private val lock = Any()
    private val peak = FloatArray(3)
    private var seq = 0L
    private var frames = 0
    private var rateStart = 0L
    @Volatile private var lastPush = 0L
    @Volatile var hz = 0f
    @Volatile var route = "off"

    /** Set by the activity; forwards status lines to the page. */
    @Volatile var listener: ((route: String, state: String, message: String) -> Unit)? = null

    fun status(route: String, state: String, message: String) {
        listener?.invoke(route, state, message)
    }

    fun push(e0: Float, e1: Float, e2: Float) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            peak[0] = max(peak[0], e0)
            peak[1] = max(peak[1], e1)
            peak[2] = max(peak[2], e2)
            seq++
            frames++
            if (rateStart == 0L) rateStart = now
            if (now - rateStart >= 1000) {
                hz = frames * 1000f / (now - rateStart)
                frames = 0
                rateStart = now
            }
        }
        lastPush = now
    }

    /** "seq,kick,snare,hat,updatesPerSecond,route" */
    fun read(): String = synchronized(lock) {
        if (SystemClock.elapsedRealtime() - lastPush > 1000) hz = 0f
        val s = "$seq,${peak[0]},${peak[1]},${peak[2]},$hz,$route"
        peak.fill(0f)
        s
    }

    fun resetRate() = synchronized(lock) {
        frames = 0
        rateStart = 0L
        hz = 0f
    }
}

/**
 * Route 1: Android's Visualizer on the global output mix (session 0).
 * No consent dialog and works with most players. The data is coarse (8-bit FFT) and the
 * update rate is set by the device, so the page shows the measured rate in its readout.
 */
class VisualizerRoute {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var vis: Visualizer? = null

    /** Throws if the device refuses the output mix. */
    fun start() {
        val v = Visualizer(0)
        try {
            v.enabled = false
            val range = Visualizer.getCaptureSizeRange()
            v.captureSize = min(1024, range[1])
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.enabled = true
        } catch (e: RuntimeException) {
            v.release()
            throw e
        }
        vis = v
        running = true
        AudioEngine.resetRate()
        thread = Thread({ loop(v) }, "slick-visualizer").also { it.start() }
    }

    private fun loop(v: Visualizer) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val n = v.captureSize
        val buf = ByteArray(n)
        val prev = ByteArray(n)
        val binHz = v.samplingRate / 1000f / n
        val ranges = AudioEngine.BANDS.map { (lo, hi) ->
            max(1, (lo / binHz).roundToInt()) to min(n / 2 - 1, (hi / binHz).roundToInt())
        }
        val e = FloatArray(3)
        while (running) {
            // Poll faster than the documented capture rate: many devices refresh the buffer more often.
            if (v.getFft(buf) == Visualizer.SUCCESS && !buf.contentEquals(prev)) {
                System.arraycopy(buf, 0, prev, 0, n)
                for (b in 0..2) {
                    val (lo, hi) = ranges[b]
                    var s = 0f
                    for (k in lo..hi) {
                        val re = buf[2 * k] / 128f
                        val im = buf[2 * k + 1] / 128f
                        s += re * re + im * im
                    }
                    e[b] = s / (hi - lo + 1)
                }
                AudioEngine.push(e[0], e[1], e[2])
            }
            try { Thread.sleep(4) } catch (_: InterruptedException) { break }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(300)
        thread = null
        try { vis?.enabled = false } catch (_: RuntimeException) {}
        vis?.release()
        vis = null
    }
}

/**
 * Route 2: playback capture. Real PCM from other apps, so it is fast and precise,
 * but an app can opt out and Android asks for consent each session.
 * Bands come from three biquad filters measured every 256 samples (about 5 ms).
 */
class CaptureRoute(private val projection: MediaProjection) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var rec: AudioRecord? = null

    @SuppressLint("MissingPermission") // The activity confirms RECORD_AUDIO before capture starts.
    fun start() {
        val rate = 48000
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuf, 8192))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("AudioRecord did not initialize")
        }
        r.startRecording()
        rec = r
        running = true
        AudioEngine.resetRate()
        thread = Thread({ loop(r, r.sampleRate.toFloat()) }, "slick-capture").also { it.start() }
    }

    private fun loop(r: AudioRecord, fs: Float) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val kick = Biquad.lowpass(140f, 0.707f, fs)
        val snare = Biquad.bandpass(660f, 0.45f, fs)
        val hat = Biquad.highpass(3300f, 0.707f, fs)
        val hop = 256
        val buf = ShortArray(hop)
        while (running) {
            val got = r.read(buf, 0, hop)
            if (got <= 0) {
                if (got < 0) break
                continue
            }
            var k = 0f
            var s = 0f
            var h = 0f
            for (i in 0 until got) {
                val x = buf[i] / 32768f
                val a = kick.process(x); k += a * a
                val b = snare.process(x); s += b * b
                val c = hat.process(x); h += c * c
            }
            AudioEngine.push(k / got, s / got, h / got)
        }
    }

    fun stop() {
        running = false
        try { rec?.stop() } catch (_: IllegalStateException) {}
        thread?.join(300)
        thread = null
        rec?.release()
        rec = null
    }
}

/** RBJ cookbook biquad, transposed direct form II. */
class Biquad private constructor(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float,
) {
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        private fun make(f0: Float, q: Float, fs: Float, kind: Int): Biquad {
            val w = 2.0 * PI * f0 / fs
            val alpha = sin(w) / (2.0 * q)
            val c = cos(w)
            val a0 = 1.0 + alpha
            val (b0, b1, b2) = when (kind) {
                0 -> Triple((1 - c) / 2, 1 - c, (1 - c) / 2)       // lowpass
                1 -> Triple((1 + c) / 2, -(1 + c), (1 + c) / 2)    // highpass
                else -> Triple(alpha, 0.0, -alpha)                  // bandpass, 0 dB peak
            }
            return Biquad(
                (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (-2 * c / a0).toFloat(), ((1 - alpha) / a0).toFloat(),
            )
        }
        fun lowpass(f0: Float, q: Float, fs: Float) = make(f0, q, fs, 0)
        fun highpass(f0: Float, q: Float, fs: Float) = make(f0, q, fs, 1)
        fun bandpass(f0: Float, q: Float, fs: Float) = make(f0, q, fs, 2)
    }
}
