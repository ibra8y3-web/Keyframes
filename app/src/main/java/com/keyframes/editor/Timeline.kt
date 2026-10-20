package com.keyframes.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import kotlin.math.ceil

private val ACCENT = Color(0xFF00E5C3)
private val MUTED = Color(0xFF8A8AA8)
private val C_TEXT = Color(0xFF7C4DFF)
private val C_STICKER = Color(0xFFFF7043)
private val C_AUDIO = Color(0xFF00BFA5)
private val C_VOICE = Color(0xFF42A5F5)

private fun audioIcon(kind: Int): ImageVector = when (kind) {
    0 -> Icons.Default.MusicNote
    1 -> Icons.Default.Mic
    2 -> Icons.Default.GraphicEq
    3 -> Icons.Default.RecordVoiceOver
    else -> Icons.Default.VideoLibrary
}

/** Greedy row assignment so overlapping items sit on different rows. */
private fun assignRows(spans: List<Pair<Long, Long>>): List<Int> {
    val order = spans.indices.sortedBy { spans[it].first }
    val rowEnd = mutableListOf<Long>()
    val res = MutableList(spans.size) { 0 }
    for (i in order) {
        val (s, e) = spans[i]
        var r = rowEnd.indexOfFirst { it <= s }
        if (r < 0) { rowEnd += e; r = rowEnd.lastIndex } else rowEnd[r] = e
        res[i] = r
    }
    return res
}

@Composable
private fun Modifier.hDrag(msPerPx: Float, onDelta: (Long) -> Unit, onDone: () -> Unit): Modifier {
    val mpp by rememberUpdatedState(msPerPx)
    val cb by rememberUpdatedState(onDelta)
    val dn by rememberUpdatedState(onDone)
    return pointerInput(Unit) {
        detectHorizontalDragGestures(onDragEnd = { dn() }, onDragCancel = { dn() }) { change, dx ->
            change.consume()
            cb((dx * mpp).toLong())
        }
    }
}

@Composable
private fun TlBar(
    startMs: Long, endMs: Long, pxPerSec: Float, row: Int, color: Color, selected: Boolean,
    onTap: () -> Unit, onMove: (Long) -> Unit, onTrimStart: (Long) -> Unit, onTrimEnd: (Long) -> Unit, onDone: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val msPerPx = 1000f / with(density) { pxPerSec.dp.toPx() }
    val tap by rememberUpdatedState(onTap)
    val w = ((endMs - startMs) / 1000f * pxPerSec).dp.coerceAtLeast(28.dp)
    var m = Modifier.offset(x = (startMs / 1000f * pxPerSec).dp, y = (row * 30).dp).width(w).height(26.dp)
        .clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = if (selected) 0.95f else 0.5f))
        .pointerInput(Unit) { detectTapGestures { tap() } }
    if (selected) m = m.hDrag(msPerPx, onMove, onDone)
    Box(m) {
        content()
        if (selected) {
            Box(Modifier.align(Alignment.CenterStart).width(14.dp).fillMaxHeight().background(Color.White).hDrag(msPerPx, onTrimStart, onDone))
            Box(Modifier.align(Alignment.CenterEnd).width(14.dp).fillMaxHeight().background(Color.White).hDrag(msPerPx, onTrimEnd, onDone))
        }
    }
}

@Composable
private fun ClipItem(c: Clip, selected: Boolean, pxPerSec: Float, loader: ImageLoader, vm: EditorViewModel, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val msPerPx = 1000f / with(density) { pxPerSec.dp.toPx() }
    val w = (c.outMs / 1000f * pxPerSec).dp.coerceAtLeast(20.dp)
    val n = minOf(8, maxOf(1, ceil(w.value / 56f).toInt()))
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier.width(w).fillMaxHeight().padding(horizontal = 1.dp).clip(RoundedCornerShape(8.dp))
            .border(BorderStroke(if (selected) 2.dp else 0.dp, ACCENT), RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { tap() } }
    ) {
        Row(Modifier.fillMaxSize()) {
            for (k in 0 until n) {
                val tm = ((c.start + c.lenMs * k / n) / 500) * 500
                val req = remember(c.uri, tm) {
                    ImageRequest.Builder(ctx).data(c.uri).videoFrameMillis(tm).size(200, 200)
                        .bitmapConfig(Bitmap.Config.RGB_565).memoryCacheKey("${c.uri}@$tm").build()
                }
                AsyncImage(
                    model = req,
                    imageLoader = loader, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).width(14.dp).fillMaxHeight().background(Color.White)
                    .hDrag(msPerPx, { d -> vm.updateClip(true) { x -> x.copy(start = (x.start + (d * x.speed).toLong()).coerceIn(0, x.end - 300)) } }, { vm.liveEnd() })
            )
            Box(
                Modifier.align(Alignment.CenterEnd).width(14.dp).fillMaxHeight().background(Color.White)
                    .hDrag(msPerPx, { d -> vm.updateClip(true) { x -> x.copy(end = (x.end + (d * x.speed).toLong()).coerceIn(x.start + 300, x.durMs)) } }, { vm.liveEnd() })
            )
        }
    }
}

