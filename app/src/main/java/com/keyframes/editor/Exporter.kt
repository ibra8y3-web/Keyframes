@file:OptIn(UnstableApi::class)

package com.keyframes.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
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
import androidx.media3.effect.MatrixTransformation
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

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
private val LUT_MIDNIGHT by lazy { makeLut { r, g, b -> val l = lum(r, g, b); Triple(r * .72f + .02f * l, g * .84f + .03f * l, b * 1.02f + .06f) } }
private val LUT_VINTAGE by lazy { makeLut { r, g, b -> val l = lum(r, g, b); Triple(.10f + .85f * (l * .8f + r * .25f), .08f + .80f * (l * .85f + g * .15f), .05f + .70f * (l * .75f + b * .1f)) } }
private val LUT_GOLDEN by lazy { makeLut { r, g, b -> Triple(r * 1.10f + .04f, g * 1.02f + .02f, b * .85f) } }
private fun gradeLut(gr: Float, gg: Float, gb: Float, lr: Float, lg: Float, lb: Float, con: Float, sat: Float) = makeLut { r, g, b ->
    val l = lum(r, g, b)
    fun ch(v: Float, gain: Float, lift: Float): Float = (((l + (v - l) * sat) * gain + lift) - .5f) * con + .5f
    Triple(ch(r, gr, lr), ch(g, gg, lg), ch(b, gb, lb))
}
private val LUT_SUNSET by lazy { gradeLut(1.12f, .98f, .78f, .05f, .01f, 0f, 1.08f, 1.1f) }
private val LUT_FOREST by lazy { gradeLut(.9f, 1.08f, .92f, 0f, .02f, 0f, 1.05f, 1f) }
private val LUT_ROSE by lazy { gradeLut(1.08f, .93f, 1.03f, .03f, 0f, .03f, 1f, 1.05f) }
private val LUT_OCEAN by lazy { gradeLut(.82f, 1f, 1.12f, 0f, .02f, .03f, 1.05f, 1.05f) }
private val LUT_CYBER by lazy { gradeLut(1.1f, .9f, 1.15f, .03f, 0f, .05f, 1.2f, 1.25f) }
private val LUT_MATTE by lazy { gradeLut(.9f, .9f, .92f, .08f, .08f, .09f, .88f, .9f) }
private val LUT_BLEACH by lazy { gradeLut(1f, 1f, 1f, 0f, 0f, 0f, 1.3f, .45f) }
private val LUT_DRAMA by lazy { gradeLut(1f, 1f, 1f, 0f, 0f, 0f, 1.35f, .85f) }
private val LUT_PASTEL by lazy { gradeLut(.78f, .78f, .8f, .22f, .22f, .22f, .9f, .8f) }
private val LUT_RETRO by lazy { gradeLut(.95f, .9f, .7f, .1f, .1f, .06f, .9f, .85f) }
private val LUT_SUNRISE by lazy { gradeLut(1.06f, 1f, .9f, .06f, .03f, .04f, 1f, 1.05f) }
private val LUT_MOODY by lazy { gradeLut(.78f, .86f, .92f, 0f, .01f, .02f, 1.15f, .9f) }
private val LUT_NOIR by lazy { makeLut { r, g, b -> val v = (lum(r, g, b) - .5f) * 1.45f + .5f; Triple(v, v, v) } }

/** Parses an Adobe .cube 3D LUT file into a real colour lookup effect. */
internal fun parseCube(text: String): SingleColorLut {
    var n = 0
    val vals = ArrayList<FloatArray>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line.startsWith("LUT_3D_SIZE")) { n = line.split(Regex("\\s+"))[1].toInt(); continue }
        val c0 = line[0]
        if (!(c0.isDigit() || c0 == '-' || c0 == '.')) continue
        val p = line.split(Regex("\\s+"))
        if (p.size >= 3) vals += floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat())
    }
    require(n >= 2 && vals.size == n * n * n) { "Invalid .cube file" }
    val cube = Array(n) { Array(n) { IntArray(n) } }
    for (i in vals.indices) {
        val v = vals[i]
        cube[i % n][(i / n) % n][i / (n * n)] = Color.rgb(
            (v[0].coerceIn(0f, 1f) * 255).toInt(), (v[1].coerceIn(0f, 1f) * 255).toInt(), (v[2].coerceIn(0f, 1f) * 255).toInt()
        )
    }
    return SingleColorLut.createFromCube(cube)
}

