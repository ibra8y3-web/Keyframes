@file:OptIn(UnstableApi::class)

package com.keyframes.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val SEPIA = floatArrayOf(
    .393f, .349f, .272f, 0f,
    .769f, .686f, .534f, 0f,
    .189f, .168f, .131f, 0f,
    0f, 0f, 0f, 1f
)

private fun makeLut(f: (Float, Float, Float) -> Triple<Float, Float, Float>): SingleColorLut {
    val n = 17
    val cube = Array(n) { r -> Array(n) { g -> IntArray(n) { b ->
        val (x, y, z) = f(r / (n - 1f), g / (n - 1f), b / (n - 1f))
        Color.rgb((x.coerceIn(0f, 1f) * 255).toInt(), (y.coerceIn(0f, 1f) * 255).toInt(), (z.coerceIn(0f, 1f) * 255).toInt())
    } } }
    return SingleColorLut.createFromCube(cube)
}

private fun lum(r: Float, g: Float, b: Float) = .299f * r + .587f * g + .114f * b

private val LUT_CINE by lazy { makeLut { r, g, b -> val l = lum(r, g, b); Triple(r + .14f * (l - .5f) + .03f, g + .02f, b - .14f * (l - .5f) + .03f * (1f - l)) } }
private val LUT_VIVID by lazy { makeLut { r, g, b -> val l = lum(r, g, b); Triple(l + (r - l) * 1.4f, l + (g - l) * 1.4f, l + (b - l) * 1.4f) } }
private val LUT_FADE by lazy { makeLut { r, g, b -> val l = lum(r, g, b); Triple(.1f + .8f * (l + (r - l) * .8f), .1f + .8f * (l + (g - l) * .8f), .1f + .8f * (l + (b - l) * .8f)) } }
private val LUT_NOIR by lazy { makeLut { r, g, b -> val v = (lum(r, g, b) - .5f) * 1.45f + .5f; Triple(v, v, v) } }

/** Per-clip visual effects. Shared by the live preview and the final export. */
fun clipEffects(c: Clip): List<Effect> {
    val l = mutableListOf<Effect>()
    if (c.zoom > 1f) {
        val h = 1f / c.zoom
        val cx = c.panX * (1f - h); val cy = c.panY * (1f - h)
        l += Crop(cx - h, cx + h, cy - h, cy + h)
    }
    if (c.rot != 0 || c.mirror) {
        l += ScaleAndRotateTransformation.Builder()
            .setRotationDegrees(c.rot.toFloat())
            .setScale(if (c.mirror) -1f else 1f, 1f).build()
    }
    when (c.fx) {
        Fx.GRAY -> l += RgbFilter.createGrayscaleFilter()
        Fx.INVERT -> l += RgbFilter.createInvertedFilter()
        Fx.SEPIA -> l += RgbMatrix { _, _ -> SEPIA }
        Fx.WARM -> l += RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(0.85f).build()
        Fx.COOL -> l += RgbAdjustment.Builder().setRedScale(0.85f).setBlueScale(1.15f).build()
        Fx.CINE -> l += LUT_CINE
        Fx.VIVID -> l += LUT_VIVID
        Fx.FADE -> l += LUT_FADE
        Fx.NOIR -> l += LUT_NOIR
        Fx.NONE -> {}
    }
    if (c.temp != 0f) l += RgbAdjustment.Builder().setRedScale(1f + .3f * c.temp).setBlueScale(1f - .3f * c.temp).build()
    if (c.bright != 0f) l += Brightness(c.bright)
    if (c.contrast != 0f) l += Contrast(c.contrast)
    if (c.sat != 0f || c.hue != 0f) l += HslAdjustment.Builder().adjustHue(c.hue * 180f).adjustSaturation(c.sat * 100f).build()
    if (c.fadeIn > 0 || c.fadeOut > 0) {
        val len = c.lenMs.toFloat()
        l += RgbMatrix { us, _ ->
            val ms = us / 1000f
            var k = 1f
            if (c.fadeIn > 0) k = minOf(k, ms / c.fadeIn)
            if (c.fadeOut > 0) k = minOf(k, (len - ms) / c.fadeOut)
            val v = k.coerceIn(0f, 1f)
            floatArrayOf(v, 0f, 0f, 0f, 0f, v, 0f, 0f, 0f, 0f, v, 0f, 0f, 0f, 0f, 1f)
        }
    }
    return l
}