@Composable
private fun Ruler(total: Long, pxPerSec: Float) {
    val step = when { pxPerSec >= 80f -> 1; pxPerSec >= 40f -> 2; pxPerSec >= 20f -> 5; else -> 10 }
    Box(Modifier.fillMaxWidth().height(20.dp)) {
        var s = 0
        while (s * 1000L <= total) {
            Text(fmtS(s * 1000L), fontSize = 9.sp, color = MUTED, modifier = Modifier.offset(x = (s * pxPerSec).dp))
            s += step
        }
    }
}

/** CapCut-style timeline: fixed centre playhead, scrolling tracks, selectable and draggable layers. */
@Composable
fun Timeline(
    vm: EditorViewModel, p: Project, sel: Int, absState: State<Long>, selKind: Int, selId: Long, loader: ImageLoader,
    heightDp: Int, onSelect: (Int, Long) -> Unit, onSeek: (Long) -> Unit, onScrub: () -> Unit, onAdd: () -> Unit
) {
    val density = LocalDensity.current
    var zoom by remember { mutableFloatStateOf(60f) }
    val total = maxOf(p.totalMs, 1000L)
    val pxPerSec = minOf(zoom, (25000f / density.density) / (total / 1000f))
    val hs = rememberScrollState()
    val vs = rememberScrollState()
    var programmatic by remember { mutableStateOf(false) }
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val waves by vm.waves.collectAsStateWithLifecycle()
    val tRows = remember(p.texts) { assignRows(p.texts.map { it.start to it.end }) }
    val sRows = remember(p.stickers) { assignRows(p.stickers.map { it.start to it.end }) }
    val nT = (tRows.maxOrNull() ?: -1) + 1
    val nS = (sRows.maxOrNull() ?: -1) + 1

    BoxWithConstraints(Modifier.fillMaxWidth().height(heightDp.dp).background(Color(0xFF0F0F1A))) {
        val halfW = maxWidth / 2
        val pxMs = with(density) { pxPerSec.dp.toPx() } / 1000f
        LaunchedEffect(pxMs) {
            snapshotFlow { absState.value }.collect { a ->
                if (!hs.isScrollInProgress) { programmatic = true; hs.scrollTo((a * pxMs).toInt()); programmatic = false }
            }
        }
        LaunchedEffect(pxMs) {
            snapshotFlow { hs.value }.collect { v ->
                if (hs.isScrollInProgress && !programmatic) { scrub(); seek((v / pxMs).toLong()) }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(vs)) {
            Row(Modifier.horizontalScroll(hs)) {
                Spacer(Modifier.width(halfW))
                Column(Modifier.width((total / 1000f * pxPerSec).dp)) {
                    Ruler(total, pxPerSec)
                    Row(Modifier.height(56.dp)) {
                        p.clips.forEachIndexed { i, c ->
                            ClipItem(c, selKind == 0 && i == sel, pxPerSec, loader, vm) { onSelect(0, i.toLong()) }
                        }
                    }
                    if (nT > 0) Box(Modifier.fillMaxWidth().padding(top = 4.dp).height((nT * 30).dp)) {
                        p.texts.forEachIndexed { i, t ->
                            TlBar(
                                t.start, t.end, pxPerSec, tRows[i], C_TEXT, selKind == 1 && selId == t.id,
                                onTap = { onSelect(1, t.id) },
                                onMove = { d -> vm.updateText(t.id, true) { x -> val len = x.end - x.start; val s = (x.start + d).coerceAtLeast(0); x.copy(start = s, end = s + len) } },
                                onTrimStart = { d -> vm.updateText(t.id, true) { x -> x.copy(start = (x.start + d).coerceIn(0, x.end - 300)) } },
                                onTrimEnd = { d -> vm.updateText(t.id, true) { x -> x.copy(end = maxOf(x.start + 300, x.end + d)) } },
                                onDone = { vm.liveEnd() }
                            ) {
                                Text(t.text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp))
                            }
                        }
                    }
                    if (nS > 0) Box(Modifier.fillMaxWidth().padding(top = 4.dp).height((nS * 30).dp)) {
                        p.stickers.forEachIndexed { i, s ->
                            TlBar(
                                s.start, s.end, pxPerSec, sRows[i], C_STICKER, selKind == 2 && selId == s.id,
                                onTap = { onSelect(2, s.id) },
                                onMove = { d -> vm.updateSticker(s.id, true) { x -> val len = x.end - x.start; val st = (x.start + d).coerceAtLeast(0); x.copy(start = st, end = st + len) } },
                                onTrimStart = { d -> vm.updateSticker(s.id, true) { x -> x.copy(start = (x.start + d).coerceIn(0, x.end - 300)) } },
                                onTrimEnd = { d -> vm.updateSticker(s.id, true) { x -> x.copy(end = maxOf(x.start + 300, x.end + d)) } },
                                onDone = { vm.liveEnd() }
                            ) {
                                AsyncImage(model = s.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.align(Alignment.Center).size(22.dp).clip(RoundedCornerShape(4.dp)))
                            }
                        }
                    }
                    if (p.audio.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(top = 4.dp).height((p.audio.size * 30).dp)) {
                        p.audio.forEachIndexed { i, a ->
                            TlBar(
                                a.startMs, a.startMs + a.lenMs, pxPerSec, i, if (a.kind == 1 || a.kind == 3) C_VOICE else C_AUDIO, selKind == 3 && selId == a.id,
                                onTap = { onSelect(3, a.id) },
                                onMove = { d -> vm.updateAudio(a.id, true) { x -> x.copy(startMs = (x.startMs + d).coerceAtLeast(0)) } },
                                onTrimStart = { d -> vm.updateAudio(a.id, true) { x -> val ns = (x.srcStart + d).coerceIn(0, x.srcEnd - 300); x.copy(srcStart = ns, startMs = x.startMs + (ns - x.srcStart)) } },
                                onTrimEnd = { d -> vm.updateAudio(a.id, true) { x -> x.copy(srcEnd = (x.srcEnd + d).coerceIn(x.srcStart + 300, x.durMs)) } },
                                onDone = { vm.liveEnd() }
                            ) {
                                LaunchedEffect(a.uri) { vm.ensureWave(a.uri) }
                                val pk = waves[a.uri.toString()]
                                if (pk != null && pk.isNotEmpty()) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val mid = size.height / 2f
                                        var x = 0f
                                        while (x < size.width) {
                                            val t = a.srcStart + (x / size.width * a.lenMs).toLong()
                                            val amp = pk[((t / Waveform.STEP_MS).toInt()).coerceIn(0, pk.size - 1)]
                                            val hh = amp * size.height * 0.42f
                                            drawLine(Color.White.copy(alpha = 0.75f), Offset(x, mid - hh), Offset(x, mid + hh), 2f)
                                            x += 4f
                                        }
                                    }
                                }
                                Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(audioIcon(a.kind), null, Modifier.size(14.dp), tint = Color.White)
                                    Spacer(Modifier.width(4.dp))
                                    Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.width(halfW)) {
                    Spacer(Modifier.height(22.dp))
                    Box(
                        Modifier.padding(start = 8.dp).size(52.dp).clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00BFA5))))
                            .clickable { onAdd() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Add, null, tint = Color.White) }
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(Color.White))
        Row(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
            IconButton({ zoom = (zoom / 1.4f).coerceAtLeast(10f) }, Modifier.size(30.dp)) { Icon(Icons.Default.ZoomOut, null, Modifier.size(18.dp)) }
            IconButton({ zoom = (zoom * 1.4f).coerceAtMost(240f) }, Modifier.size(30.dp)) { Icon(Icons.Default.ZoomIn, null, Modifier.size(18.dp)) }
        }
    }
}
