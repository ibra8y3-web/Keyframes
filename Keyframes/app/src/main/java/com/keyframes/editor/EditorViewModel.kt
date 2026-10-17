@file:OptIn(UnstableApi::class)

package com.keyframes.editor

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.net.Uri
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

sealed interface ExportUi {
    data object Idle : ExportUi
    data class Running(val progress: Float) : ExportUi
    data class Done(val uri: Uri, val audio: Boolean) : ExportUi
    data class Failed(val reason: String?) : ExportUi
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val _p = MutableStateFlow(ProjectStore.load(app))
    val project = _p.asStateFlow()
    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)
    val selected = MutableStateFlow(0)
    val export = MutableStateFlow<ExportUi>(ExportUi.Idle)
    val msg = MutableStateFlow<Int?>(null)
    private var dragging = false
    private var transformer: Transformer? = null

    private fun sync() {
        canUndo.value = undo.isNotEmpty(); canRedo.value = redo.isNotEmpty()
        ProjectStore.save(ctx, _p.value)
    }

    private fun push() {
        undo.addLast(_p.value); if (undo.size > 60) undo.removeFirst(); redo.clear()
    }

    fun edit(f: (Project) -> Project) { push(); _p.value = f(_p.value); fixSel(); sync() }
    fun live(f: (Project) -> Project) { if (!dragging) { push(); dragging = true }; _p.value = f(_p.value) }
    fun liveEnd() { dragging = false; sync() }
    private fun fixSel() { selected.value = selected.value.coerceIn(0, maxOf(0, _p.value.clips.size - 1)) }

    fun undo() { if (undo.isEmpty()) return; redo.addLast(_p.value); _p.value = undo.removeLast(); fixSel(); sync() }
    fun redo() { if (redo.isEmpty()) return; undo.addLast(_p.value); _p.value = redo.removeLast(); fixSel(); sync() }

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
                        return@mapNotNull if (o.outWidth > 0) Clip(id = System.nanoTime(), uri = u, durMs = 20000, end = 3000, isImage = true) else null
                    }
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, u)
                        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != null
                        if (d == null || !hasVideo) null else Clip(id = System.nanoTime(), uri = u, durMs = d)
                    } catch (e: Exception) { null } finally { r.release() }
                }
            }
            if (new.isEmpty()) msg.value = R.string.failed else edit { it.copy(clips = it.clips + new) }
        }
    }

    fun setMusic(u: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        edit { it.copy(music = Music(u)) }
    }

    fun split(localMs: Long) {
        val i = selected.value
        val c = _p.value.clips.getOrNull(i) ?: return
        val at = c.start + localMs
        if (at - c.start < 300 || c.end - at < 300) { msg.value = R.string.failed; return }
        edit {
            val l = it.clips.toMutableList()
            l[i] = c.copy(end = at); l.add(i + 1, c.copy(id = System.nanoTime(), start = at))
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

    fun addText(s: String) {
        if (s.isBlank()) return
        edit { it.copy(texts = it.texts + TextItem(System.nanoTime(), s.trim(), 0, maxOf(1000, it.totalMs))) }
    }
    fun updateText(id: Long, drag: Boolean, f: (TextItem) -> TextItem) {
        val g: (Project) -> Project = { p -> p.copy(texts = p.texts.map { if (it.id == id) f(it) else it }) }
        if (drag) live(g) else edit(g)
    }
    fun removeText(id: Long) = edit { p -> p.copy(texts = p.texts.filter { it.id != id }) }
    private fun frameAt(u: Uri, us: Long): android.graphics.Bitmap? {
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
                    File(ctx.filesDir, "freeze_${System.nanoTime()}.png").also { out -> out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                }.getOrNull()
            }
            if (f == null) { msg.value = R.string.failed; return@launch }
            val at = c.start + localMs
            val img = c.copy(id = System.nanoTime(), uri = Uri.fromFile(f), durMs = 20000, start = 0, end = 3000, speed = 1f, volume = 1f, fadeIn = 0, fadeOut = 0, isImage = true)
            edit { p ->
                val l = p.clips.toMutableList()
                if (i in l.indices) {
                    if (at - c.start >= 300 && c.end - at >= 300) {
                        l[i] = c.copy(end = at); l.add(i + 1, img); l.add(i + 2, c.copy(id = System.nanoTime(), start = at))
                    } else l.add(i + 1, img)
                }
                p.copy(clips = l)
            }
        }
    }

    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    val recording = MutableStateFlow(false)

    fun startVoice() {
        val f = File(ctx.filesDir, "voice_${System.currentTimeMillis()}.m4a")
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128000); r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath); r.prepare(); r.start()
            recorder = r; voiceFile = f; recording.value = true
        } catch (e: Exception) { msg.value = R.string.failed }
    }

    fun stopVoice() {
        val r = recorder ?: return
        recorder = null; recording.value = false
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = voiceFile
        if (ok && f != null) edit { it.copy(voice = Music(Uri.fromFile(f), 1f)) } else msg.value = R.string.failed
    }

    fun voiceVolume(v: Float) = live { p -> p.copy(voice = p.voice?.copy(volume = v)) }
    fun removeVoice() = edit { it.copy(voice = null) }
    fun newProject() { selected.value = 0; edit { Project() } }

    override fun onCleared() { runCatching { recorder?.release() }; recorder = null }

    fun addSticker(u: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        edit { it.copy(stickers = it.stickers + Sticker(System.nanoTime(), u, 0, maxOf(1000, it.totalMs))) }
    }
    fun updateSticker(id: Long, drag: Boolean, f: (Sticker) -> Sticker) {
        val g: (Project) -> Project = { p -> p.copy(stickers = p.stickers.map { if (it.id == id) f(it) else it }) }
        if (drag) live(g) else edit(g)
    }
    fun removeSticker(id: Long) = edit { p -> p.copy(stickers = p.stickers.filter { it.id != id }) }
    fun setRatio(r: Float) = edit { it.copy(ratio = r) }
    fun musicVolume(v: Float) = live { p -> p.copy(music = p.music?.copy(volume = v)) }
    fun removeMusic() = edit { it.copy(music = null) }

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
