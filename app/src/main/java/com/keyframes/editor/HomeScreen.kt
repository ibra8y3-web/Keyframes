@file:OptIn(ExperimentalMaterial3Api::class)

package com.keyframes.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis

@Composable
fun AppRoot() {
    var inEditor by rememberSaveable { mutableStateOf(false) }
    var startTool by remember { mutableStateOf<Tool?>(null) }
    if (inEditor) {
        BackHandler { inEditor = false }
        EditorScreen(startTool = startTool, onBack = { inEditor = false })
    } else {
        HomeScreen(onOpen = { startTool = it; inEditor = true })
    }
}

@Composable
fun HomeScreen(vm: EditorViewModel = viewModel(), onOpen: (Tool?) -> Unit) {
    val ctx = LocalContext.current
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    LaunchedEffect(Unit) { vm.refreshDrafts() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { vm.newDraft(); vm.addMedia(uris); onOpen(null) }
    }
    val grad = Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00BFA5)))
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1240), Color(0xFF0B0B14))))
            .verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(64.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Text(stringResource(R.string.tagline), color = Color(0xFFC9C9E0))
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(grad)
                .clickable { pick.launch(arrayOf("video/*", "image/*")) }.padding(24.dp)
        ) {
            Column {
                Icon(Icons.Default.Add, null, Modifier.size(40.dp), tint = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.new_project), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(stringResource(R.string.tagline), color = Color(0xDDFFFFFF))
            }
        }
        if (drafts.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.drafts), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(drafts, key = { it.id }) { d ->
                    Box(
                        Modifier.size(width = 120.dp, height = 150.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x22FFFFFF))
                            .clickable { vm.openDraft(d.id); onOpen(null) }
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(d.thumb).videoFrameMillis(0).build(),
                            imageLoader = loader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            "${d.count} · ${fmtS(d.durMs)}", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        IconButton({ vm.deleteDraft(d.id) }, Modifier.align(Alignment.TopEnd).size(32.dp)) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("480p – 4K", "H.264 · HEVC", stringResource(R.string.no_watermark)).forEach {
                Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x22FFFFFF)).padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(stringResource(R.string.tools_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Cat.entries.forEach { c ->
            val tools = c.tools.filter { it.label != 0 && it != Tool.DUP && it != Tool.DEL && it != Tool.SRT }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(c.label), style = MaterialTheme.typography.titleSmall, color = Color(0xFFB39DFF))
            Spacer(Modifier.height(6.dp))
            tools.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { t ->
                        Column(
                            Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF))
                                .clickable { onOpen(t) }.padding(vertical = 14.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(t.icon, null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
