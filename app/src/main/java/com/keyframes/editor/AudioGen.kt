package com.keyframes.editor

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Generates real WAV audio on the device: silence for track offsets and synthesized sound effects. */
object AudioGen {
    private const val SR = 44100
    val SFX_ICONS = listOf("🔔", "💨", "💥", "⚡", "🥁", "📢")
    val SFX_NAMES = listOf("Ding", "Whoosh", "Pop", "Zap", "Drum", "Horn")

    private fun header(dataLen: Int, channels: Int): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(channels.toShort())
        bb.putInt(SR); bb.putInt(SR * 2 * channels); bb.putShort((2 * channels).toShort()); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        return bb.array()
    }

    fun writeSilence(f: File, ms: Long) {
        val n = (ms * SR / 1000).toInt()
        FileOutputStream(f).use { o ->
            o.write(header(n * 2, 1))
            val chunk = ByteArray(65536)
            var left = n * 2
            while (left > 0) { val k = minOf(left, chunk.size); o.write(chunk, 0, k); left -= k }
        }
    }

    private fun writeStereo(f: File, mono: ShortArray) {
        val bb = ByteBuffer.allocate(mono.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        mono.forEach { bb.putShort(it); bb.putShort(it) }
        FileOutputStream(f).use { it.write(header(mono.size * 4, 2)); it.write(bb.array()) }
    }

    fun sfx(ctx: Context, kind: Int): File {
        val f = File(ctx.filesDir, "sfx_$kind.wav")
        if (f.exists()) return f
        val secs = when (kind) { 0 -> 1.0; 1 -> 0.8; 2 -> 0.25; 3 -> 0.4; 4 -> 0.5; else -> 0.7 }
        val n = (secs * SR).toInt()
        val rnd = Random(7)
        var phase = 0.0
        var lp = 0.0
        val out = ShortArray(n) { i ->
            val t = i / SR.toDouble()
            val u = t / secs
            val v = when (kind) {
                0 -> (sin(2 * PI * 880 * t) * .7 + sin(2 * PI * 1760 * t) * .3) * exp(-4.0 * t)
                1 -> { val a = lp * .9 + (rnd.nextDouble() * 2 - 1) * (.1 + .6 * u); lp = a; a * sin(PI * u) * 2.5 }
                2 -> { phase += 2 * PI * (700 - 500 * u) / SR; sin(phase) * exp(-18.0 * t) }
                3 -> { phase += 2 * PI * (1800 - 1500 * u) / SR; sin(phase) * (1 - u) }
                4 -> { phase += 2 * PI * (160 * exp(-8.0 * t) + 45) / SR; sin(phase) * exp(-7.0 * t) }
                else -> (sin(2 * PI * 330 * t) + .5 * sin(2 * PI * 660 * t)) * (if (u < .1) u / .1 else if (u > .8) (1 - u) / .2 else 1.0) * .6
            }
            (v.coerceIn(-1.0, 1.0) * 28000).toInt().toShort()
        }
        writeStereo(f, out)
        return f
    }
}
