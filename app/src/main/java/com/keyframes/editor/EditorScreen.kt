@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.keyframes.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class Tool(val icon: ImageVector, val label: Int, val action: Boolean = false) {
    TRIM(Icons.Default.Timeline, R.string.trim),
    SPLIT(Icons.Default.ContentCut, R.string.split, true),
    FREEZE(Icons.Default.PauseCircle, R.string.freeze, true),
    SPEED(Icons.Default.Speed, R.string.speed),
    ZOOM(Icons.Default.ZoomIn, R.string.zoom),
    ROTATE(Icons.Default.RotateRight, R.string.rotate, true),
    MIRROR(Icons.Default.Flip, R.string.mirror, true),
    FLIPV(Icons.Default.SwapVert, R.string.flip_v, true),
    LEFT(Icons.Default.ArrowBack, 0, true),
    RIGHT(Icons.Default.ArrowForward, 0, true),
    DUP(Icons.Default.ContentCopy, R.string.duplicate, true),
    DEL(Icons.Default.Delete, R.string.delete, true),
    VOLUME(Icons.Default.VolumeUp, R.string.volume),
    PITCH(Icons.Default.GraphicEq, R.string.pitch),
    AUDIO(Icons.Default.LibraryMusic, R.string.cat_audio),
    TEXT(Icons.Default.TextFields, R.string.text),
    STICKER(Icons.Default.Image, R.string.sticker),
    SRT(Icons.Default.Subtitles, R.string.srt, true),
    FILTER(Icons.Default.AutoAwesome, R.string.filter),
    ADJUST(Icons.Default.Tune, R.string.adjust),
    MOTION(Icons.Default.Animation, R.string.motion),
    FADE(Icons.Default.Gradient, R.string.fade),
    RATIO(Icons.Default.AspectRatio, R.string.ratio)
}

enum class Cat(val icon: ImageVector, val label: Int, val tools: List<Tool>) {
    EDIT(Icons.Default.ContentCut, R.string.cat_edit, listOf(Tool.TRIM, Tool.SPLIT, Tool.FREEZE, Tool.SPEED, Tool.ZOOM, Tool.ROTATE, Tool.MIRROR, Tool.FLIPV, Tool.LEFT, Tool.RIGHT, Tool.DUP, Tool.DEL)),
    AUDIO(Icons.Default.MusicNote, R.string.cat_audio, listOf(Tool.AUDIO, Tool.VOLUME, Tool.PITCH)),
    TEXT(Icons.Default.TextFields, R.string.text, listOf(Tool.TEXT, Tool.STICKER, Tool.SRT)),
    FX(Icons.Default.AutoAwesome, R.string.cat_effects, listOf(Tool.FILTER, Tool.ADJUST, Tool.MOTION, Tool.FADE)),
    CANVAS(Icons.Default.AspectRatio, R.string.cat_canvas, listOf(Tool.RATIO))
}

private val ACCENT = Color(0xFF00E5C3)
private val VIOLET = Color(0xFF7C4DFF)
private val SHEET = Color(0xFF14141F)
private val MUTED = Color(0xFF8A8AA8)
private val FONT_FAMILIES = listOf(FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace, FontFamily.Cursive)

private fun fmt(ms: Long) = "%d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

fun fmtS(ms: Long) = "%02d:%02d".format(ms / 60000, (ms / 1000) % 60)

private fun fxLabel(f: Fx) = when (f) {
    Fx.NONE -> R.string.f_none; Fx.GRAY -> R.string.f_gray; Fx.SEPIA -> R.string.f_sepia
    Fx.INVERT -> R.string.f_invert; Fx.WARM -> R.string.f_warm; Fx.COOL -> R.string.f_cool
    Fx.CINE -> R.string.f_cine; Fx.VIVID -> R.string.f_vivid; Fx.FADE -> R.string.f_fade; Fx.NOIR -> R.string.f_noir
    Fx.MIDNIGHT -> R.string.f_midnight; Fx.VINTAGE -> R.string.f_vintage; Fx.GOLDEN -> R.string.f_golden
}

@Composable
private fun animLabel(a: Anim): String = when (a) {
    Anim.NONE -> stringResource(R.string.f_none)
    Anim.SLIDE_LEFT -> "←"
    Anim.SLIDE_RIGHT -> "→"
    Anim.SLIDE_UP -> "↑"
    Anim.SLIDE_DOWN -> "↓"
    Anim.ZOOM -> stringResource(R.string.zoom)
    Anim.POP -> stringResource(R.string.m_pop)
}