private val fileLuts = HashMap<String, SingleColorLut?>()

private fun lutFromFile(path: String): SingleColorLut? =
    fileLuts.getOrPut(path) { runCatching { parseCube(File(path).readText()) }.getOrNull() }

private val toneCache = HashMap<List<Float>, SingleColorLut>()

/** Exposure, shadows, highlights, matte and tint, baked into a real 3D colour LUT. */
private fun toneLut(c: Clip): SingleColorLut {
    val key = listOf(c.expo, c.shadows, c.highlights, c.matte, c.tint)
    return toneCache.getOrPut(key) {
        if (toneCache.size > 20) toneCache.clear()
        val gamma = 2.0.pow(c.expo.toDouble()).toFloat()
        makeLut { r, g, b ->
            fun ch(v: Float, adj: Float): Float {
                var x = v.pow(1f / gamma)
                val l = x
                x += c.shadows * (1f - l) * (1f - l) * .3f
                x += c.highlights * l * l * .3f
                x = x * (1f - c.matte * .5f) + c.matte * .12f
                return x + adj
            }
            Triple(ch(r, -c.tint * .04f), ch(g, c.tint * .08f), ch(b, -c.tint * .04f))
        }
    }
}

private val FONTS = listOf("sans-serif", "serif", "monospace", "cursive")

/** Time-varying zoom / slide / shake for one clip. Coordinates are NDC (-1..1). */
private fun motionMatrix(c: Clip, us: Long): Matrix {
    val ms = us / 1000f
    val len = c.lenMs.toFloat().coerceAtLeast(1f)
    var sc = 1f; var tx = 0f; var ty = 0f
    when (c.loop) {
        Loop.KEN_IN -> sc *= 1f + .25f * (ms / len).coerceIn(0f, 1f)
        Loop.KEN_OUT -> sc *= 1.25f - .25f * (ms / len).coerceIn(0f, 1f)
        Loop.PULSE -> sc *= 1f + .06f * sin(ms / 1000f * 2f * PI.toFloat() * 1.5f)
        Loop.SHAKE -> { tx += .02f * sin(ms / 20f); ty += .02f * cos(ms / 17f) }
        Loop.BOUNCE -> ty += .05f * sin(ms / 1000f * 2f * PI.toFloat() * 1.5f)
        Loop.DRIFT -> { sc *= 1.12f; tx += .06f * sin(ms / 1000f * PI.toFloat() * .5f) }
        Loop.FLASH, Loop.STROBE -> {}
        Loop.NONE -> {}
    }
    if (c.keys.isNotEmpty()) {
        val ks = c.keys
        val k0 = ks.lastOrNull { it.tMs <= ms }
        val k1 = ks.firstOrNull { it.tMs > ms }
        val (z, px, py) = when {
            k0 == null -> Triple(k1!!.zoom, k1.panX, k1.panY)
            k1 == null -> Triple(k0.zoom, k0.panX, k0.panY)
            else -> {
                val f = (ms - k0.tMs) / (k1.tMs - k0.tMs).toFloat()
                Triple(k0.zoom + (k1.zoom - k0.zoom) * f, k0.panX + (k1.panX - k0.panX) * f, k0.panY + (k1.panY - k0.panY) * f)
            }
        }
        sc *= z; tx += -px * (z - 1f); ty += -py * (z - 1f)
    }
    val d = c.animMs.toFloat().coerceAtLeast(100f)
    fun apply(a: Anim, p: Float) {
        val e = p * p * (3f - 2f * p)
        val off = (1f - e) * 2f
        when (a) {
            Anim.SLIDE_LEFT -> tx -= off
            Anim.SLIDE_RIGHT -> tx += off
            Anim.SLIDE_UP -> ty -= off
            Anim.SLIDE_DOWN -> ty += off
            Anim.ZOOM -> sc *= .6f + .4f * e
            Anim.POP -> sc *= e
            Anim.NONE -> {}
        }
    }
    if (c.animIn != Anim.NONE && ms < d) apply(c.animIn, (ms / d).coerceIn(0f, 1f))
    if (c.animOut != Anim.NONE && ms > len - d) apply(c.animOut, ((len - ms) / d).coerceIn(0f, 1f))
    val s = sc.coerceAtLeast(0.001f)
    return Matrix().apply { postScale(s, s); postTranslate(tx, ty) }
}

