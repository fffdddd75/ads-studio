package com.adsstudio

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("ads_studio", Context.MODE_PRIVATE)
    private val llmModels = LlmModelManager(application)
    private val _state = MutableStateFlow(
        UiState(
            maxClipMs = prefs.getLong("max_clip_ms", 240_000L),
            llmEnabled = prefs.getBoolean("llm_enabled", true),
            llmReady = llmModels.isReady()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val models = ModelManager(application)
    private var analysisJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Старая 3B-видеомодель больше не используется: гасим осиротевший сервер
            // и удаляем её файлы, чтобы вернуть ~2,6 ГБ хранилища.
            VlmServer.cleanupOrphan(application, scan = true)
            File(application.filesDir, "vlm").deleteRecursively()
            prefs.edit().remove("vlm_enabled").apply()
        }
    }

    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    videoUri = uri,
                    info = null,
                    transcript = emptyList(),
                    clips = emptyList(),
                    output = null,
                    outputTitle = null,
                    exportingClipStart = null,
                    error = null,
                    notice = null,
                    busy = "Читаю видео…",
                    progress = null
                )
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    VideoProbe.probe(getApplication<Application>(), uri)
                }
            }.onSuccess { info ->
                _state.update { it.copy(info = info, busy = null) }
            }.onFailure { error ->
                _state.update {
                    it.copy(busy = null, error = error.message ?: "Не удалось открыть видео")
                }
            }
        }
    }

    fun transcribe() {
        val uri = _state.value.videoUri ?: return
        val app = getApplication<Application>()

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var asrMs = 0L
            var modelMs = 0L
            var aiMs = 0L
            var videoMs = 0L

            _state.update {
                it.copy(
                    busy = "Готовлю модель (~45 МБ при первом запуске)…",
                    progress = null,
                    error = null,
                    output = null,
                    outputTitle = null,
                    exportingClipStart = null,
                    notice = null
                )
            }

            try {
                val modelDir = models.ensure { value ->
                    _state.update { it.copy(progress = value.takeIf { p -> p >= 0f }) }
                }

                _state.update { it.copy(busy = "Достаю звук…", progress = null) }
                val wav = AudioExtractor.toWav(
                    context = app,
                    uri = uri,
                    outFile = File(app.cacheDir, "audio.wav"),
                    onProgress = { value -> _state.update { it.copy(progress = value) } }
                )

                _state.update { it.copy(busy = "Распознаю речь…", progress = 0f) }
                val asrStarted = SystemClock.elapsedRealtime()
                val utterances = withContext(Dispatchers.IO) {
                    VoskAsr(modelDir).use { asr ->
                        asr.transcribe(wav) { value ->
                            _state.update { it.copy(progress = value) }
                        }
                    }
                }
                asrMs = SystemClock.elapsedRealtime() - asrStarted

                _state.update { it.copy(busy = "Смотрю кадры и слушаю звук…", progress = null) }
                val durationMs = _state.value.info?.durationMs ?: 0L
                val maxClipMs = _state.value.maxClipMs
                val candidates = ClipPicker.candidates(utterances, 16, durationMs, maxClipMs)
                val audio = withContext(Dispatchers.IO) {
                    runCatching { AudioFeatures.analyze(wav, candidates) }
                        .getOrDefault(List<AudioHint?>(candidates.size) { null })
                }
                val visual = withContext(Dispatchers.IO) {
                    runCatching { VisualHints.analyzeScores(app, uri, candidates) }
                        .getOrDefault(List<VisualHint?>(candidates.size) { null })
                }
                wav.delete()
                val ranked = ClipPicker.rescore(candidates, durationMs, audio, visual, maxClipMs)
                var clips = ClipPicker.fillSelection(
                    ClipPicker.pickFromCandidates(ranked, 5),
                    ranked,
                    5
                )
                var notice: String? = null
                var llmReady = llmModels.isReady()

                if (_state.value.llmEnabled && ranked.size >= 3) {
                    try {
                        val aiStarted = SystemClock.elapsedRealtime()
                        clips = chooseWithFastLlm(app, ranked) { text ->
                            notice = text
                        }
                        aiMs = SystemClock.elapsedRealtime() - aiStarted
                        llmReady = true
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        coroutineContext.ensureActive()
                        notice = "Два агента не справились: ${error.message ?: "ошибка"}. " +
                            "Выбрал по эвристике."
                    }
                }

                _state.update { it.copy(busy = "Видеомодуль смотрит кадры…", progress = null) }
                val videoStarted = SystemClock.elapsedRealtime()
                clips = withContext(Dispatchers.IO) {
                    try {
                        VideoVision.refineClips(
                            context = app,
                            uri = uri,
                            clips = clips,
                            utterances = utterances,
                            totalDurationMs = durationMs,
                            maxClipMs = maxClipMs
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        clips
                    }
                }
                clips = ClipPicker.fillSelection(clips, ranked, 5)
                videoMs = SystemClock.elapsedRealtime() - videoStarted

                val totalMs = SystemClock.elapsedRealtime() - startedAt
                if (totalMs > 45_000) {
                    notice = listOfNotNull(
                        notice,
                        timingText(totalMs, asrMs, modelMs, aiMs, videoMs)
                    ).joinToString(" ")
                }

                _state.update {
                    it.copy(
                        transcript = utterances,
                        clips = clips,
                        llmReady = llmReady,
                        notice = notice,
                        busy = null,
                        progress = null
                    )
                }
            } catch (error: CancellationException) {
                _state.update { it.copy(busy = null, progress = null) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        busy = null,
                        progress = null,
                        error = error.message ?: "Ошибка распознавания"
                    )
                }
            }
        }
    }

    private suspend fun chooseWithFastLlm(
        app: Application,
        candidates: List<SuggestedClip>,
        onNotice: (String) -> Unit
    ): List<SuggestedClip> {
        _state.update {
            it.copy(
                busy = if (llmModels.isReady()) {
                    "Загружаю двух агентов…"
                } else {
                    "Скачиваю нейросеть (~520 МБ)…"
                },
                progress = null
            )
        }

        val modelPath = llmModels.ensure { value ->
            _state.update { it.copy(progress = value.takeIf { p -> p >= 0f }) }
        }
        _state.update {
            it.copy(
                llmReady = true,
                busy = "Загружаю критик и редактор…",
                progress = null
            )
        }

        val critic = LocalLlm(app)
        var editor: LocalLlm? = null
        return try {
            withContext(Dispatchers.IO) {
                critic.load(modelPath)
                if (hasEnoughMemoryForTwoLlm(app)) {
                    editor = try {
                        LocalLlm(app).also { it.load(modelPath) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        null
                    }
                }
            }

            val editorLlm = editor
            if (editorLlm == null) {
                _state.update { it.copy(busy = "Памяти мало: агенты работают по очереди…") }
            }

            val pool = ClipPicker.pickFromCandidates(candidates, 6)
                .sortedByDescending { it.score }

            // Первый агент: критик придумывает проверочные вопросы по кандидатам.
            _state.update { it.copy(busy = "Критик задаёт вопросы…") }
            val questions = withContext(Dispatchers.Default) {
                critic.generate(LlmClipSelector.criticPrompt(pool))
            }.ifBlank { LlmClipSelector.defaultQuestions }

            // Второй агент: редактор отвечает на вопросы и выбирает номера.
            _state.update { it.copy(busy = "Редактор отвечает и выбирает…") }
            var response = withContext(Dispatchers.Default) {
                (editorLlm ?: critic).generate(LlmClipSelector.editorPrompt(pool, 5, questions))
            }

            var indices = parseLlmChoice(response, pool.size, 5)

            // Если редактор не дал номеров, пробуем короткий одношаговый запрос:
            // маленькой модели так проще, а результат всё равно смешается с эвристикой.
            if (indices.isEmpty()) {
                _state.update {
                    it.copy(busy = "Редактор не ответил — пробую короткий запрос…")
                }
                var chosenFallback = withContext(Dispatchers.Default) {
                    (editorLlm ?: critic).generate(LlmClipSelector.compactPrompt(pool, 5))
                }
                var fallbackIndices = parseLlmChoice(chosenFallback, pool.size, 5)
                if (fallbackIndices.isEmpty()) {
                    val simple = withContext(Dispatchers.Default) {
                        (editorLlm ?: critic).generate(LlmClipSelector.prompt(pool, 5))
                    }
                    val simpleIndices = parseLlmChoice(simple, pool.size, 5)
                    if (simpleIndices.isNotEmpty()) {
                        chosenFallback = simple
                        fallbackIndices = simpleIndices
                    }
                }
                if (fallbackIndices.isNotEmpty()) {
                    response = chosenFallback
                    indices = fallbackIndices
                }
            }

            if (indices.isEmpty()) {
                onNotice("Агенты не дали номеров — выбрал по смыслу и звуку.")
                ClipPicker.pickFromCandidates(candidates, 5)
            } else {
                val strongKeys = LlmClipSelector.parseStrong(response, pool.size)
                    .mapNotNull { pool.getOrNull(it - 1) }
                    .map { it.startMs to it.endMs }
                    .toSet()
                val weakKeys = LlmClipSelector.parseWeak(response, pool.size)
                    .mapNotNull { pool.getOrNull(it - 1) }
                    .map { it.startMs to it.endMs }
                    .toSet()

                fun verdictBonus(clip: SuggestedClip): Float {
                    val key = clip.startMs to clip.endMs
                    var value = 0f
                    if (key in strongKeys) value += 10f
                    if (key in weakKeys) value -= 18f
                    return value
                }

                val llmSelected = indices.mapNotNull { pool.getOrNull(it - 1) }
                val heuristic = ClipPicker.pickFromCandidates(candidates, 5)
                val combined =
                    (llmSelected.map { it to (it.score + 12f + verdictBonus(it)) } +
                        heuristic.map { it to (it.score + verdictBonus(it)) })
                        .sortedByDescending { it.second }
                        .map { it.first }
                        .distinctBy { it.startMs to it.endMs }

                val allowOverlap = (combined.maxOfOrNull { it.endMs - it.startMs } ?: 0L) > 60_000L
                val selected = mutableListOf<SuggestedClip>()
                for (clip in combined) {
                    if (selected.size >= 5) break
                    if (!allowOverlap && selected.any { it.startMs < clip.endMs && clip.startMs < it.endMs }) continue
                    selected += clip
                }
                ClipPicker.fillSelection(selected, candidates, 5)
            }
        } finally {
            editor?.close()
            critic.close()
        }
    }

    private fun parseLlmChoice(
        response: String,
        candidateCount: Int,
        maxClips: Int
    ): List<Int> {
        return LlmClipSelector.parseEditorChoice(response, candidateCount, maxClips)
            .ifEmpty { LlmClipSelector.parseLooseChoice(response, candidateCount, maxClips) }
            .ifEmpty { LlmClipSelector.parseNumericLine(response, candidateCount, maxClips) }
            .ifEmpty { LlmClipSelector.parseMentions(response, candidateCount, maxClips) }
            .ifEmpty { LlmClipSelector.parse(response, candidateCount, maxClips) }
    }

    fun setAd(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val probe = withContext(Dispatchers.IO) {
                runCatching { VideoProbe.probe(app, uri) }.getOrNull()
            }
            if (probe == null) {
                _state.update { it.copy(error = "Не удалось прочитать видео-рекламу") }
                return@launch
            }

            val hasAudio = withContext(Dispatchers.IO) {
                runCatching { hasAudioTrack(app, uri) }.getOrDefault(false)
            }
            if (!hasAudio) {
                _state.update {
                    it.copy(error = "В рекламе нет звука — выбери видео со звуком")
                }
                return@launch
            }

            val thumbnail = withContext(Dispatchers.IO) {
                runCatching { loadVideoThumbnail(app, uri, 512) }.getOrNull()
            }
            val duration = probe.durationMs.takeIf { it > 0 } ?: 10_000L
            _state.update {
                it.copy(
                    adSet = true,
                    adUri = uri,
                    adDurationMs = duration,
                    adPreview = thumbnail,
                    output = null,
                    outputTitle = null,
                    exportingClipStart = null,
                    notice = "Реклама добавлена — она войдёт в следующий экспорт.",
                    error = null
                )
            }
        }
    }

    fun clearAd() {
        _state.update {
            it.copy(
                adSet = false,
                adUri = null,
                adDurationMs = null,
                adPreview = null,
                output = null,
                outputTitle = null,
                exportingClipStart = null,
                notice = "Реклама убрана — следующий экспорт будет без неё."
            )
        }
    }

    fun setRatio(ratio: OutputRatio) {
        _state.update { it.copy(ratio = ratio) }
    }

    fun trimClipStart(clip: SuggestedClip, deltaMs: Long): SuggestedClip {
        val maxStart = (clip.endMs - 2_000L).coerceAtLeast(0L)
        val updated = clip.copy(
            startMs = (clip.startMs + deltaMs).coerceIn(0L, maxStart)
        )
        replaceClip(clip, updated)
        return updated
    }

    fun trimClipEnd(clip: SuggestedClip, deltaMs: Long): SuggestedClip {
        val duration = _state.value.info?.durationMs ?: Long.MAX_VALUE
        val minEnd = (clip.startMs + 2_000L).coerceAtLeast(0L)
        val maxEnd = duration.coerceAtLeast(minEnd)
        val updated = clip.copy(
            endMs = (clip.endMs + deltaMs).coerceIn(minEnd, maxEnd)
        )
        replaceClip(clip, updated)
        return updated
    }

    private fun replaceClip(old: SuggestedClip, updated: SuggestedClip) {
        _state.update { state ->
            state.copy(
                clips = state.clips.map {
                    if (it.startMs == old.startMs && it.endMs == old.endMs) updated else it
                }
            )
        }
    }

    fun setMaxClipMs(value: Long) {
        val clamped = value.coerceIn(60_000L, 240_000L)
        prefs.edit().putLong("max_clip_ms", clamped).apply()
        _state.update {
            it.copy(
                maxClipMs = clamped,
                notice = if (it.clips.isNotEmpty()) {
                    "Длина изменена. Нажми «Нарезать заново», чтобы пересобрать моменты."
                } else {
                    it.notice
                }
            )
        }
    }

    fun setLlmEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("llm_enabled", enabled).apply()
        _state.update { it.copy(llmEnabled = enabled) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun renameClip(clip: SuggestedClip, title: String) {
        _state.update { state ->
            state.copy(
                clips = state.clips.map { item ->
                    if (item.startMs == clip.startMs && item.endMs == clip.endMs) {
                        item.copy(title = title)
                    } else {
                        item
                    }
                }
            )
        }
    }

    fun export(clip: SuggestedClip) {
        val uri = _state.value.videoUri ?: return
        val app = getApplication<Application>()

        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = "Рендерю клип…",
                    progress = 0f,
                    error = null,
                    output = null,
                    outputTitle = null,
                    exportingClipStart = clip.startMs
                )
            }

            try {
                val ratio = _state.value.ratio
                val outFile = File(app.cacheDir, "clip_${clip.startMs}.mp4")
                ClipExporter.export(
                    context = app,
                    uri = uri,
                    startMs = clip.startMs,
                    endMs = clip.endMs,
                    adUri = _state.value.adUri,
                    adDurationMs = _state.value.adDurationMs,
                    outputWidth = ratio.width,
                    outputHeight = ratio.height,
                    outFile = outFile,
                    onProgress = { value -> _state.update { it.copy(progress = value) } }
                )

                val safeTitle = sanitizeFileName(clip.title)
                val galleryUri = withContext(Dispatchers.IO) {
                    Gallery.publishVideo(
                        app,
                        outFile,
                        "$safeTitle.mp4",
                        clip.title
                    )
                }
                outFile.delete()

                val adCount = if (
                    _state.value.adUri != null && _state.value.adDurationMs != null
                ) {
                    ClipExporter.adCount(clip.startMs, clip.endMs)
                } else {
                    0
                }
                _state.update {
                    it.copy(
                        busy = null,
                        progress = null,
                        output = galleryUri,
                        outputTitle = clip.title,
                        exportingClipStart = null,
                        notice = if (adCount > 0) {
                            "Готово. Реклама вставлена: $adCount."
                        } else {
                            it.notice
                        }
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        busy = null,
                        progress = null,
                        exportingClipStart = null,
                        error = error.message ?: "Ошибка рендера"
                    )
                }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _state.update {
            it.copy(busy = null, progress = null, notice = "Остановлено")
        }
    }

    private fun hasEnoughMemoryForTwoLlm(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        // Два экземпляра 0.5B занимают заметно меньше одной 3B-VLM.
        return info.availMem >= 2_200L * 1024 * 1024
    }

    private fun timingText(
        totalMs: Long,
        asrMs: Long,
        modelMs: Long,
        aiMs: Long,
        videoMs: Long = 0L
    ): String {
        val parts = mutableListOf("речь ${duration(asrMs)}")
        if (modelMs >= 5_000) parts += "загрузка ИИ ${duration(modelMs)}"
        if (aiMs > 0) parts += "ИИ ${duration(aiMs)}"
        if (videoMs >= 1_000) parts += "видеомодуль ${duration(videoMs)}"
        return "Готово за ${duration(totalMs)}: ${parts.joinToString(", ")}."
    }

    private fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return if (minutes > 0) {
            "$minutes мин ${seconds.toString().padStart(2, '0')} с"
        } else {
            "$seconds с"
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\n\r]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
            .ifBlank { "clip" }

    private fun hasAudioTrack(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        try {
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            if (afd != null) {
                afd.use {
                    extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            } else {
                extractor.setDataSource(context, uri, null)
            }
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) return true
            }
            return false
        } finally {
            extractor.release()
        }
    }

    private fun loadVideoThumbnail(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            if (afd != null) {
                afd.use {
                    retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            } else {
                retriever.setDataSource(context, uri)
            }
            val frame = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null
            val longestSide = maxOf(frame.width, frame.height).coerceAtLeast(1)
            if (longestSide <= maxSide) return frame
            val scale = maxSide.toFloat() / longestSide
            val scaled = Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== frame) frame.recycle()
            return scaled
        } finally {
            retriever.release()
        }
    }
}
