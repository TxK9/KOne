package cc.txwd.kone.city

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Codex's soundscape (ambient-audio-v11.js): wind, rain and sea, each a loop of noise made here
 * (never recorded, never downloaded) through a high-pass and a low-pass filter, mixed quietly by
 * the weather and the water in sight. This is the sound itself, sample by sample; [Ambient]
 * plays it.
 */
class Soundscape(val rate: Int = 44_100) {
    /** What the sound follows: the weather's wind (about 1) and rain (0 to 1), and the kind of water. */
    data class Mix(val wind: Double = 1.0, val rain: Double = 0.0, val water: String = "land")

    private inner class Layer(val noise: FloatArray, val loopStart: Int, var position: Int, high: Double, low: Double) {
        val highPass = Biquad(high = true, frequency = high)
        val lowPass = Biquad(high = false, frequency = low)
        var gain = 0.0
        var target = 0.0
    }

    /** A WebAudio biquad (lowpass or highpass, Q in dB as WebAudio reads it there), its frequency eased like an AudioParam. */
    private inner class Biquad(private val high: Boolean, frequency: Double) {
        var frequency = frequency
        var target = frequency
        private var b0 = 0.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0
        private var designed = -1.0

        fun process(x: Double): Double {
            if (frequency != designed) design()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        private fun design() {
            designed = frequency
            val w0 = 2 * PI * frequency / rate
            val alpha = sin(w0) / (2 * 10.0.pow(Q / 20))
            val c = cos(w0)
            val a0 = 1 + alpha
            val edge = if (high) 1 + c else 1 - c
            b0 = edge / 2 / a0
            b1 = (if (high) -edge else edge) / a0
            b2 = edge / 2 / a0
            a1 = -2 * c / a0
            a2 = (1 - alpha) / a0
        }
    }

    private val wind = layer("wind", 913, low = 620.0, high = 55.0, start = .7)
    private val rain = layer("rain", 419, low = 6200.0, high = 950.0, start = 0.0)
    private val sea = layer("sea", 2671, low = 530.0, high = 35.0, start = 1.9)
    private val layers = listOf(wind, rain, sea)
    private var master = 0.0
    private var masterTarget = 0.0
    private var masterTau = .24
    private var seconds = 0.0

    /** The sound's own clock, for its slow breathing and swell. */
    val time get() = seconds

    /** Turns the sound toward [mix], or toward silence for null, as Codex does five times a second. */
    fun follow(mix: Mix?) {
        if (mix == null) {
            masterTarget = 0.0
            masterTau = .12
            return
        }
        val wind = mix.wind.coerceIn(.5, 2.0)
        val rain = mix.rain.coerceIn(0.0, 1.0)
        val t = seconds
        val breath = .88 + .07 * sin(t * .17) + .05 * sin(t * .049 + 1.8)
        val swell = .68 + .2 * sin(t * .53 + .6) + .12 * sin(t * .21)
        this.wind.target = ((wind - .35) * .12 * breath).coerceIn(0.0, .23)
        this.rain.target = rain * .27
        sea.target = (SEA[mix.water.lowercase()] ?: 0.0) * (.13 + .055 * wind) * swell
        this.wind.lowPass.target = 360 + wind * 180
        masterTarget = minOf(MASTER_LIMIT, MASTER_LEVEL)
        masterTau = .24
    }

    /** Whether it has fallen silent after being told to stop. */
    val silent get() = masterTarget == 0.0 && master < 1e-5

    /** The next [count] samples, -1 to 1, into [out]. */
    fun render(out: FloatArray, count: Int = out.size) {
        val step = 1.0 / rate
        val masterEase = 1 - exp(-step / masterTau)
        val layerEase = 1 - exp(-step / .35)
        val frequencyEase = 1 - exp(-step / .5)
        for (i in 0 until count) {
            var sum = 0.0
            for (layer in layers) {
                layer.gain += (layer.target - layer.gain) * layerEase
                layer.lowPass.frequency += (layer.lowPass.target - layer.lowPass.frequency) * frequencyEase
                val x = layer.noise[layer.position].toDouble()
                layer.position++
                if (layer.position >= layer.noise.size) layer.position = layer.loopStart
                sum += layer.lowPass.process(layer.highPass.process(x)) * layer.gain
            }
            master += (masterTarget - master) * masterEase
            out[i] = (sum * master).toFloat().coerceIn(-1f, 1f)
            seconds += step
        }
    }

    private fun layer(kind: String, seed: Int, low: Double, high: Double, start: Double): Layer {
        val noise = noise(kind, seed, rate)
        return Layer(noise, (rate * .06).toInt(), (start * rate).toInt().coerceIn(0, noise.size - 1), high, low).also { it.lowPass.target = low }
    }

    companion object {
        const val MASTER_LIMIT = .09
        const val MASTER_LEVEL = .075
        private const val Q = .35
        val SEA = mapOf("sea" to 1.0, "ocean" to 1.0, "coast" to 1.0, "bay" to .85, "strait" to .9, "harbour" to .32, "harbor" to .32, "lagoon" to .36, "marina" to .22)

        /** Codex's 4.2 seconds of noise for a layer: rain white, wind and sea smoothed, the loop's seam blended away. */
        fun noise(kind: String, seed: Int, rate: Int): FloatArray {
            val data = FloatArray(jsRound(rate * 4.2))
            var value = seed
            var last = 0.0
            for (i in data.indices) {
                value = value * 1664525 + 1013904223
                val white = value.toUInt().toDouble() / 2147483648.0 - 1
                data[i] = if (kind == "rain") (white * .7).toFloat() else {
                    last = if (kind == "sea") (last + .025 * white) / 1.025 else last * .98 + white * .07
                    (last * (if (kind == "sea") 3.4 else 1.6)).coerceIn(-.95, .95).toFloat()
                }
            }
            val edge = minOf((rate * .06).toInt(), data.size / 4)
            for (i in 0 until edge) {
                val mix = i.toDouble() / edge
                val end = data.size - edge + i
                data[end] = (data[end] * (1 - mix) + data[i] * mix).toFloat()
            }
            return data
        }
    }
}

/**
 * Plays the [Soundscape] on the computer's own audio output while there is a [mix] to follow;
 * fades out and lets the output go when it is set to null. Nothing plays until it is.
 */
class Ambient {
    @Volatile
    var mix: Soundscape.Mix? = null
        set(value) {
            field = value
            if (value != null) start()
        }

