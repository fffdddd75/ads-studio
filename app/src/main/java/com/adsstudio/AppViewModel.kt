package com.adsstudio

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("ads_studio", Context.MODE_PRIVATE)
    private val llmModels = LlmModelManager(application)
    private val _state = MutableStateFlow(
        UiState(
            llmEnabled = prefs.getBoolean("llm_enabled", true),
            llmReady = llmModels.isReady()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val models = ModelManager(application)
    private var adBitmap: Bitmap? = null

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

        viewModelScope.launch {
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
                val utterances = VoskAsr(modelDir).use { asr ->
                    asr.transcribe(wav) { value ->
                        _state.update { it.copy(progress = value) }
                    }
                }
                wav.delete()

                val candidates = ClipPicker.candidates(utterances, 10)
                var clips = ClipPicker.pickFromCandidates(candidates, 5)
                var notice: String? = null
                var llmReady = llmModels.isReady()

                if (_state.value.llmEnabled && candidates.size >= 3) {
                    try {
                        _state.update {
                            it.copy(
                                busy = if (llmReady) {
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
                        llmReady = true

                        _state.update {
                            it.copy(
                                llmReady = true,
                                busy = "Смотрю кадры и выбираю моменты…",
                                progress = null
                            )
                        }

                        LocalLlm(app).use { llm ->
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
                            if (indices.isNotEmpty()) {
                                val selected = indices.mapNotNull { candidates.getOrNull(it - 1) }
                                clips = ClipPicker.fillSelection(selected, candidates, 5)
                            } else {
                                notice = "Нейросеть не ответила номерами — выбрал по эвристике."
                            }
                        }
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        notice = "Нейросеть не справилась: ${error.message ?: "ошибка"}. " +
                            "Выбрал по эвристике."
                    }
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
}