@Composable
private fun textAnimLabel(i: Int): String = when (i) {
    0 -> stringResource(R.string.f_none)
    1 -> stringResource(R.string.fade)
    2 -> stringResource(R.string.m_pop)
    3 -> "↑"
    else -> "Abc▌"
}

@Composable
private fun loopLabel(l: Loop): String = when (l) {
    Loop.NONE -> stringResource(R.string.f_none)
    Loop.KEN_IN -> stringResource(R.string.m_kenin)
    Loop.KEN_OUT -> stringResource(R.string.m_kenout)
    Loop.PULSE -> stringResource(R.string.m_pulse)
    Loop.SHAKE -> stringResource(R.string.m_shake)
}

private fun applyPreview(player: ExoPlayer, c: Clip, recording: Boolean) {
    player.playbackParameters = PlaybackParameters(if (c.isImage) 1f else c.speed, if (c.isImage) 1f else c.pitch)
    player.volume = if (recording) 0f else c.volume.coerceIn(0f, 1f)
    player.setVideoEffects(clipEffects(c))
}

private class PanelCtx(
    val recording: Boolean, val selKind: Int, val selId: Long,
    val abs: () -> Long, val onSelect: (Int, Long) -> Unit, val onClear: () -> Unit,
    val pickAudio: () -> Unit, val pickImage: () -> Unit, val onRecord: () -> Unit,
    val onAddKey: () -> Unit, val onSpeak: (String) -> Unit, val onSfx: (Int) -> Unit, val onExtract: () -> Unit
)

