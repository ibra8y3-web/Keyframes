@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.keyframes.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import kotlinx.coroutines.delay

enum class Tool(val icon: ImageVector, val label: Int, val action: Boolean = false) {
    TRIM(Icons.Default.Timeline, R.string.trim),
    SPLIT(Icons.Default.ContentCut, R.string.split, true),
    FREEZE(Icons.Default.PauseCircle, R.string.freeze, true),
    SPEED(Icons.Default.Speed, R.string.speed),
    VOLUME(Icons.Default.VolumeUp, R.string.volume),
    FILTER(Icons.Default.AutoAwesome, R.string.filter),
    ADJUST(Icons.Default.Tune, R.string.adjust),
    ZOOM(Icons.Default.ZoomIn, R.string.zoom),
    FADE(Icons.Default.Gradient, R.string.fade_in),
    STICKER(Icons.Default.Image, R.string.sticker),
    VOICE(Icons.Default.Mic, R.string.voiceover),
    TEXT(Icons.Default.TextFields, R.string.text),
    MUSIC(Icons.Default.MusicNote, R.string.music),
    RATIO(Icons.Default.AspectRatio, R.string.ratio),
    ROTATE(Icons.Default.RotateRight, R.string.rotate, true),
    MIRROR(Icons.Default.Flip, R.string.mirror, true),
    LEFT(Icons.Default.ArrowBack, 0, true),
    RIGHT(Icons.Default.ArrowForward, 0, true),
    DUP(Icons.Default.ContentCopy, R.string.duplicate, true),
    DEL(Icons.Default.Delete, R.string.delete, true)
}

private fun fmt(ms: Long) = "%d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

private fun fxLabel(f: Fx) = when (f) {
    Fx.NONE -> R.string.f_none; Fx.GRAY -> R.string.f_gray; Fx.SEPIA -> R.string.f_sepia
    Fx.INVERT -> R.string.f_invert; Fx.WARM -> R.string.f_warm; Fx.COOL -> R.string.f_cool
    Fx.CINE -> R.string.f_cine; Fx.VIVID -> R.string.f_vivid; Fx.FADE -> R.string.f_fade; Fx.NOIR -> R.string.f_noir
}

private fun applyPreview(player: ExoPlayer, c: Clip) {
    player.playbackParameters = PlaybackParameters(if (c.isImage) 1f else c.speed)
    player.volume = c.volume.coerceIn(0f, 1f)
    player.setVideoEffects(clipEffects(c))
}