/** Per-clip visual effects. Shared by the live preview and the final export. */
fun clipEffects(c: Clip): List<Effect> {
    val l = mutableListOf<Effect>()
    if (c.zoom > 1f && c.keys.isEmpty()) {
        val h = 1f / c.zoom
        val cx = c.panX * (1f - h); val cy = c.panY * (1f - h)
        l += Crop(cx - h, cx + h, cy - h, cy + h)
    }
    if (c.rot != 0 || c.tilt != 0f || c.mirror || c.flipV) {
        l += ScaleAndRotateTransformation.Builder()
            .setRotationDegrees(c.rot + c.tilt)
            .setScale(if (c.mirror) -1f else 1f, if (c.flipV) -1f else 1f).build()
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
        Fx.MIDNIGHT -> l += LUT_MIDNIGHT
        Fx.VINTAGE -> l += LUT_VINTAGE
        Fx.GOLDEN -> l += LUT_GOLDEN
        Fx.SUNSET -> l += LUT_SUNSET
        Fx.FOREST -> l += LUT_FOREST
        Fx.ROSE -> l += LUT_ROSE
        Fx.OCEAN -> l += LUT_OCEAN
        Fx.CYBER -> l += LUT_CYBER
        Fx.MATTE -> l += LUT_MATTE
        Fx.BLEACH -> l += LUT_BLEACH
        Fx.DRAMA -> l += LUT_DRAMA
        Fx.PASTEL -> l += LUT_PASTEL
        Fx.RETRO -> l += LUT_RETRO
        Fx.SUNRISE -> l += LUT_SUNRISE
        Fx.MOODY -> l += LUT_MOODY
        Fx.NONE -> {}
    }
    if (c.temp != 0f) l += RgbAdjustment.Builder().setRedScale(1f + .3f * c.temp).setBlueScale(1f - .3f * c.temp).build()
    if (c.bright != 0f) l += Brightness(c.bright)
    if (c.contrast != 0f) l += Contrast(c.contrast)
    if (c.sat != 0f || c.hue != 0f) l += HslAdjustment.Builder().adjustHue(c.hue * 180f).adjustSaturation(c.sat * 100f).build()
    if (c.expo != 0f || c.shadows != 0f || c.highlights != 0f || c.matte != 0f || c.tint != 0f) l += toneLut(c)
    if (c.loop != Loop.NONE || c.animIn != Anim.NONE || c.animOut != Anim.NONE || c.keys.isNotEmpty()) {
        l += MatrixTransformation { us -> motionMatrix(c, us) }
    }
    c.lut?.let { path -> lutFromFile(path)?.let { l += it } }
    if (c.loop == Loop.FLASH || c.loop == Loop.STROBE) {
        val strobe = c.loop == Loop.STROBE
        l += RgbMatrix { us, _ ->
            val ms = us / 1000f
            val k = if (strobe) (if (((ms / 140f).toInt() % 2) == 0) 1f else 0.2f) else 1f + 1.4f * exp(-((ms % 1200f) / 160f))
            floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 1f)
        }
    }
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

private fun animP(start: Long, end: Long, ms: Long): Float =
    minOf(((ms - start) / 500f).coerceIn(0f, 1f), ((end - ms) / 500f).coerceIn(0f, 1f))