private fun volumeProcessor(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
    for (ch in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(v))
}

private class TimedText(val t: TextItem) : TextOverlay() {
    override fun getText(presentationTimeUs: Long): SpannableString {
        val ms = presentationTimeUs / 1000
        val s = SpannableString(if (ms in t.start until t.end) t.text else " ")
        s.setSpan(ForegroundColorSpan(t.color), 0, s.length, 0)
        s.setSpan(AbsoluteSizeSpan(t.size), 0, s.length, 0)
        return s
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder().setBackgroundFrameAnchor(0f, t.y).build()
}

private class TimedSticker(val s: Sticker, val bmp: Bitmap) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(s.x, s.y).setScale(s.scale, s.scale)
            .setAlphaScale(if (ms in s.start until s.end) 1f else 0f).build()
    }
}

internal fun loadBitmap(ctx: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
        val m = maxOf(info.size.width, info.size.height)
        if (m > 512) {
            val f = 512f / m
            dec.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
        }
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

object Exporter {
    fun build(ctx: Context, p: Project, height: Int, audioOnly: Boolean): Composition {
        val items = p.clips.filter { !audioOnly || !it.isImage }.map { c ->
            if (c.isImage) {
                return@map EditedMediaItem.Builder(MediaItem.fromUri(c.uri))
                    .setDurationUs(c.lenMs * 1000).setFrameRate(30)
                    .setEffects(Effects(emptyList(), clipEffects(c))).build()
            }
            val mi = MediaItem.Builder().setUri(c.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.start).setEndPositionMs(c.end).build()
                ).build()
            val audio = mutableListOf<AudioProcessor>()
            if (c.speed != 1f) audio += SonicAudioProcessor().apply { setSpeed(c.speed); setPitch(1f) }
            if (c.volume != 1f) audio += volumeProcessor(c.volume)
            val video: List<Effect> = if (audioOnly) emptyList() else {
                clipEffects(c) + (if (c.speed != 1f) listOf<Effect>(SpeedChangeEffect(c.speed)) else emptyList())
            }
            EditedMediaItem.Builder(mi).setEffects(Effects(audio, video)).setRemoveVideo(audioOnly).build()
        }
        val seqs = mutableListOf(EditedMediaItemSequence(items))
        p.music?.let { m ->
            val mi = EditedMediaItem.Builder(MediaItem.fromUri(m.uri))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(volumeProcessor(m.volume)), emptyList())).build()
            seqs += EditedMediaItemSequence(listOf(mi), true)
        }
        p.voice?.let { m ->
            val mi = EditedMediaItem.Builder(MediaItem.fromUri(m.uri)).setRemoveVideo(true)
                .setEffects(Effects(if (m.volume != 1f) listOf(volumeProcessor(m.volume)) else emptyList(), emptyList())).build()
            seqs += EditedMediaItemSequence(listOf(mi))
        }
        val vfx = mutableListOf<Effect>()
        if (!audioOnly) {
            if (p.ratio > 0f) vfx += Presentation.createForAspectRatio(p.ratio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            vfx += Presentation.createForHeight(height)
            val overlays = mutableListOf<TextureOverlay>()
            p.stickers.forEach { st -> runCatching { loadBitmap(ctx, st.uri) }.getOrNull()?.let { overlays += TimedSticker(st, it) } }
            p.texts.forEach { overlays += TimedText(it) }
            if (overlays.isNotEmpty()) vfx += OverlayEffect(ImmutableList.copyOf<TextureOverlay>(overlays))
        }
        return Composition.Builder(seqs).setEffects(Effects(emptyList(), vfx)).build()
    }
}

object Media {
    suspend fun publish(ctx: Context, file: File, audio: Boolean): Uri = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (audio) "audio/mp4" else "video/mp4")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Keyframes"
            )
        }
        val col = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = ctx.contentResolver.insert(col, v) ?: error("insert failed")
        ctx.contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        file.delete()
        uri
    }

    suspend fun savePng(ctx: Context, bmp: Bitmap) = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "kf_${System.currentTimeMillis()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Keyframes")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("insert failed")
        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
