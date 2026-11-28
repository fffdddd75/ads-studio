package com.adsstudio

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun AdsStudioApp(vm: AppViewModel = viewModel()) {
    StudioTheme {
        val state by vm.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val activity = context as? Activity
        val reducedMotion = rememberReducedMotion()

        DisposableEffect(state.busy) {
            if (state.busy != null) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val videoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri -> if (uri != null) vm.onVideoPicked(uri) }

        val imagePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri -> if (uri != null) vm.setAd(uri) }

        val pickVideo: () -> Unit = {
            videoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }

        val pickAd: () -> Unit = {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        val openVideo: (Uri) -> Unit = { output ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(output, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(intent) }
        }

        val shareVideo: (Uri) -> Unit = { output ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, output)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, "Поделиться"))
            }
        }

        StudioBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Reveal(0) { Header() }

                val videoUri = state.videoUri
                if (videoUri == null) {
                    OnboardingContent(
                        llmEnabled = state.llmEnabled,
                        onPickVideo = pickVideo
                    )
                } else {
                    ProjectContent(
                        state = state,
                        vm = vm,
                        onPickVideo = pickVideo,
                        onPickAd = pickAd,
                        onOpen = openVideo,
                        onShare = shareVideo
                    )
                }

                Footer()
            }
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark(Modifier.weight(1f))
        LocalBadge()
    }
}

@Composable
private fun OnboardingContent(
    llmEnabled: Boolean,
    onPickVideo: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Reveal(1) { Hero() }
        Reveal(2) { StepStrip() }
        Reveal(3) { ProjectPickerCard(onClick = onPickVideo) }
        Reveal(4) { ModelsHint(llmEnabled) }
    }
}

@Composable
private fun Hero() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = StudioColors.Text)) {
                    append("Длинное видео.\n")
                }
                withStyle(SpanStyle(color = StudioColors.Orange)) {
                    append("Пять сильных.")
                }
            },
            style = MaterialTheme.typography.displayLarge
        )
        Text(
            "Нейросеть находит сильные моменты, режет и вставляет рекламу. " +
                "Всё на телефоне, без облака.",
            style = MaterialTheme.typography.bodyMedium,
            color = StudioColors.TextMuted
        )
    }
}

@Composable
private fun ModelsHint(llmEnabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = StudioColors.TextDim,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = when {
                llmEnabled -> "Первый раз: Vosk 45 МБ + Qwen 520 МБ. Два агента — офлайн."
                else -> "Первый раз: Vosk 45 МБ. Дальше — офлайн."
            },
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextDim,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProjectContent(
    state: UiState,
    vm: AppViewModel,
    onPickVideo: () -> Unit,
    onPickAd: () -> Unit,
    onOpen: (Uri) -> Unit,
    onShare: (Uri) -> Unit
) {
    val videoUri = state.videoUri ?: return

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Reveal(0) {
            SourceSection(
                videoUri = videoUri,
                info = state.info,
                onChange = onPickVideo
            )
        }

        Reveal(1) {
            FormatSection(
                selected = state.ratio,
                maxClipMs = state.maxClipMs,
                onSelect = vm::setRatio,
                onSelectLength = vm::setMaxClipMs
            )
        }

        Reveal(2) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("03", "ИИ")
                AiCard(
                    enabled = state.llmEnabled,
                    ready = state.llmReady,
                    onToggle = vm::setLlmEnabled,
                    interactive = state.busy == null
                )
            }
        }

        Reveal(3) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("04", "Реклама")
                AdCard(
                    adPreview = state.adPreview,
                    onPick = onPickAd,
                    onClear = vm::clearAd,
                    enabled = state.busy == null
                )
            }
        }

        Reveal(4) {
            ActionSection(state = state, onRun = vm::transcribe, onCancel = vm::cancelAnalysis)
        }

        state.notice?.let { notice ->
            Reveal(5) {
                StudioBanner(
                    text = notice,
                    isError = false,
                    onDismiss = vm::dismissNotice
                )
            }
        }

        state.error?.let { error ->
            Reveal(5) {
                StudioBanner(
                    text = error,
                    isError = true,
                    onDismiss = vm::dismissError
                )
            }
        }

        state.output?.let { output ->
            Reveal(5) {
                SuccessCard(
                    title = state.outputTitle,
                    onOpen = { onOpen(output) },
                    onShare = { onShare(output) }
                )
            }
        }

        if (state.clips.isNotEmpty()) {
            Reveal(5) { ClipsSection(state = state, vm = vm) }
        } else if (state.transcript.isNotEmpty() && state.busy == null) {
            Reveal(5) { EmptyClips() }
        }
    }
}