/** anim: 0 none, 1 fade, 2 pop, 3 slide up, 4 typewriter. */
private class TimedText(val t: TextItem) : TextOverlay() {
    override fun getText(presentationTimeUs: Long): SpannableString {
        val ms = presentationTimeUs / 1000
        val inside = ms in t.start until t.end
        val s = SpannableString(if (inside) t.text else " ")
        val shown = if (t.anim == 4 && inside) (t.text.length * ((ms - t.start) / 500f).coerceIn(0f, 1f)).toInt().coerceAtLeast(1) else s.length
        s.setSpan(ForegroundColorSpan(t.color), 0, shown.coerceAtMost(s.length), 0)
        if (shown < s.length) s.setSpan(ForegroundColorSpan(0), shown, s.length, 0)
        s.setSpan(AbsoluteSizeSpan(t.size), 0, s.length, 0)
        if (t.bold) s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, 0)
        if (t.bg) s.setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, s.length, 0)
        s.setSpan(TypefaceSpan(FONTS[t.font.coerceIn(0, FONTS.size - 1)]), 0, s.length, 0)
        return s
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        val p = animP(t.start, t.end, ms)
        var alpha = if (t.anim == 1) p else 1f
        if (ms !in t.start until t.end) alpha = 0f
        val b = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(t.x, if (t.anim == 3) t.y - (1f - p) * 0.25f else t.y)
            .setAlphaScale(alpha).setRotationDegrees(t.rot)
        if (t.anim == 2) { val sc = p.coerceAtLeast(0.01f); b.setScale(sc, sc) }
        return b.build()
    }
}

private class TimedSticker(val s: Sticker, val bmp: Bitmap) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        val p = animP(s.start, s.end, ms)
        var alpha = if (s.anim == 1) s.alpha * p else s.alpha
        if (ms !in s.start until s.end) alpha = 0f
        val sc = if (s.anim == 2) s.scale * p.coerceAtLeast(0.01f) else s.scale
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(s.x, if (s.anim == 3) s.y - (1f - p) * 0.25f else s.y)
            .setScale(sc, sc).setAlphaScale(alpha).setRotationDegrees(s.rot).build()
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

private fun silenceFile(ctx: Context, ms: Long): File {
    val f = File(ctx.cacheDir, "sil_$ms.wav")
    if (!f.exists()) AudioGen.writeSilence(f, ms)
    return f
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
            if (c.speed != 1f || c.pitch != 1f) audio += SonicAudioProcessor().apply { setSpeed(c.speed); setPitch(c.pitch) }
            if (c.volume != 1f) audio += volumeProcessor(c.volume)
            val video: List<Effect> = if (audioOnly) emptyList() else {
                clipEffects(c) + (if (c.speed != 1f) listOf<Effect>(SpeedChangeEffect(c.speed)) else emptyList())
            }
            EditedMediaItem.Builder(mi).setEffects(Effects(audio, video)).setRemoveVideo(audioOnly).build()
        }
        val seqs = mutableListOf<EditedMediaItemSequence>()
        if (items.isNotEmpty()) seqs += EditedMediaItemSequence(items)
        p.audio.forEach { a ->
            val room = p.totalMs - a.startMs
            if (room < 300) return@forEach
            val end = minOf(a.srcEnd, a.srcStart + room)
            val list = mutableListOf<EditedMediaItem>()
            if (a.startMs > 0) list += EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(silenceFile(ctx, a.startMs)))).build()
            val mi = MediaItem.Builder().setUri(a.uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a.srcStart).setEndPositionMs(end).build()).build()
            list += EditedMediaItem.Builder(mi).setRemoveVideo(true)
                .setEffects(Effects(if (a.volume != 1f) listOf(volumeProcessor(a.volume)) else emptyList(), emptyList())).build()
            seqs += EditedMediaItemSequence(list)
        }
        val vfx = mutableListOf<Effect>()
        if (!audioOnly) {
            if (p.ratio > 0f) vfx += Presentation.createForAspectRatio(p.ratio, if (p.fit) Presentation.LAYOUT_SCALE_TO_FIT else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
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
