package com.adsstudio

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val vlmModels = VlmModelManager(application)
    private val _state = MutableStateFlow(
        UiState(
            llmEnabled = prefs.getBoolean("llm_enabled", true),
            llmReady = llmModels.isReady(),
            vlmEnabled = prefs.getBoolean("vlm_enabled", false),
            vlmReady = vlmModels.isReady()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val models = ModelManager(application)
    private var adBitmap: Bitmap? = null
    private var vlmServer: VlmServer? = null
    private var analysisJob: Job? = null
    private var vlmIdleJob: Job? = null

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
        vlmIdleJob?.cancel()
        analysisJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var asrMs = 0L
            var modelMs = 0L
            var aiMs = 0L

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
                wav.delete()

                val candidates = ClipPicker.candidates(utterances, 10)
                var clips = ClipPicker.pickFromCandidates(candidates, 5)
                var notice: String? = null
                var llmReady = llmModels.isReady()
                var vlmReady = vlmModels.isReady()

                if (_state.value.llmEnabled && candidates.size >= 3) {
                    if (_state.value.vlmEnabled) {
                        try {
                            _state.update {
                                it.copy(
                                    busy = if (vlmReady) {
                                        "Загружаю видео-ИИ…"
                                    } else {
                                        "Скачиваю видео-ИИ (${VlmModelManager.TOTAL_LABEL}). " +
                                            "Не закрывай приложение…"
                                    },
                                    progress = null
                                )
                            }

                            val modelStarted = SystemClock.elapsedRealtime()
                            val paths = vlmModels.ensure { value ->
                                _state.update { it.copy(progress = value.takeIf { p -> p >= 0f }) }
                            }
                            vlmReady = true
                            _state.update {
                                it.copy(vlmReady = true, busy = "Запускаю видео-ИИ…", progress = null)
                            }

                            val server = vlmServer ?: VlmServer(app).also { vlmServer = it }
                            run {
                                server.start(paths.first, paths.second) { status ->
                                    _state.update { it.copy(busy = status) }
                                }
                                modelMs = SystemClock.elapsedRealtime() - modelStarted
                                _state.update {
                                    it.copy(busy = "Смотрю кадры и выбираю моменты…", progress = null)
                                }

                                val pool = ClipPicker.pickFromCandidates(candidates, 6)
                                val aiStarted = SystemClock.elapsedRealtime()
                                val indices = withContext(Dispatchers.IO) {
                                    VlmClipSelector.select(
                                        context = app,
                                        uri = uri,
                                        candidates = pool,
                                        maxClips = 5,
                                        server = server,
                                        onProgress = { value ->
                                            _state.update { it.copy(progress = value) }
                                        },
                                        onFramesReady = {
                                            _state.update {
                                                it.copy(
                                                    busy = "Думаю над кадрами…",
                                                    progress = null
                                                )
                                            }
                                        }
                                    )
                                }

                                aiMs = SystemClock.elapsedRealtime() - aiStarted

                                if (indices.isNotEmpty()) {
                                    val selected = indices.mapNotNull { pool.getOrNull(it - 1) }
                                    clips = ClipPicker.fillSelection(selected, candidates, 5)
                                } else {
                                    notice = "Видео-ИИ не ответил номерами — выбрал по эвристике."
                                }
                            }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            coroutineContext.ensureActive()
                            vlmServer?.stop()
                            notice = if (llmModels.isReady()) {
                                "Видео-ИИ не справился: ${error.message ?: "ошибка"}. " +
                                    "Выбрал быстрой моделью."
                            } else {
                                "Видео-ИИ не справился: ${error.message ?: "ошибка"}. " +
                                    "Выбрал по эвристике."
                            }
                            if (llmModels.isReady()) {
                                try {
                                    val aiStarted = SystemClock.elapsedRealtime()
                                    clips = chooseWithFastLlm(app, uri, candidates) { text ->
                                        notice = text
                                    }
                                    aiMs = SystemClock.elapsedRealtime() - aiStarted
                                    llmReady = true
                                } catch (fallback: Throwable) {
                                    if (fallback is CancellationException) throw fallback
                                    coroutineContext.ensureActive()
                                    notice = "Быстрая модель тоже не справилась. Выбрал по эвристике."
                                }
                            }
                        }
                    } else {
                        try {
                            val aiStarted = SystemClock.elapsedRealtime()
                            clips = chooseWithFastLlm(app, uri, candidates) { text ->
                                notice = text
                            }
                            aiMs = SystemClock.elapsedRealtime() - aiStarted
                            llmReady = true
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            coroutineContext.ensureActive()
                            notice = "Нейросеть не справилась: ${error.message ?: "ошибка"}. " +
                                "Выбрал по эвристике."
                        }
                    }
                }

                val totalMs = SystemClock.elapsedRealtime() - startedAt
                if (totalMs > 45_000) {
                    notice = listOfNotNull(
                        notice,
                        timingText(totalMs, asrMs, modelMs, aiMs)
                    ).joinToString(" ")
                }

                _state.update {
                    it.copy(
                        transcript = utterances,
                        clips = clips,
                        llmReady = llmReady,
                        vlmReady = vlmReady,
                        notice = notice,
                        busy = null,
                        progress = null
                    )
                }
                scheduleVlmStop()
            } catch (error: CancellationException) {
                _state.update { it.copy(busy = null, progress = null) }
                scheduleVlmStop()
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
        uri: Uri,
        candidates: List<SuggestedClip>,
        onNotice: (String) -> Unit
    ): List<SuggestedClip> {
        _state.update {
            it.copy(
                busy = if (llmModels.isReady()) {
                    "Загружаю нейросеть…"
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
                busy = "Смотрю кадры и выбираю моменты…",
                progress = null
            )
        }

        return LocalLlm(app).use { llm ->
            withContext(Dispatchers.IO) { llm.load(modelPath) }

            val hints: Map<Int, String> = withContext(Dispatchers.IO) {
                runCatching {
                    VisualHints.analyze(app, uri, candidates)
                }.getOrDefault(emptyMap())
            }

            val prompt = LlmClipSelector.prompt(candidates, 5, hints)
            val response = withContext(Dispatchers.Default) {
                llm.generate(prompt)
            }
            val indices = LlmClipSelector.parse(response, candidates.size, 5)
            if (indices.isEmpty()) {
                onNotice("Нейросеть не ответила номерами — выбрал по эвристике.")
                ClipPicker.pickFromCandidates(candidates, 5)
            } else {
                val selected = indices.mapNotNull { candidates.getOrNull(it - 1) }
                ClipPicker.fillSelection(selected, candidates, 5)
            }
        }
    }

    fun setVlmEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("vlm_enabled", enabled).apply()
        if (enabled) prefs.edit().putBoolean("llm_enabled", true).apply()
        _state.update {
            it.copy(
                vlmEnabled = enabled,
                llmEnabled = if (enabled) true else it.llmEnabled
            )
        }
    }

    fun setAd(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeSampled(uri, 512)
            }
            if (bitmap == null) {
                _state.update { it.copy(error = "Не удалось прочитать картинку") }
                return@launch
            }
            adBitmap = bitmap
            _state.update { it.copy(adSet = true, adPreview = bitmap) }
        }
    }

    fun clearAd() {
        adBitmap = null
        _state.update { it.copy(adSet = false, adPreview = null) }
    }

    fun setRatio(ratio: OutputRatio) {
        _state.update { it.copy(ratio = ratio) }
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
                    ad = adBitmap,
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

                _state.update {
                    it.copy(
                        busy = null,
                        progress = null,
                        output = galleryUri,
                        outputTitle = clip.title,
                        exportingClipStart = null
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

    private fun scheduleVlmStop() {
        if (vlmServer == null) return
        vlmIdleJob?.cancel()
        vlmIdleJob = viewModelScope.launch {
            delay(120_000)
            if (analysisJob?.isActive != true) {
                vlmServer?.stop()
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        vlmServer?.stop()
        _state.update {
            it.copy(busy = null, progress = null, notice = "Остановлено")
        }
    }

    private fun timingText(totalMs: Long, asrMs: Long, modelMs: Long, aiMs: Long): String {
        val parts = mutableListOf("речь ${duration(asrMs)}")
        if (modelMs >= 5_000) parts += "загрузка ИИ ${duration(modelMs)}"
        if (aiMs > 0) parts += "ИИ ${duration(aiMs)}"
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

    private fun decodeSampled(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = getApplication<Application>().contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    override fun onCleared() {
        vlmServer?.close()
        vlmServer = null
        super.onCleared()
    }
}