@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val ctx = LocalContext.current
    val p by vm.project.collectAsStateWithLifecycle()
    val sel by vm.selected.collectAsStateWithLifecycle()
    val exp by vm.export.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    val canU by vm.canUndo.collectAsStateWithLifecycle()
    val canR by vm.canRedo.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    var tool by remember { mutableStateOf<Tool?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    val player = remember { ExoPlayer.Builder(ctx).build() }
    val musicPlayer = remember { ExoPlayer.Builder(ctx).build().apply { repeatMode = Player.REPEAT_MODE_ALL } }
    val voicePlayer = remember { ExoPlayer.Builder(ctx).build() }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    DisposableEffect(Unit) { onDispose { player.release(); musicPlayer.release(); voicePlayer.release() } }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addMedia(it) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setMusic) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::addSticker) }

    LaunchedEffect(msg) { msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.msg.value = null } }

    val structure = p.clips.map { Triple(it.uri, it.start, it.end) }
    val cur = p.clips.getOrNull(sel)
    var pos by remember { mutableLongStateOf(0L) }
    val absMs = p.clips.take(sel).sumOf { it.outMs } + (pos / (cur?.speed ?: 1f)).toLong()

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
    LaunchedEffect(cur?.speed, cur?.volume, cur?.fx, cur?.rot, cur?.mirror, cur?.bright, cur?.contrast, cur?.sat, sel, structure) {
        cur?.let { applyPreview(player, it) }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { vm.selected.value = player.currentMediaItemIndex }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; musicPlayer.playWhenReady = isPlaying; voicePlayer.playWhenReady = isPlaying }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(Unit) { while (true) { pos = player.currentPosition; delay(100) } }
    LaunchedEffect(p.music?.uri) { p.music?.let { musicPlayer.setMediaItem(MediaItem.fromUri(it.uri)); musicPlayer.prepare() } ?: musicPlayer.clearMediaItems() }
    LaunchedEffect(p.music?.volume) { musicPlayer.volume = p.music?.volume ?: 0f }
    LaunchedEffect(p.voice?.uri) { p.voice?.let { voicePlayer.setMediaItem(MediaItem.fromUri(it.uri)); voicePlayer.prepare() } ?: voicePlayer.clearMediaItems() }
    LaunchedEffect(p.voice?.volume, recording) { voicePlayer.volume = if (recording) 0f else (p.voice?.volume ?: 0f) }

    fun beginRecording() {
        player.seekTo(0, 0); vm.selected.value = 0; musicPlayer.seekTo(0); voicePlayer.seekTo(0)
        vm.startVoice(); player.play()
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) beginRecording() else vm.msg.value = R.string.failed
    }
    fun toggleRecord() {
        if (recording) { vm.stopVoice(); player.pause() }
        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun toolEnabled(t: Tool) = when (t) {
        Tool.MUSIC, Tool.TEXT, Tool.RATIO, Tool.STICKER, Tool.VOICE -> true
        Tool.SPEED, Tool.VOLUME, Tool.FREEZE -> cur != null && !cur.isImage
        else -> cur != null
    }

    fun onTool(t: Tool) {
        when (t) {
            Tool.SPLIT -> vm.split(player.currentPosition)
            Tool.FREEZE -> vm.freeze(player.currentPosition)
            Tool.ROTATE -> vm.updateClip(false) { it.copy(rot = (it.rot + 90) % 360) }
            Tool.MIRROR -> vm.updateClip(false) { it.copy(mirror = !it.mirror) }
            Tool.LEFT -> vm.move(-1)
            Tool.RIGHT -> vm.move(1)
            Tool.DUP -> vm.duplicate()
            Tool.DEL -> vm.delete()
            else -> tool = if (tool == t) null else t
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            IconButton({ vm.newProject() }) { Icon(Icons.Default.NoteAdd, stringResource(R.string.new_project)) }
            IconButton({ vm.undo() }, enabled = canU) { Icon(Icons.Default.Undo, stringResource(R.string.undo)) }
            IconButton({ vm.redo() }, enabled = canR) { Icon(Icons.Default.Redo, stringResource(R.string.redo)) }
            IconButton({ vm.snapshot(player.currentPosition) }, enabled = cur != null) { Icon(Icons.Default.PhotoCamera, stringResource(R.string.snapshot)) }
            Button({ showExport = true }, enabled = p.clips.isNotEmpty()) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.export))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val m = if (p.ratio > 0f) Modifier.aspectRatio(p.ratio) else Modifier.fillMaxSize()
            AndroidView(
                modifier = m.clip(RoundedCornerShape(12.dp)),
                factory = { c -> PlayerView(c).apply { useController = false; this.player = player } },
                update = { it.resizeMode = if (p.ratio > 0f) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT }
            )
            p.stickers.filter { absMs in it.start until it.end }.forEach { s ->
                AsyncImage(model = s.uri, contentDescription = null, modifier = Modifier.align(BiasAlignment(s.x, -s.y)).fillMaxWidth(s.scale))
            }
            p.texts.filter { absMs in it.start until it.end }.forEach { t ->
                Text(t.text, color = Color(t.color), fontSize = (t.size / 3f).sp, modifier = Modifier.align(BiasAlignment(0f, -t.y)))
            }
            if (p.clips.isEmpty()) Text(stringResource(R.string.empty_hint), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({
                if (playing) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0, 0); musicPlayer.seekTo(0); voicePlayer.seekTo(0) }
                    player.play()
                }
            }, enabled = cur != null) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
            Slider(
                value = if (cur != null && cur.lenMs > 0) (pos.toFloat() / cur.lenMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { v -> cur?.let { player.seekTo(sel, (v * it.lenMs).toLong()) } },
                modifier = Modifier.weight(1f), enabled = cur != null
            )
            Text(fmt(p.totalMs), style = MaterialTheme.typography.labelMedium)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(72.dp)) {
            items(p.clips, key = { it.id }) { c ->
                val i = p.clips.indexOf(c)
                val w = (c.outMs / 1000f * 28f).dp.coerceIn(72.dp, 220.dp)
                Box(
                    Modifier.width(w).fillMaxHeight().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
                        .border(BorderStroke(if (i == sel) 2.dp else 0.dp, MaterialTheme.colorScheme.secondary), RoundedCornerShape(8.dp))
                        .clickable { vm.selected.value = i; player.seekTo(i, 0) }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(c.uri).videoFrameMillis(c.start).build(),
                        imageLoader = loader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                    Text(fmt(c.outMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                }
            }
            item {
                FilledTonalIconButton({ pickVideo.launch(arrayOf("video/*", "image/*")) }, Modifier.size(64.dp).padding(vertical = 4.dp)) {
                    Icon(Icons.Default.Add, stringResource(R.string.add_media))
                }
            }
        }
        tool?.let { t -> Panel(t, p, cur, vm, { pickAudio.launch(arrayOf("audio/*")) }, { pickImage.launch(arrayOf("image/*")) }, recording, { toggleRecord() }) }
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            items(Tool.entries.toList()) { t ->
                val on = tool == t
                Column(
                    Modifier.alpha(if (toolEnabled(t)) 1f else 0.35f).clip(RoundedCornerShape(10.dp)).clickable(enabled = toolEnabled(t)) { onTool(t) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(t.icon, null, tint = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                    if (t == Tool.LEFT || t == Tool.RIGHT) Text(if (t == Tool.LEFT) "◀" else "▶", style = MaterialTheme.typography.labelSmall)
                    else Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(480, 720, 1080).forEach { FilterChip(h == it, { h = it }, { Text("${it}p") }) }
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
            confirmButton = {
                Button({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = if (e.audio) "audio/mp4" else "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, e.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, null)); vm.dismissExport()
                }) { Text(stringResource(R.string.share)) }
            },
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
private fun Panel(t: Tool, p: Project, c: Clip?, vm: EditorViewModel, pickAudio: () -> Unit, pickImage: () -> Unit, recording: Boolean, onRecord: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
        when (t) {
            Tool.TRIM -> c?.let {
                RangeSlider(
                    value = it.start.toFloat()..it.end.toFloat(),
                    onValueChange = { r ->
                        val s = r.start.toLong(); val e = r.endInclusive.toLong()
                        if (e - s >= 300) vm.updateClip(true) { x -> x.copy(start = s, end = e) }
                    },
                    valueRange = 0f..it.durMs.toFloat(), onValueChangeFinished = vm::liveEnd
                )
                Text("${fmt(it.start)} – ${fmt(it.end)}  (${fmt(it.lenMs)})", style = MaterialTheme.typography.labelMedium)
            }
            Tool.SPEED -> c?.let {
                Slider(it.speed, { v -> vm.updateClip(true) { x -> x.copy(speed = v) } }, valueRange = 0.25f..4f, onValueChangeFinished = vm::liveEnd)
                Text("%.2fx".format(it.speed), style = MaterialTheme.typography.labelMedium)
            }
            Tool.VOLUME -> c?.let {
                Slider(it.volume, { v -> vm.updateClip(true) { x -> x.copy(volume = v) } }, valueRange = 0f..1f, onValueChangeFinished = vm::liveEnd)
                Text("${(it.volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            Tool.FILTER -> c?.let {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Fx.entries.toList()) { f ->
                        FilterChip(it.fx == f, { vm.updateClip(false) { x -> x.copy(fx = f) } }, { Text(stringResource(fxLabel(f))) })
                    }
                }
            }
            Tool.ADJUST -> c?.let {
                Text(stringResource(R.string.brightness), style = MaterialTheme.typography.labelMedium)
                Slider(it.bright, { v -> vm.updateClip(true) { x -> x.copy(bright = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
                Text(stringResource(R.string.contrast), style = MaterialTheme.typography.labelMedium)
                Slider(it.contrast, { v -> vm.updateClip(true) { x -> x.copy(contrast = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
                Text(stringResource(R.string.saturation), style = MaterialTheme.typography.labelMedium)
                Slider(it.sat, { v -> vm.updateClip(true) { x -> x.copy(sat = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
                Text(stringResource(R.string.hue), style = MaterialTheme.typography.labelMedium)
                Slider(it.hue, { v -> vm.updateClip(true) { x -> x.copy(hue = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
                Text(stringResource(R.string.temperature), style = MaterialTheme.typography.labelMedium)
                Slider(it.temp, { v -> vm.updateClip(true) { x -> x.copy(temp = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
            }
            Tool.VOICE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onRecord) {
                        Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null); Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (recording) R.string.stop else R.string.voiceover))
                    }
                    p.voice?.let { TextButton({ vm.removeVoice() }) { Text(stringResource(R.string.remove)) } }
                }
                p.voice?.let { Slider(it.volume, { v -> vm.voiceVolume(v) }, valueRange = 0f..1f, onValueChangeFinished = vm::liveEnd) }
            }
            Tool.ZOOM -> c?.let {
                Text("${stringResource(R.string.zoom)}  %.2fx".format(it.zoom), style = MaterialTheme.typography.labelMedium)
                Slider(it.zoom, { v -> vm.updateClip(true) { x -> x.copy(zoom = v) } }, valueRange = 1f..4f, onValueChangeFinished = vm::liveEnd)
                Text("X", style = MaterialTheme.typography.labelMedium)
                Slider(it.panX, { v -> vm.updateClip(true) { x -> x.copy(panX = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
                Text("Y", style = MaterialTheme.typography.labelMedium)
                Slider(it.panY, { v -> vm.updateClip(true) { x -> x.copy(panY = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
            }
            Tool.FADE -> c?.let {
                val mx = minOf(3000f, it.lenMs / 2f).coerceAtLeast(100f)
                Text("${stringResource(R.string.fade_in)}  ${it.fadeIn / 1000f}s", style = MaterialTheme.typography.labelMedium)
                Slider(it.fadeIn.toFloat().coerceAtMost(mx), { v -> vm.updateClip(true) { x -> x.copy(fadeIn = v.toLong()) } }, valueRange = 0f..mx, onValueChangeFinished = vm::liveEnd)
                Text("${stringResource(R.string.fade_out)}  ${it.fadeOut / 1000f}s", style = MaterialTheme.typography.labelMedium)
                Slider(it.fadeOut.toFloat().coerceAtMost(mx), { v -> vm.updateClip(true) { x -> x.copy(fadeOut = v.toLong()) } }, valueRange = 0f..mx, onValueChangeFinished = vm::liveEnd)
            }
            Tool.STICKER -> {
                var chosen by remember { mutableStateOf<Long?>(null) }
                Button(pickImage) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sticker)) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(p.stickers, key = { it.id }) { s ->
                        FilterChip(chosen == s.id, { chosen = s.id }, { AsyncImage(model = s.uri, contentDescription = null, modifier = Modifier.size(28.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.remove), Modifier.size(16.dp).clickable { vm.removeSticker(s.id) }) })
                    }
                }
                p.stickers.firstOrNull { it.id == chosen }?.let { s ->
                    val total = maxOf(1000L, p.totalMs).toFloat()
                    RangeSlider(
                        s.start.toFloat()..s.end.toFloat().coerceAtMost(total),
                        { r -> if (r.endInclusive - r.start >= 300f) vm.updateSticker(s.id, true) { x -> x.copy(start = r.start.toLong(), end = r.endInclusive.toLong()) } },
                        valueRange = 0f..total, onValueChangeFinished = vm::liveEnd
                    )
                    Text(stringResource(R.string.size), style = MaterialTheme.typography.labelMedium)
                    Slider(s.scale, { v -> vm.updateSticker(s.id, true) { x -> x.copy(scale = v) } }, valueRange = 0.1f..1f, onValueChangeFinished = vm::liveEnd)
                    Text("X", style = MaterialTheme.typography.labelMedium)
                    Slider(s.x, { v -> vm.updateSticker(s.id, true) { x -> x.copy(x = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
                    Text("Y", style = MaterialTheme.typography.labelMedium)
                    Slider(s.y, { v -> vm.updateSticker(s.id, true) { x -> x.copy(y = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
                }
            }
            Tool.TEXT -> {
                var input by remember { mutableStateOf("") }
                var chosen by remember { mutableStateOf<Long?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    Button({ vm.addText(input); input = "" }) { Text(stringResource(R.string.add)) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(p.texts, key = { it.id }) { tx ->
                        FilterChip(chosen == tx.id, { chosen = tx.id }, { Text(tx.text, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.remove), Modifier.size(16.dp).clickable { vm.removeText(tx.id) }) })
                    }
                }
                p.texts.firstOrNull { it.id == chosen }?.let { tx ->
                    val total = maxOf(1000L, p.totalMs).toFloat()
                    RangeSlider(
                        tx.start.toFloat()..tx.end.toFloat().coerceAtMost(total),
                        { r -> if (r.endInclusive - r.start >= 300f) vm.updateText(tx.id, true) { x -> x.copy(start = r.start.toLong(), end = r.endInclusive.toLong()) } },
                        valueRange = 0f..total, onValueChangeFinished = vm::liveEnd
                    )
                    Text(stringResource(R.string.position), style = MaterialTheme.typography.labelMedium)
                    Slider(tx.y, { v -> vm.updateText(tx.id, true) { x -> x.copy(y = v) } }, valueRange = -1f..1f, onValueChangeFinished = vm::liveEnd)
                    Text(stringResource(R.string.size), style = MaterialTheme.typography.labelMedium)
                    Slider(tx.size.toFloat(), { v -> vm.updateText(tx.id, true) { x -> x.copy(size = v.toInt()) } }, valueRange = 24f..200f, onValueChangeFinished = vm::liveEnd)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFEB3B, 0xFFFF5252, 0xFF00E5C3, 0xFF7C4DFF).forEach { col ->
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).background(Color(col))
                                    .border(BorderStroke(if (tx.color == col.toInt()) 3.dp else 1.dp, MaterialTheme.colorScheme.secondary), CircleShape)
                                    .clickable { vm.updateText(tx.id, false) { x -> x.copy(color = col.toInt()) } }
                            )
                        }
                    }
                }
            }
            Tool.MUSIC -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(pickAudio) { Icon(Icons.Default.MusicNote, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.music)) }
                    p.music?.let { TextButton({ vm.removeMusic() }) { Text(stringResource(R.string.remove)) } }
                }
                p.music?.let { Slider(it.volume, { v -> vm.musicVolume(v) }, valueRange = 0f..1f, onValueChangeFinished = vm::liveEnd) }
            }
            Tool.RATIO -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val opts = listOf(0f to null, 16f / 9 to "16:9", 9f / 16 to "9:16", 1f to "1:1", 4f / 5 to "4:5", 4f / 3 to "4:3")
                items(opts) { (r, name) ->
                    FilterChip(p.ratio == r, { vm.setRatio(r) }, { Text(name ?: stringResource(R.string.original)) })
                }
            }
            else -> {}
        }
    }
}
