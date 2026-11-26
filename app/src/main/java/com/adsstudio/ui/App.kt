package com.adsstudio

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdsStudioApp(vm: AppViewModel = viewModel()) {
    AdsStudioTheme {
        val state by vm.state.collectAsStateWithLifecycle()
        val context = LocalContext.current

        val videoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri -> if (uri != null) vm.onVideoPicked(uri) }

        val imagePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri -> if (uri != null) vm.setAd(uri) }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Ads Studio") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Нарезка клипов 9:16 + реклама. Всё считается на телефоне.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val videoUri = state.videoUri
                if (videoUri == null) {
                    Button(
                        onClick = {
                            videoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.VideoOnly
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Выбрать видео")
                    }
                    Text(
                        "При первом распознавании скачается русская модель Vosk (~45 МБ).",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    VideoPreview(videoUri)

                    state.info?.let { info ->
                        Text(
                            "${formatDuration(info.durationMs)} · ${info.width}×${info.height}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            videoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.VideoOnly
                                )
                            )
                        }) {
                            Text("Другое видео")
                        }

                        TextButton(onClick = {
                            if (state.adSet) {
                                vm.clearAd()
                            } else {
                                imagePicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        }) {
                            Text(if (state.adSet) "Убрать рекламу" else "Добавить рекламу")
                        }
                    }

                    Button(
                        onClick = vm::transcribe,
                        enabled = state.busy == null && state.info != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (state.transcript.isEmpty()) "Распознать речь"
                            else "Распознать заново"
                        )
                    }

                    state.busy?.let { busy ->
                        Card {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(busy)
                                val progress = state.progress
                                if (progress != null) {
                                    LinearProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }

                    state.error?.let { error ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    error,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                TextButton(onClick = vm::dismissError) { Text("Закрыть") }
                            }
                        }
                    }

                    state.output?.let { output ->
                        Card {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Клип сохранён в Movies/AdsStudio",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(output, "video/mp4")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching { context.startActivity(intent) }
                                    }) {
                                        Text("Открыть")
                                    }

                                    OutlinedButton(onClick = {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(Intent.EXTRA_STREAM, output)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            context.startActivity(
                                                Intent.createChooser(intent, "Поделиться")
                                            )
                                        }
                                    }) {
                                        Text("Поделиться")
                                    }
                                }
                            }
                        }
                    }

                    if (state.clips.isNotEmpty()) {
                        Text(
                            "Найденные моменты",
                            style = MaterialTheme.typography.titleMedium
                        )
                        state.clips.forEach { clip ->
                            ClipCard(
                                clip = clip,
                                enabled = state.busy == null,
                                onExport = { vm.export(clip) }
                            )
                        }
                    } else if (state.transcript.isNotEmpty() && state.busy == null) {
                        Text(
                            "Речь распознана, но подходящих моментов не нашлось. " +
                                "Попробуй видео подлиннее."
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdsStudioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

@Composable
private fun VideoPreview(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    )
}

@Composable
private fun ClipCard(clip: SuggestedClip, enabled: Boolean, onExport: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                clip.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${formatDuration(clip.startMs)} – ${formatDuration(clip.endMs)}",
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                clip.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4
            )
            Button(onClick = onExport, enabled = enabled) {
                Text("Экспорт 9:16")
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