@Composable
fun EditorScreen(vm: EditorViewModel = viewModel(), startTool: Tool? = null, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val p by vm.project.collectAsStateWithLifecycle()
    val sel by vm.selected.collectAsStateWithLifecycle()
    val exp by vm.export.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    val canU by vm.canUndo.collectAsStateWithLifecycle()
    val canR by vm.canRedo.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    var cat by remember(startTool) { mutableStateOf(Cat.entries.firstOrNull { c -> startTool != null && startTool in c.tools } ?: Cat.EDIT) }
    var tool by remember(startTool) { mutableStateOf(startTool?.takeIf { !it.action }) }
    var selKind by remember { mutableIntStateOf(0) }
    var selId by remember { mutableLongStateOf(-1L) }
    var showExport by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    val absState = remember { mutableStateOf(0L) }
    val pNow = rememberUpdatedState(p)
    BackHandler(enabled = full) { full = false }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    val audioPlayers = remember { HashMap<Long, ExoPlayer>() }
    val audioLoaded = remember { HashMap<Long, Triple<Uri, Long, Long>>() }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    DisposableEffect(Unit) { onDispose { player.release(); audioPlayers.values.forEach { it.release() }; audioPlayers.clear() } }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addMedia(it) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.addAudio(u, null, 0, absState.value, true) } }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { u -> val id = vm.addSticker(u, absState.value); selKind = 2; selId = id; cat = Cat.TEXT; tool = Tool.STICKER }
    }
    val pickSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importSrt) }
    LaunchedEffect(msg) { msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.msg.value = null } }

    val structure = p.clips.map { Triple(it.uri, it.start, it.end) }
    val cur = p.clips.getOrNull(sel)

    LaunchedEffect(structure) {
        delay(200)
        player.setMediaItems(
            p.clips.map {
                if (it.isImage) MediaItem.Builder().setUri(it.uri).setImageDurationMs(it.lenMs).build()
                else MediaItem.Builder().setUri(it.uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(it.start).setEndPositionMs(it.end).build()
                ).build()
            }, sel.coerceIn(0, maxOf(0, p.clips.size - 1)), 0L
        )
        player.prepare()
    }
    LaunchedEffect(cur, sel, structure, recording) { cur?.let { applyPreview(player, it, recording) } }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { vm.selected.value = player.currentMediaItemIndex }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // absolute playhead position on the timeline, in output milliseconds
    LaunchedEffect(Unit) {
        while (true) {
            val pp = pNow.value
            val idx = player.currentMediaItemIndex
            var acc = 0L
            for (i in 0 until minOf(idx, pp.clips.size)) acc += pp.clips[i].outMs
            val sp = pp.clips.getOrNull(idx)?.speed ?: 1f
            absState.value = acc + (player.currentPosition / sp).toLong()
            delay(50)
        }
    }
    // preview players for every audio track
    LaunchedEffect(p.audio, recording) {
        val ids = p.audio.map { it.id }.toSet()
        audioPlayers.keys.filter { it !in ids }.forEach { id -> audioPlayers.remove(id)?.release(); audioLoaded.remove(id) }
        p.audio.forEach { a ->
            val pl = audioPlayers.getOrPut(a.id) { ExoPlayer.Builder(ctx).build() }
            val key = Triple(a.uri, a.srcStart, a.srcEnd)
            if (audioLoaded[a.id] != key) {
                pl.setMediaItem(
                    MediaItem.Builder().setUri(a.uri).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a.srcStart).setEndPositionMs(a.srcEnd).build()
                    ).build()
                )
                pl.prepare(); audioLoaded[a.id] = key
            }
            pl.volume = if (recording) 0f else a.volume.coerceIn(0f, 1f)
        }
    }
    LaunchedEffect(playing, p.audio) {
        if (!playing) { audioPlayers.values.forEach { it.pause() }; return@LaunchedEffect }
        while (true) {
            val at = absState.value
            p.audio.forEach { a ->
                val pl = audioPlayers[a.id] ?: return@forEach
                val want = at - a.startMs
                if (want < 0 || want >= a.lenMs) { if (pl.isPlaying) pl.pause() }
                else {
                    if (abs(pl.currentPosition - want) > 250) pl.seekTo(want)
                    if (!pl.isPlaying) pl.play()
                }
            }
            delay(100)
        }
    }

    fun seekAbs(ms: Long) {
        val clips = pNow.value.clips
        if (clips.isEmpty()) return
        var acc = 0L
        for ((i, c) in clips.withIndex()) {
            if (ms < acc + c.outMs || i == clips.lastIndex) {
                val local = ((ms - acc).coerceIn(0, c.outMs) * c.speed).toLong().coerceIn(0, c.lenMs)
                vm.selected.value = i; player.seekTo(i, local); return
            }
            acc += c.outMs
        }
    }
    fun togglePlay() {
        if (playing) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
            player.play()
        }
    }
    fun shareTo(e: ExportUi.Done, pkg: String?) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = if (e.audio) "audio/mp4" else "video/mp4"
            putExtra(Intent.EXTRA_STREAM, e.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        try { ctx.startActivity(if (pkg == null) Intent.createChooser(i, null) else i) } catch (ex: Exception) { vm.msg.value = R.string.failed }
    }
    fun beginRecording() { vm.startVoice(absState.value); player.play() }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) beginRecording() else vm.msg.value = R.string.failed
    }
    fun toggleRecord() {
        if (recording) { vm.stopVoice(); player.pause() }
        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun onSelect(kind: Int, id: Long) {
        selKind = kind; selId = id
        when (kind) {
            0 -> { vm.selected.value = id.toInt(); player.seekTo(id.toInt(), 0) }
            1 -> { cat = Cat.TEXT; tool = Tool.TEXT }
            2 -> { cat = Cat.TEXT; tool = Tool.STICKER }
            3 -> { cat = Cat.AUDIO; tool = Tool.AUDIO }
        }
    }
    fun toolEnabled(t: Tool) = when (t) {
        Tool.AUDIO, Tool.TEXT, Tool.RATIO, Tool.STICKER, Tool.SRT -> true
        Tool.SPEED, Tool.VOLUME, Tool.PITCH, Tool.FREEZE -> cur != null && !cur.isImage
        Tool.SPLIT, Tool.DUP, Tool.DEL -> cur != null || selKind != 0
        else -> cur != null
    }
    fun onTool(t: Tool) {
        when (t) {
            Tool.SPLIT -> if (selKind == 0) vm.split(player.currentPosition) else vm.splitLayer(selKind, selId, absState.value)
            Tool.FREEZE -> vm.freeze(player.currentPosition)
            Tool.ROTATE -> vm.updateClip(false) { it.copy(rot = (it.rot + 90) % 360) }
            Tool.MIRROR -> vm.updateClip(false) { it.copy(mirror = !it.mirror) }
            Tool.FLIPV -> vm.updateClip(false) { it.copy(flipV = !it.flipV) }
            Tool.LEFT -> vm.move(-1)
            Tool.RIGHT -> vm.move(1)
            Tool.DUP -> if (selKind == 0) vm.duplicate() else vm.duplicateLayer(selKind, selId)
            Tool.DEL -> if (selKind == 0) vm.delete() else { vm.deleteLayer(selKind, selId); selKind = 0 }
            Tool.SRT -> pickSrt.launch(arrayOf("*/*"))
            else -> tool = if (tool == t) null else t
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!full) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close)) }
            Text(stringResource(R.string.edit_project), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(22.dp))
                    .background(Brush.horizontalGradient(listOf(VIOLET, Color(0xFF00BFA5))), RoundedCornerShape(22.dp), alpha = if (p.clips.isNotEmpty()) 1f else 0.35f)
                    .clickable(enabled = p.clips.isNotEmpty()) { showExport = true }.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.export), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (full) 0.dp else 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(if (full) 0.dp else 18.dp)).background(Color.Black)
                .clickable(enabled = cur != null) { togglePlay() },
            contentAlignment = Alignment.Center
        ) {
            val m = if (p.ratio > 0f) Modifier.aspectRatio(p.ratio) else Modifier.fillMaxSize()
            AndroidView(
                modifier = m,
                factory = { c -> PlayerView(c).apply { useController = false; this.player = player } },
                update = { it.resizeMode = if (p.ratio > 0f && !p.fit) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT }
            )
            PreviewOverlays(p, absState)
            if (p.clips.isNotEmpty()) {
                Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton({ vm.snapshot(player.currentPosition) }) { Icon(Icons.Default.PhotoCamera, stringResource(R.string.snapshot)) }
                    IconButton({ full = !full }) { Icon(if (full) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, null) }
                }
                if (!playing) Icon(Icons.Default.PlayArrow, null, Modifier.align(Alignment.Center).size(64.dp).background(Color(0x66000000), CircleShape).padding(8.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideoLibrary, null, Modifier.size(48.dp), tint = MUTED)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.empty_hint), color = MUTED)
                }
            }
        }
        if (!full) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeText(absState, p.totalMs, Modifier.weight(1f))
                IconButton({ vm.undo() }, enabled = canU) { Icon(Icons.Default.Undo, stringResource(R.string.undo)) }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(VIOLET, Color(0xFF00BFA5))))
                        .clickable(enabled = cur != null) { togglePlay() },
                    contentAlignment = Alignment.Center
                ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
                IconButton({ vm.redo() }, enabled = canR) { Icon(Icons.Default.Redo, stringResource(R.string.redo)) }
                Spacer(Modifier.weight(1f))
            }
            Timeline(
                vm, p, sel, absState, selKind, selId, loader, if (tool != null) 130 else 176,
                onSelect = { k, id -> onSelect(k, id) },
                onSeek = { ms -> seekAbs(ms) },
                onScrub = { if (player.isPlaying) player.pause() },
                onAdd = { pickVideo.launch(arrayOf("video/*", "image/*")) }
            )
            Column(Modifier.fillMaxWidth().background(SHEET, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))) {
                tool?.let { t ->
                    Panel(
                        t, p, cur, vm,
                        PanelCtx(
                            recording, selKind, selId, { absState.value }, { k, id -> onSelect(k, id) }, { selKind = 0 },
                            { pickAudio.launch(arrayOf("audio/*")) }, { pickImage.launch(arrayOf("image/*")) }, { toggleRecord() },
                            { vm.addKey(player.currentPosition) }, { vm.speak(it, absState.value) }, { vm.addSfx(it, absState.value) }, { vm.extractAudio() }
                        )
                    )
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(cat.tools) { t ->
                        val on = tool == t
                        val en = toolEnabled(t)
                        Column(
                            Modifier.padding(horizontal = 2.dp).alpha(if (en) 1f else 0.35f).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Color(0x3300E5C3) else Color.Transparent)
                                .clickable(enabled = en) { onTool(t) }.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(t.icon, null, tint = if (on) ACCENT else MaterialTheme.colorScheme.onSurface)
                            if (t == Tool.LEFT || t == Tool.RIGHT) Text(if (t == Tool.LEFT) "◀" else "▶", style = MaterialTheme.typography.labelSmall)
                            else Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Cat.entries.forEach { c ->
                        val on = c == cat
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { cat = c; tool = null }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(c.icon, null, tint = if (on) Color(0xFFB39DFF) else MUTED)
                            Text(stringResource(c.label), style = MaterialTheme.typography.labelSmall, color = if (on) Color(0xFFB39DFF) else MUTED, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (showExport) {
        var h by remember { mutableIntStateOf(1080) }
        var hevc by remember { mutableStateOf(false) }
        var q by remember { mutableIntStateOf(1) }
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text(stringResource(R.string.export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(480, 720, 1080, 1440, 2160).forEach { FilterChip(h == it, { h = it }, { Text(if (it == 2160) "4K" else "${it}p") }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!hevc, { hevc = false }, { Text("H.264") })
                        FilterChip(hevc, { hevc = true }, { Text("HEVC") })
                    }
                    Text(stringResource(R.string.quality), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.low, R.string.medium, R.string.high).forEachIndexed { i, r ->
                            FilterChip(q == i, { q = i }, { Text(stringResource(r)) })
                        }
                    }
                }
            },
            confirmButton = { Button({ showExport = false; vm.startExport(h, false, hevc, q) }) { Text(stringResource(R.string.export)) } },
            dismissButton = { TextButton({ showExport = false; vm.startExport(h, true, hevc, q) }) { Text(stringResource(R.string.audio_only)) } }
        )
    }
    when (val e = exp) {
        is ExportUi.Running -> AlertDialog(
            onDismissRequest = {}, title = { Text(stringResource(R.string.exporting)) },
            text = { LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ vm.cancelExport() }) { Text(stringResource(R.string.cancel)) } }
        )
        is ExportUi.Done -> AlertDialog(
            onDismissRequest = { vm.dismissExport() }, title = { Text(stringResource(R.string.saved)) },
            text = {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("TikTok" to "com.zhiliaoapp.musically", "YouTube" to "com.google.android.youtube", "Instagram" to "com.instagram.android", "WhatsApp" to "com.whatsapp").forEach { (n, pkg) ->
                        AssistChip(onClick = { shareTo(e, pkg) }, label = { Text(n) })
                    }
                }
            },
            confirmButton = { Button({ shareTo(e, null); vm.dismissExport() }) { Text(stringResource(R.string.share)) } },
            dismissButton = { TextButton({ vm.dismissExport() }) { Text(stringResource(R.string.close)) } }
        )
        is ExportUi.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissExport() }, title = { Text(stringResource(R.string.failed)) },
            text = { Text(e.reason ?: "") },
            confirmButton = { TextButton({ vm.dismissExport() }) { Text(stringResource(R.string.close)) } }
        )
        ExportUi.Idle -> {}
    }
}

