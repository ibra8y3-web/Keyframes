package com.keyframes.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Decodes an audio file on the device and returns one normalized peak per [STEP_MS] milliseconds. */
object Waveform {
    const val STEP_MS = 50

    fun peaks(ctx: Context, uri: Uri): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            ex.setDataSource(ctx, uri, null)
            var ti = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) { ti = i; break }
            }
            if (ti < 0) return null
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val buckets = FloatArray(((durUs / 1000) / STEP_MS + 2).toInt().coerceIn(2, 400000))
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var guard = 0
            while (!outDone && guard++ < 400000) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val out = c.getOutputBuffer(oi)
                    if (out != null && info.size > 0) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                        var mx = 0
                        while (sb.hasRemaining()) { val v = abs(sb.get().toInt()); if (v > mx) mx = v }
                        val idx = ((info.presentationTimeUs / 1000) / STEP_MS).toInt().coerceIn(0, buckets.size - 1)
                        buckets[idx] = max(buckets[idx], mx / 32768f)
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            val peak = buckets.maxOrNull() ?: 0f
            if (peak > 0f) for (i in buckets.indices) buckets[i] = (buckets[i] / peak).coerceIn(0f, 1f)
            buckets
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