    private var player: Thread? = null

    @Synchronized
    private fun start() {
        if (player?.isAlive == true) return
        player = Thread(::play, "KOne sound").apply {
            isDaemon = true
            start()
        }
    }

    private fun play() {
        val scape = Soundscape()
        val format = AudioFormat(scape.rate.toFloat(), 16, 1, true, false)
        val line: SourceDataLine = runCatching { AudioSystem.getSourceDataLine(format).apply { open(format, scape.rate / 5 * 2) } }.getOrNull() ?: return
        line.start()
        val block = FloatArray(scape.rate / 50)
        val bytes = ByteArray(block.size * 2)
        var followed = -1.0
        try {
            while (true) {
                val wanted = mix
                // Five times a second, as Codex's does.
                if (scape.time - followed >= .2) {
                    scape.follow(wanted)
                    followed = scape.time
                }
                if (wanted == null && scape.silent) break
                scape.render(block)
                for (i in block.indices) {
                    val sample = (block[i] * 32767).toInt()
                    bytes[i * 2] = sample.toByte()
                    bytes[i * 2 + 1] = (sample shr 8).toByte()
                }
                line.write(bytes, 0, bytes.size)
            }
            line.drain()
        } finally {
            line.close()
        }
        // Turned on again while it faded: play on.
        synchronized(this) { player = null }
        if (mix != null) start()
    }
}