@Composable
private fun TimeText(abs: State<Long>, total: Long, modifier: Modifier) {
    Text("${fmtS(abs.value)} / ${fmtS(total)}", style = MaterialTheme.typography.labelLarge, color = MUTED, modifier = modifier)
}

@Composable
private fun BoxScope.PreviewOverlays(p: Project, abs: State<Long>) {
    val a = abs.value
    fun prog(s: Long, e: Long) = minOf(((a - s) / 500f).coerceIn(0f, 1f), ((e - a) / 500f).coerceIn(0f, 1f))
    p.stickers.filter { a in it.start until it.end }.forEach { s ->
        val pr = prog(s.start, s.end)
        AsyncImage(
            model = s.uri, contentDescription = null,
            modifier = Modifier.align(BiasAlignment(s.x, -s.y)).fillMaxWidth(s.scale).graphicsLayer {
                alpha = if (s.anim == 1) s.alpha * pr else s.alpha
                val k = if (s.anim == 2) pr.coerceAtLeast(0.01f) else 1f
                scaleX = k; scaleY = k
                translationY = if (s.anim == 3) (1f - pr) * 60f else 0f
            }
        )
    }
    p.texts.filter { a in it.start until it.end }.forEach { t ->
        val pr = prog(t.start, t.end)
        val shown = if (t.anim == 4) (t.text.length * ((a - t.start) / 500f).coerceIn(0f, 1f)).toInt().coerceAtLeast(1) else t.text.length
        Text(
            t.text.take(shown), color = Color(t.color), fontSize = (t.size / 3f).sp,
            fontWeight = if (t.bold) FontWeight.Bold else null,
            fontFamily = FONT_FAMILIES[t.font.coerceIn(0, FONT_FAMILIES.size - 1)],
            modifier = Modifier.align(BiasAlignment(0f, -t.y)).graphicsLayer {
                alpha = if (t.anim == 1) pr else 1f
                val k = if (t.anim == 2) pr.coerceAtLeast(0.01f) else 1f
                scaleX = k; scaleY = k
                translationY = if (t.anim == 3) (1f - pr) * 60f else 0f
            }.background(if (t.bg) Color(0x99000000) else Color.Transparent)
        )
    }
}