@Composable
private fun SourceSection(
    videoUri: Uri,
    info: VideoInfo?,
    onChange: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("01", "Исходник")
        VideoPreview(videoUri)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (info != null) {
                        "${formatTimecode(info.durationMs)} · ${info.width}×${info.height}"
                    } else {
                        "Читаю видео…"
                    },
                    style = StudioType.mono,
                    color = StudioColors.TextMuted
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "MP4 · MOV · всё локально",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextDim
                )
            }
            TextButton(onClick = onChange) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = StudioColors.TextMuted,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Заменить",
                    color = StudioColors.TextMuted,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun FormatSection(
    selected: OutputRatio,
    maxClipMs: Long,
    onSelect: (OutputRatio) -> Unit,
    onSelectLength: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("02", "Формат")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputRatio.values().forEach { ratio ->
                RatioCard(
                    ratio = ratio,
                    selected = selected == ratio,
                    onClick = { onSelect(ratio) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            "Один и тот же клип можно экспортировать в каждом формате.",
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextDim
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Длина момента",
            style = MaterialTheme.typography.labelLarge,
            color = StudioColors.TextMuted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LengthChip("до 1 мин", maxClipMs == 60_000L) { onSelectLength(60_000L) }
            LengthChip("до 2 мин", maxClipMs == 120_000L) { onSelectLength(120_000L) }
            LengthChip("до 4 мин", maxClipMs == 240_000L) { onSelectLength(240_000L) }
        }
        Text(
            "4 минуты — больше контекста для длинных историй. После смены нажми «Нарезать заново».",
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextDim
        )
    }
}

@Composable
private fun LengthChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) StudioColors.Orange else StudioColors.TextMuted,
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) StudioColors.Orange.copy(alpha = 0.12f)
                else StudioColors.Surface
            )
            .border(
                1.dp,
                if (selected) StudioColors.Orange else StudioColors.BorderSoft,
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun ActionSection(
    state: UiState,
    onRun: () -> Unit,
    onCancel: () -> Unit
) {
    val hasTranscript = state.transcript.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryAction(
            text = if (hasTranscript) "Нарезать заново" else "Распознать и нарезать",
            enabled = state.busy == null && state.info != null,
            onClick = onRun,
            icon = if (hasTranscript) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = StudioColors.TextDim,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    state.llmEnabled && !state.llmReady ->
                        "Первый раз: Vosk 45 МБ + Qwen 520 МБ"
                    else -> "Всё локально · ничего не уходит в облако"
                },
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextDim,
                textAlign = TextAlign.Center
            )
        }

        state.busy?.let { busy ->
            ProcessingCard(
                busy = busy,
                progress = state.progress,
                onCancel = onCancel
            )
        }
    }
}

@Composable
private fun ClipsSection(
    state: UiState,
    vm: AppViewModel
) {
    var previewClip by remember { mutableStateOf<SuggestedClip?>(null) }
    val videoUri = state.videoUri

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            number = "05",
            title = "Моменты",
            trailing = { CountPill("${state.clips.size} / 5") }
        )
        Text(
            "Нажми на превью или «Смотреть», чтобы проверить выбранный отрезок.",
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextMuted
        )
        state.clips.forEachIndexed { index, clip ->
            ClipCard(
                index = index,
                clip = clip,
                videoUri = videoUri,
                enabled = state.busy == null,
                ratioLabel = state.ratio.label,
                isExporting = state.exportingClipStart == clip.startMs && state.busy != null,
                exportProgress = state.progress,
                onRename = { title -> vm.renameClip(clip, title) },
                onExport = { vm.export(clip) },
                onPreview = { previewClip = clip }
            )
        }
    }

    val clipToPlay = previewClip
    if (videoUri != null && clipToPlay != null) {
        ClipPreviewDialog(
            videoUri = videoUri,
            clip = clipToPlay,
            onDismiss = { previewClip = null },
            onTrimStart = { delta ->
                previewClip = vm.trimClipStart(clipToPlay, delta)
            },
            onTrimEnd = { delta ->
                previewClip = vm.trimClipEnd(clipToPlay, delta)
            }
        )
    }
}

@Composable
private fun ClipPreviewDialog(
    videoUri: Uri,
    clip: SuggestedClip,
    onDismiss: () -> Unit,
    onTrimStart: (Long) -> Unit,
    onTrimEnd: (Long) -> Unit
) {
    val context = LocalContext.current
    val player = remember(videoUri, clip.startMs, clip.endMs) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri), clip.startMs)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player, clip.startMs, clip.endMs) {
        while (true) {
            if (player.currentPosition >= clip.endMs ||
                player.playbackState == Player.STATE_ENDED
            ) {
                player.pause()
                break
            }
            delay(100)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(StudioColors.SurfaceRaised)
                .border(1.dp, StudioColors.BorderSoft, RoundedCornerShape(22.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${formatTimecode(clip.startMs)} – ${formatTimecode(clip.endMs)}",
                        style = StudioType.monoSmall,
                        color = StudioColors.Cyan
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        clip.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.Text,
                        maxLines = 2
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Закрыть",
                        tint = StudioColors.TextMuted
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    update = { view -> view.player = player },
                    modifier = Modifier.fillMaxSize()
                )
            }

            TrimRow(
                label = "Старт",
                value = clip.startMs,
                onMinus = { onTrimStart(-500L) },
                onPlus = { onTrimStart(500L) }
            )
            TrimRow(
                label = "Конец",
                value = clip.endMs,
                onMinus = { onTrimEnd(-500L) },
                onPlus = { onTrimEnd(500L) }
            )

            Text(
                "Подправь границы по 0,5 с — экспорт возьмёт новые. Видео играет только выбранный отрезок.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextDim
            )
        }
    }
}

@Composable
private fun TrimRow(
    label: String,
    value: Long,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = StudioColors.TextMuted,
            modifier = Modifier.width(54.dp)
        )
        Text(
            formatTimecode(value),
            style = StudioType.monoSmall,
            color = StudioColors.Cyan,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onMinus) {
            Text("−0,5 с", color = StudioColors.Text, style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onPlus) {
            Text("+0,5 с", color = StudioColors.Text, style = MaterialTheme.typography.labelLarge)
        }
    }
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black)
            .border(1.dp, StudioColors.BorderSoft, RoundedCornerShape(22.dp))
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { view -> view.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun Footer() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(StudioColors.BorderSoft)
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = StudioColors.TextDim,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Ads Studio · всё считается на телефоне",
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextDim
            )
        }
    }
}
