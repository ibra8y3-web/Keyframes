@file:OptIn(UnstableApi::class)

package com.keyframes.editor

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

sealed interface ExportUi {
    data object Idle : ExportUi
    data class Running(val progress: Float) : ExportUi
    data class Done(val uri: Uri, val audio: Boolean) : ExportUi
    data class Failed(val reason: String?) : ExportUi
}

data class Draft(val id: Long, val thumb: Uri?, val count: Int, val durMs: Long)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private var curId: Long = ProjectStore.currentId(app)
    private val _p = MutableStateFlow(ProjectStore.load(app, curId))
    val project = _p.asStateFlow()
    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)
    val selected = MutableStateFlow(0)
    val export = MutableStateFlow<ExportUi>(ExportUi.Idle)
    val msg = MutableStateFlow<Int?>(null)
    val drafts = MutableStateFlow<List<Draft>>(emptyList())
    val recording = MutableStateFlow(false)
    val waves = MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    private val pendingWaves = HashSet<String>()
    private var dragging = false
    private var transformer: Transformer? = null
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voiceStart = 0L

    // ---------- state / history ----------
    private fun sync() {
        canUndo.value = undo.isNotEmpty(); canRedo.value = redo.isNotEmpty()
        ProjectStore.save(ctx, curId, _p.value)
    }
    private fun push() { undo.addLast(_p.value); if (undo.size > 60) undo.removeFirst(); redo.clear() }
    fun edit(f: (Project) -> Project) { push(); _p.value = f(_p.value); fixSel(); sync() }
    fun live(f: (Project) -> Project) { if (!dragging) { push(); dragging = true }; _p.value = f(_p.value) }
    fun liveEnd() { dragging = false; sync() }
    private fun fixSel() { selected.value = selected.value.coerceIn(0, maxOf(0, _p.value.clips.size - 1)) }
    fun undo() { if (undo.isEmpty()) return; redo.addLast(_p.value); _p.value = undo.removeLast(); fixSel(); sync() }
    fun redo() { if (redo.isEmpty()) return; undo.addLast(_p.value); _p.value = redo.removeLast(); fixSel(); sync() }

    // ---------- drafts ----------
    fun refreshDrafts() {
        viewModelScope.launch {
            drafts.value = withContext(Dispatchers.IO) {
                ProjectStore.ids(ctx).mapNotNull { id ->
                    val pr = ProjectStore.load(ctx, id)
                    if (pr.clips.isEmpty()) null else Draft(id, pr.clips[0].uri, pr.clips.size, pr.totalMs)
                }
            }
        }
    }
    private fun resetHistory() { undo.clear(); redo.clear(); selected.value = 0 }
    fun newDraft() {
        if (_p.value.clips.isNotEmpty()) { curId = System.currentTimeMillis(); ProjectStore.setCurrent(ctx, curId) }
        resetHistory(); _p.value = Project(); sync()
    }
    fun openDraft(id: Long) {
        curId = id; ProjectStore.setCurrent(ctx, id)
        resetHistory(); _p.value = ProjectStore.load(ctx, id); sync()
    }
    fun deleteDraft(id: Long) {
        ProjectStore.delete(ctx, id)
        if (id == curId) { curId = System.currentTimeMillis(); ProjectStore.setCurrent(ctx, curId); resetHistory(); _p.value = Project() }
        refreshDrafts()
    }

    // ---------- clips ----------
    fun updateClip(drag: Boolean, f: (Clip) -> Clip) {
        val i = selected.value
        val g: (Project) -> Project = { p ->
            if (i !in p.clips.indices) p
            else p.copy(clips = p.clips.toMutableList().also { it[i] = f(it[i]) })
        }
        if (drag) live(g) else edit(g)
    }

    fun addMedia(uris: List<Uri>) {
        viewModelScope.launch {
            val new = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    if (ctx.contentResolver.getType(u)?.startsWith("image/") == true) {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        runCatching { ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) } }
                        return@mapNotNull if (o.outWidth > 0) Clip(id = System.nanoTime(), uri = u, durMs = 20000, end = 3000, isImage = true, w = o.outWidth, h = o.outHeight) else null
                    }
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, u)
                        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != null
                        if (d == null || !hasVideo) null else {
                            var vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            var vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                            if (rot % 180 != 0) { val t = vw; vw = vh; vh = t }
                            Clip(id = System.nanoTime(), uri = u, durMs = d, w = vw, h = vh)
                        }
                    } catch (e: Exception) { null } finally { r.release() }
                }
            }
            if (new.isEmpty()) msg.value = R.string.failed else edit { it.copy(clips = it.clips + new) }
        }
    }

    fun split(localMs: Long) {
        val i = selected.value
        val c = _p.value.clips.getOrNull(i) ?: return
        val at = c.start + localMs
        if (at - c.start < 300 || c.end - at < 300) { msg.value = R.string.failed; return }
        edit {
            val l = it.clips.toMutableList()
            l[i] = c.copy(end = at); l.add(i + 1, c.copy(id = System.nanoTime(), start = at, keys = emptyList()))
            it.copy(clips = l)
        }
    }
    fun duplicate() {
        val i = selected.value
        edit { p -> p.clips.getOrNull(i)?.let { c -> p.copy(clips = p.clips.toMutableList().also { it.add(i + 1, c.copy(id = System.nanoTime())) }) } ?: p }
    }
    fun delete() { val i = selected.value; edit { p -> if (i in p.clips.indices) p.copy(clips = p.clips - p.clips[i]) else p } }
    fun move(d: Int) {
        val i = selected.value; val j = i + d
        edit { p ->
            if (i !in p.clips.indices || j !in p.clips.indices) p
            else p.copy(clips = p.clips.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t })
        }
        if (j in _p.value.clips.indices) selected.value = j
    }

    private fun frameAt(u: Uri, us: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(ctx, u); r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) } finally { r.release() }
    }

    fun freeze(localMs: Long) {
        val i = selected.value
        val c = _p.value.clips.getOrNull(i) ?: return
        if (c.isImage) return
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = frameAt(c.uri, (c.start + localMs) * 1000)!!
                    File(ctx.filesDir, "freeze_${System.nanoTime()}.png").also { out -> out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }.getOrNull()
            }
            if (f == null) { msg.value = R.string.failed; return@launch }
            val at = c.start + localMs
            val img = c.copy(id = System.nanoTime(), uri = Uri.fromFile(f), durMs = 20000, start = 0, end = 3000, speed = 1f, volume = 1f, fadeIn = 0, fadeOut = 0, isImage = true, keys = emptyList())
            edit { p ->
                val l = p.clips.toMutableList()
                if (i in l.indices) {
                    if (at - c.start >= 300 && c.end - at >= 300) {
                        l[i] = c.copy(end = at); l.add(i + 1, img); l.add(i + 2, c.copy(id = System.nanoTime(), start = at, keys = emptyList()))
                    } else l.add(i + 1, img)
                }
                p.copy(clips = l)
            }
        }
    }

    fun addKey(ms: Long) = updateClip(false) { c ->
        c.copy(keys = (c.keys.filter { abs(it.tMs - ms) > 100 } + Keyframe(ms.coerceIn(0, c.lenMs), c.zoom, c.panX, c.panY)).sortedBy { it.tMs })
    }
    fun removeKey(i: Int) = updateClip(false) { c -> c.copy(keys = c.keys.filterIndexed { idx, _ -> idx != i }) }

    fun snapshot(localMs: Long) {
        val c = _p.value.clips.getOrNull(selected.value) ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = if (c.isImage) loadBitmap(ctx, c.uri) else frameAt(c.uri, (c.start + localMs) * 1000)
                    Media.savePng(ctx, bmp!!)
                }.isSuccess
            }
            msg.value = if (ok) R.string.saved else R.string.failed
        }
    }

    // ---------- text / stickers ----------
    fun addText(s: String, size: Int, atMs: Long): Long {
        if (s.isBlank()) return -1
        val id = System.nanoTime()
        edit { it.copy(texts = it.texts + TextItem(id, s.trim(), atMs, atMs + 3000, size = size)) }
        return id
    }
    fun updateText(id: Long, drag: Boolean, f: (TextItem) -> TextItem) {
        val g: (Project) -> Project = { p -> p.copy(texts = p.texts.map { if (it.id == id) f(it) else it }) }
        if (drag) live(g) else edit(g)
    }
    fun removeText(id: Long) = edit { p -> p.copy(texts = p.texts.filter { it.id != id }) }

    fun addSticker(u: Uri, atMs: Long): Long {
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val id = System.nanoTime()
        edit { it.copy(stickers = it.stickers + Sticker(id, u, atMs, atMs + 3000)) }
        return id
    }
    fun updateSticker(id: Long, drag: Boolean, f: (Sticker) -> Sticker) {
        val g: (Project) -> Project = { p -> p.copy(stickers = p.stickers.map { if (it.id == id) f(it) else it }) }
        if (drag) live(g) else edit(g)
    }
    fun removeSticker(id: Long) = edit { p -> p.copy(stickers = p.stickers.filter { it.id != id }) }

    fun importSrt(u: Uri) {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { Srt.parse(ctx.contentResolver.openInputStream(u)!!.bufferedReader().readText()) }.getOrNull()
            }
            if (items.isNullOrEmpty()) { msg.value = R.string.failed; return@launch }
            val base = System.nanoTime()
            edit { p ->
                p.copy(texts = p.texts + items.mapIndexed { i, (s, e, t) -> TextItem(base + i, t, s, e, y = -0.75f, size = 52, bg = true) })
            }
        }
    }

    fun duplicateDraft(id: Long) {
        ProjectStore.copy(ctx, id, System.currentTimeMillis())
        refreshDrafts()
    }

    fun ensureWave(u: Uri) {
        val k = u.toString()
        if (waves.value.containsKey(k) || !pendingWaves.add(k)) return
        viewModelScope.launch(Dispatchers.IO) {
            val pk = Waveform.peaks(ctx, u)
            waves.value = waves.value + (k to (pk ?: FloatArray(0)))
        }
    }

    /** Repeat the selected audio track back to back until the end of the project. */
    fun fillAudioToEnd(id: Long) {
        val p = _p.value
        val a = p.audio.firstOrNull { it.id == id } ?: return
        if (a.lenMs < 300) return
        val extra = mutableListOf<AudioTrack>()
        var at = a.startMs + a.lenMs
        var n = 0
        while (at < p.totalMs - 200 && n < 50) { extra += a.copy(id = System.nanoTime() + n, startMs = at); at += a.lenMs; n++ }
        if (extra.isNotEmpty()) edit { pr -> pr.copy(audio = pr.audio + extra) }
    }

    /** Sequential transition between the selected clip and the next one: the first leaves, then the second enters. */
    fun setTransition(type: Int, ms: Long, drag: Boolean = false) {
        val i = selected.value
        val g: (Project) -> Project = g@{ p ->
            val a = p.clips.getOrNull(i) ?: return@g p
            val b = p.clips.getOrNull(i + 1) ?: return@g p
            val (outA, inA) = when (type) {
                2 -> Anim.SLIDE_LEFT to Anim.SLIDE_RIGHT
                3 -> Anim.SLIDE_RIGHT to Anim.SLIDE_LEFT
                4 -> Anim.SLIDE_DOWN to Anim.SLIDE_UP
                5 -> Anim.SLIDE_UP to Anim.SLIDE_DOWN
                6 -> Anim.ZOOM to Anim.ZOOM
                7 -> Anim.POP to Anim.POP
                else -> Anim.NONE to Anim.NONE
            }
            val fade = if (type == 1) ms else 0L
            val l = p.clips.toMutableList()
            l[i] = a.copy(animOut = outA, fadeOut = fade, animMs = ms)
            l[i + 1] = b.copy(animIn = inA, fadeIn = fade, animMs = ms)
            p.copy(clips = l)
        }
        if (drag) live(g) else edit(g)
    }

    fun addColorClip(kind: Int) {
        val p = _p.value
        val ref = p.clips.getOrNull(selected.value) ?: p.clips.firstOrNull()
        val aspect = if (ref != null && ref.w > 0 && ref.h > 0) ref.w / ref.h.toFloat() else 16f / 9f
        val (uri, w, h) = ShapeGen.solid(ctx, kind, aspect)
        val clip = Clip(id = System.nanoTime(), uri = uri, durMs = 20000, end = 3000, isImage = true, w = w, h = h)
        val at = (selected.value + 1).coerceIn(0, p.clips.size)
        edit { pr -> pr.copy(clips = pr.clips.toMutableList().also { it.add(at.coerceAtMost(it.size), clip) }) }
        selected.value = at.coerceAtMost(maxOf(0, _p.value.clips.size - 1))
    }

    /** Measures the brightness and contrast of the current frame and adjusts the clip accordingly. */
    fun autoEnhance(localMs: Long) {
        val c = _p.value.clips.getOrNull(selected.value) ?: return
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = if (c.isImage) loadBitmap(ctx, c.uri) else frameAt(c.uri, (c.start + localMs) * 1000)!!
                    val s = Bitmap.createScaledBitmap(bmp, 64, 64, true)
                    val px = IntArray(64 * 64)
                    s.getPixels(px, 0, 64, 0, 0, 64, 64)
                    var sum = 0.0
                    var sq = 0.0
                    for (v in px) {
                        val l = (0.299 * ((v shr 16) and 255) + 0.587 * ((v shr 8) and 255) + 0.114 * (v and 255)) / 255.0
                        sum += l; sq += l * l
                    }
                    val mean = sum / px.size
                    mean to kotlin.math.sqrt(maxOf(0.0, sq / px.size - mean * mean))
                }.getOrNull()
            }
            if (st == null) { msg.value = R.string.failed; return@launch }
            val (mean, std) = st
            updateClip(false) { x ->
                x.copy(
                    bright = ((0.46 - mean) * 0.8).toFloat().coerceIn(-0.25f, 0.25f),
                    contrast = ((0.23 - std) * 1.5).toFloat().coerceIn(-0.1f, 0.3f),
                    sat = 0.12f
                )
            }
        }
    }

    fun setLut(u: Uri) {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(u)!!.bufferedReader().readText()
                    parseCube(text)
                    File(ctx.filesDir, "lut_${System.nanoTime()}.cube").also { it.writeText(text) }
                }.getOrNull()
            }
            if (f == null) { msg.value = R.string.failed; return@launch }
            updateClip(false) { it.copy(lut = f.absolutePath) }
        }
    }
    fun clearLut() = updateClip(false) { it.copy(lut = null) }

    fun applyToAll() {
        val c = _p.value.clips.getOrNull(selected.value) ?: return
        edit { p ->
            p.copy(clips = p.clips.map {
                it.copy(fx = c.fx, bright = c.bright, contrast = c.contrast, sat = c.sat, hue = c.hue, temp = c.temp,
                    expo = c.expo, shadows = c.shadows, highlights = c.highlights, matte = c.matte, tint = c.tint)
            })
        }
    }

    fun addShape(kind: Int, color: Int, atMs: Long): Long = addSticker(ShapeGen.make(ctx, kind, color), atMs)

    /** Split the selected text (1), sticker (2) or audio track (3) at [atMs] on the timeline. */
    fun splitLayer(kind: Int, id: Long, atMs: Long) {
        val p = _p.value
        when (kind) {
            1 -> {
                val t = p.texts.firstOrNull { it.id == id } ?: return
                if (atMs - t.start < 300 || t.end - atMs < 300) { msg.value = R.string.failed; return }
                edit { pr -> pr.copy(texts = pr.texts.flatMap { if (it.id == id) listOf(it.copy(end = atMs), it.copy(id = System.nanoTime(), start = atMs)) else listOf(it) }) }
            }
            2 -> {
                val s = p.stickers.firstOrNull { it.id == id } ?: return
                if (atMs - s.start < 300 || s.end - atMs < 300) { msg.value = R.string.failed; return }
                edit { pr -> pr.copy(stickers = pr.stickers.flatMap { if (it.id == id) listOf(it.copy(end = atMs), it.copy(id = System.nanoTime(), start = atMs)) else listOf(it) }) }
            }
            3 -> {
                val a = p.audio.firstOrNull { it.id == id } ?: return
                val local = atMs - a.startMs
                if (local < 300 || a.lenMs - local < 300) { msg.value = R.string.failed; return }
                edit { pr ->
                    pr.copy(audio = pr.audio.flatMap {
                        if (it.id == id) listOf(it.copy(srcEnd = it.srcStart + local), it.copy(id = System.nanoTime(), startMs = atMs, srcStart = it.srcStart + local)) else listOf(it)
                    })
                }
            }
        }
    }

    fun duplicateLayer(kind: Int, id: Long) {
        when (kind) {
            1 -> edit { p -> p.copy(texts = p.texts.flatMap { if (it.id == id) listOf(it, it.copy(id = System.nanoTime(), start = it.end, end = it.end + (it.end - it.start))) else listOf(it) }) }
            2 -> edit { p -> p.copy(stickers = p.stickers.flatMap { if (it.id == id) listOf(it, it.copy(id = System.nanoTime(), start = it.end, end = it.end + (it.end - it.start))) else listOf(it) }) }
            3 -> edit { p -> p.copy(audio = p.audio.flatMap { if (it.id == id) listOf(it, it.copy(id = System.nanoTime(), startMs = it.startMs + it.lenMs)) else listOf(it) }) }
        }
    }

    fun deleteLayer(kind: Int, id: Long) {
        when (kind) {
            1 -> removeText(id)
            2 -> removeSticker(id)
            3 -> removeAudio(id)
        }
    }

    fun setRatio(r: Float) = edit { it.copy(ratio = r) }
    fun setFit(f: Boolean) = edit { it.copy(fit = f) }

    // ---------- audio tracks ----------
    private fun displayName(u: Uri): String = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: "Audio"

    fun addAudio(u: Uri, label: String?, kind: Int, atMs: Long, takePerm: Boolean = false) {
        viewModelScope.launch {
            val dur = withContext(Dispatchers.IO) {
                if (takePerm) runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val r = MediaMetadataRetriever()
                try { r.setDataSource(ctx, u); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
                catch (e: Exception) { null } finally { r.release() }
            }
            if (dur == null || dur < 300) { msg.value = R.string.failed; return@launch }
            val name = label ?: withContext(Dispatchers.IO) { displayName(u) }
            edit { it.copy(audio = it.audio + AudioTrack(System.nanoTime(), u, name, kind, atMs.coerceAtLeast(0), 0, dur, dur)) }
        }
    }
    fun updateAudio(id: Long, drag: Boolean, f: (AudioTrack) -> AudioTrack) {
        val g: (Project) -> Project = { p -> p.copy(audio = p.audio.map { if (it.id == id) f(it) else it }) }
        if (drag) live(g) else edit(g)
    }
    fun removeAudio(id: Long) = edit { p -> p.copy(audio = p.audio.filter { it.id != id }) }

    fun extractAudio() {
        val i = selected.value
        val p = _p.value
        val c = p.clips.getOrNull(i) ?: return
        if (c.isImage) return
        val at = p.clips.take(i).sumOf { it.outMs }
        val track = AudioTrack(System.nanoTime(), c.uri, displayName(c.uri), 4, at, c.start, c.end, c.durMs)
        edit { pr -> pr.copy(clips = pr.clips.toMutableList().also { l -> l[i] = c.copy(volume = 0f) }, audio = pr.audio + track) }
    }

    fun addSfx(kind: Int, atMs: Long) {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { AudioGen.sfx(ctx, kind) }.getOrNull() }
            if (f == null) msg.value = R.string.failed else addAudio(Uri.fromFile(f), AudioGen.SFX_NAMES[kind], 2, atMs)
        }
    }

    fun speak(text: String, atMs: Long) {
        if (text.isBlank()) return
        val f = File(ctx.filesDir, "tts_${System.nanoTime()}.wav")
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx, TextToSpeech.OnInitListener { status ->
            val t = engine
            if (status != TextToSpeech.SUCCESS || t == null) { msg.value = R.string.failed; t?.shutdown(); return@OnInitListener }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    viewModelScope.launch { addAudio(Uri.fromFile(f), "TTS", 3, atMs); t.shutdown() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { msg.value = R.string.failed; t.shutdown() }
            })
            if (t.synthesizeToFile(text, null, f, "kf") != TextToSpeech.SUCCESS) { msg.value = R.string.failed; t.shutdown() }
        })
    }

    fun startVoice(atMs: Long) {
        val f = File(ctx.filesDir, "voice_${System.currentTimeMillis()}.m4a")
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128000); r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath); r.prepare(); r.start()
            recorder = r; voiceFile = f; voiceStart = atMs; recording.value = true
        } catch (e: Exception) { msg.value = R.string.failed }
    }
    fun stopVoice() {
        val r = recorder ?: return
        recorder = null; recording.value = false
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = voiceFile
        if (ok && f != null) addAudio(Uri.fromFile(f), "Voice", 1, voiceStart) else msg.value = R.string.failed
    }

    override fun onCleared() { runCatching { recorder?.release() }; recorder = null }

    // ---------- export ----------
    fun startExport(height: Int, audioOnly: Boolean, hevc: Boolean = false, quality: Int = 1) {
        val p = _p.value
        if (p.clips.isEmpty()) return
        val out = File(ctx.cacheDir, "kf_${System.currentTimeMillis()}." + if (audioOnly) "m4a" else "mp4")
        export.value = ExportUi.Running(0f)
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(
                            (height * height * 16f / 9f * 30f * floatArrayOf(.05f, .09f, .14f)[quality.coerceIn(0, 2)] * (if (hevc) .7f else 1f)).toInt()
                        ).build()
                    ).build()
            )
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    viewModelScope.launch {
                        export.value = runCatching { Media.publish(ctx, out, audioOnly) }
                            .fold({ ExportUi.Done(it, audioOnly) }, { ExportUi.Failed(it.message) })
                    }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    export.value = ExportUi.Failed(exportException.message)
                }
            }).build()
        transformer = t
        try { t.start(Exporter.build(ctx, p, height, audioOnly), out.absolutePath) }
        catch (e: Exception) { export.value = ExportUi.Failed(e.message); return }
        viewModelScope.launch {
            val h = ProgressHolder()
            while (export.value is ExportUi.Running) {
                if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) export.value = ExportUi.Running(h.progress / 100f)
                delay(250)
            }
        }
    }
    fun cancelExport() { transformer?.cancel(); export.value = ExportUi.Idle }
    fun dismissExport() { export.value = ExportUi.Idle }
}