@Composable
private fun LS(label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String = "", onEnd: () -> Unit, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.labelMedium, color = ACCENT)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, onValueChangeFinished = onEnd)
    }
}

@Composable
private fun <T> Chips(items: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { FilterChip(it == selected, { onPick(it) }, { Text(label(it)) }) }
    }
}

@Composable
private fun Panel(t: Tool, p: Project, c: Clip?, vm: EditorViewModel, pc: PanelCtx) {
    val end = vm::liveEnd
    Column(
        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (t) {
            Tool.TRIM -> c?.let {
                RangeSlider(
                    value = it.start.toFloat()..it.end.toFloat(),
                    onValueChange = { r ->
                        val s = r.start.toLong(); val e = r.endInclusive.toLong()
                        if (e - s >= 300) vm.updateClip(true) { x -> x.copy(start = s, end = e) }
                    },
                    valueRange = 0f..it.durMs.toFloat(), onValueChangeFinished = end
                )
                Text("${fmt(it.start)} – ${fmt(it.end)}  (${fmt(it.lenMs)})", style = MaterialTheme.typography.labelMedium, color = ACCENT)
            }
            Tool.SPEED -> c?.let {
                Chips(listOf(0.5f, 1f, 1.5f, 2f, 3f), it.speed, { s -> "${s}x" }) { s -> vm.updateClip(false) { x -> x.copy(speed = s) } }
                LS(stringResource(R.string.speed), it.speed, 0.25f..4f, "%.2fx".format(it.speed), end) { v -> vm.updateClip(true) { x -> x.copy(speed = v) } }
            }
            Tool.VOLUME -> c?.let {
                LS(stringResource(R.string.volume), it.volume, 0f..1f, "${(it.volume * 100).toInt()}%", end) { v -> vm.updateClip(true) { x -> x.copy(volume = v) } }
            }
            Tool.PITCH -> c?.let {
                Chips(listOf(0.7f, 1f, 1.4f, 1.7f), it.pitch, { s -> "${s}x" }) { s -> vm.updateClip(false) { x -> x.copy(pitch = s) } }
                LS(stringResource(R.string.pitch), it.pitch, 0.5f..2f, "%.2fx".format(it.pitch), end) { v -> vm.updateClip(true) { x -> x.copy(pitch = v) } }
            }
            Tool.FILTER -> c?.let {
                Chips(Fx.entries.toList(), it.fx, { f -> stringResource(fxLabel(f)) }) { f -> vm.updateClip(false) { x -> x.copy(fx = f) } }
            }
            Tool.ADJUST -> c?.let {
                LS(stringResource(R.string.brightness), it.bright, -1f..1f, "", end) { v -> vm.updateClip(true) { x -> x.copy(bright = v) } }
                LS(stringResource(R.string.contrast), it.contrast, -1f..1f, "", end) { v -> vm.updateClip(true) { x -> x.copy(contrast = v) } }
                LS(stringResource(R.string.saturation), it.sat, -1f..1f, "", end) { v -> vm.updateClip(true) { x -> x.copy(sat = v) } }
                LS(stringResource(R.string.hue), it.hue, -1f..1f, "", end) { v -> vm.updateClip(true) { x -> x.copy(hue = v) } }
                LS(stringResource(R.string.temperature), it.temp, -1f..1f, "", end) { v -> vm.updateClip(true) { x -> x.copy(temp = v) } }
            }
            Tool.ZOOM -> c?.let {
                LS(stringResource(R.string.zoom), it.zoom, 1f..4f, "%.2fx".format(it.zoom), end) { v -> vm.updateClip(true) { x -> x.copy(zoom = v) } }
                LS("X", it.panX, -1f..1f, "", end) { v -> vm.updateClip(true) { x -> x.copy(panX = v) } }
                LS("Y", it.panY, -1f..1f, "", end) { v -> vm.updateClip(true) { x -> x.copy(panY = v) } }
                LS(stringResource(R.string.tilt), it.tilt, -45f..45f, "${it.tilt.toInt()}°", end) { v -> vm.updateClip(true) { x -> x.copy(tilt = v) } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(pc.onAddKey) { Text("◆ " + stringResource(R.string.kf)) }
                    if (it.keys.isNotEmpty()) TextButton({ vm.updateClip(false) { x -> x.copy(keys = emptyList()) } }) { Text(stringResource(R.string.remove)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    it.keys.forEachIndexed { i, k ->
                        FilterChip(false, { vm.removeKey(i) }, { Text("◆ ${fmt(k.tMs)}") }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                    }
                }
            }
            Tool.FADE -> c?.let {
                val mx = minOf(3000f, it.lenMs / 2f).coerceAtLeast(100f)
                LS(stringResource(R.string.fade_in), it.fadeIn.toFloat(), 0f..mx, "${it.fadeIn / 1000f}s", end) { v -> vm.updateClip(true) { x -> x.copy(fadeIn = v.toLong()) } }
                LS(stringResource(R.string.fade_out), it.fadeOut.toFloat(), 0f..mx, "${it.fadeOut / 1000f}s", end) { v -> vm.updateClip(true) { x -> x.copy(fadeOut = v.toLong()) } }
            }
            Tool.MOTION -> c?.let {
                Text(stringResource(R.string.anim_in), style = MaterialTheme.typography.labelMedium)
                Chips(Anim.entries.toList(), it.animIn, { a -> animLabel(a) }) { a -> vm.updateClip(false) { x -> x.copy(animIn = a) } }
                Text(stringResource(R.string.anim_out), style = MaterialTheme.typography.labelMedium)
                Chips(Anim.entries.toList(), it.animOut, { a -> animLabel(a) }) { a -> vm.updateClip(false) { x -> x.copy(animOut = a) } }
                Text(stringResource(R.string.motion), style = MaterialTheme.typography.labelMedium)
                Chips(Loop.entries.toList(), it.loop, { l -> loopLabel(l) }) { l -> vm.updateClip(false) { x -> x.copy(loop = l) } }
                LS("ms", it.animMs.toFloat(), 200f..1500f, "${it.animMs} ms", end) { v -> vm.updateClip(true) { x -> x.copy(animMs = v.toLong()) } }
            }
            Tool.AUDIO -> {
                var speakText by remember { mutableStateOf("") }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(pc.pickAudio) { Icon(Icons.Default.MusicNote, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.music)) }
                    Button(pc.onRecord) {
                        Icon(if (pc.recording) Icons.Default.Stop else Icons.Default.Mic, null); Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (pc.recording) R.string.stop else R.string.voiceover))
                    }
                    if (c != null && !c.isImage) OutlinedButton(pc.onExtract) { Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.extract)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(speakText, { speakText = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.speak)) })
                    IconButton({ pc.onSpeak(speakText); speakText = "" }) { Icon(Icons.Default.RecordVoiceOver, null) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    AudioGen.SFX_ICONS.forEachIndexed { i, e ->
                        Text(e, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { pc.onSfx(i) }.padding(6.dp))
                    }
                }
                p.audio.firstOrNull { pc.selKind == 3 && it.id == pc.selId }?.let { a ->
                    Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    LS(stringResource(R.string.volume), a.volume, 0f..1f, "${(a.volume * 100).toInt()}%", end) { v -> vm.updateAudio(a.id, true) { x -> x.copy(volume = v) } }
                    TextButton({ vm.removeAudio(a.id); pc.onClear() }) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.delete)) }
                }
            }
            Tool.TEXT -> {
                var input by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    Button({ val id = vm.addText(input, 64, pc.abs()); if (id >= 0) pc.onSelect(1, id); input = "" }) { Text(stringResource(R.string.add)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("😀", "😂", "😍", "🔥", "❤️", "⭐", "👍", "🎉", "✨", "🎵", "💯", "🙏").forEach { e ->
                        Text(e, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { val id = vm.addText(e, 120, pc.abs()); if (id >= 0) pc.onSelect(1, id) }.padding(6.dp))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.texts.forEach { tx ->
                        FilterChip(pc.selKind == 1 && pc.selId == tx.id, { pc.onSelect(1, tx.id) }, { Text(tx.text, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.remove), Modifier.size(16.dp).clickable { vm.removeText(tx.id); pc.onClear() }) })
                    }
                }
                p.texts.firstOrNull { pc.selKind == 1 && it.id == pc.selId }?.let { tx ->
                    LS(stringResource(R.string.position), tx.y, -1f..1f, "", end) { v -> vm.updateText(tx.id, true) { x -> x.copy(y = v) } }
                    LS(stringResource(R.string.size), tx.size.toFloat(), 24f..200f, "${tx.size}", end) { v -> vm.updateText(tx.id, true) { x -> x.copy(size = v.toInt()) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFEB3B, 0xFFFF5252, 0xFF00E5C3, 0xFF7C4DFF).forEach { col ->
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).background(Color(col))
                                    .border(BorderStroke(if (tx.color == col.toInt()) 3.dp else 1.dp, ACCENT), CircleShape)
                                    .clickable { vm.updateText(tx.id, false) { x -> x.copy(color = col.toInt()) } }
                            )
                        }
                    }
                    Chips(listOf(0, 1, 2, 3, 4), tx.anim, { i -> textAnimLabel(i) }) { i -> vm.updateText(tx.id, false) { x -> x.copy(anim = i) } }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(tx.bold, { vm.updateText(tx.id, false) { x -> x.copy(bold = !x.bold) } }, { Icon(Icons.Default.FormatBold, null) })
                        FilterChip(tx.bg, { vm.updateText(tx.id, false) { x -> x.copy(bg = !x.bg) } }, { Icon(Icons.Default.FormatColorFill, null) })
                        FONT_FAMILIES.forEachIndexed { i, ff ->
                            FilterChip(tx.font == i, { vm.updateText(tx.id, false) { x -> x.copy(font = i) } }, { Text("Aa", fontFamily = ff) })
                        }
                    }
                }
            }
            Tool.STICKER -> {
                Button(pc.pickImage) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sticker)) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.stickers.forEach { s ->
                        FilterChip(pc.selKind == 2 && pc.selId == s.id, { pc.onSelect(2, s.id) }, { AsyncImage(model = s.uri, contentDescription = null, modifier = Modifier.size(28.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.remove), Modifier.size(16.dp).clickable { vm.removeSticker(s.id); pc.onClear() }) })
                    }
                }
                p.stickers.firstOrNull { pc.selKind == 2 && it.id == pc.selId }?.let { s ->
                    Chips(listOf(0, 1, 2, 3), s.anim, { i -> textAnimLabel(i) }) { i -> vm.updateSticker(s.id, false) { x -> x.copy(anim = i) } }
                    LS(stringResource(R.string.size), s.scale, 0.1f..1f, "", end) { v -> vm.updateSticker(s.id, true) { x -> x.copy(scale = v) } }
                    LS(stringResource(R.string.opacity), s.alpha, 0.1f..1f, "${(s.alpha * 100).toInt()}%", end) { v -> vm.updateSticker(s.id, true) { x -> x.copy(alpha = v) } }
                    LS("X", s.x, -1f..1f, "", end) { v -> vm.updateSticker(s.id, true) { x -> x.copy(x = v) } }
                    LS("Y", s.y, -1f..1f, "", end) { v -> vm.updateSticker(s.id, true) { x -> x.copy(y = v) } }
                }
            }
            Tool.RATIO -> {
                val opts = listOf(0f to "", 9f / 16 to "9:16 · TikTok", 16f / 9 to "16:9 · YouTube", 1f to "1:1", 4f / 5 to "4:5 · Instagram", 4f / 3 to "4:3")
                Chips(opts.map { it.first }, p.ratio, { r -> if (r == 0f) stringResource(R.string.original) else opts.first { it.first == r }.second }) { r -> vm.setRatio(r) }
                Chips(listOf(false, true), p.fit, { f -> stringResource(if (f) R.string.fill else R.string.fit) }) { f -> vm.setFit(f) }
            }
            else -> {}
        }
    }
}
