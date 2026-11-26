package com.adsstudio

import android.app.Application
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

    private val _state = MutableStateFlow(UiState())
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
                    error = null,
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
                    busy = "Проверяю модель…",
                    progress = null,
                    error = null,
                    output = null
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

                val clips = ClipPicker.pick(utterances)
                _state.update {
                    it.copy(
                        transcript = utterances,
                        clips = clips,
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
            _state.update { it.copy(adSet = true) }
        }
    }

    fun clearAd() {
        adBitmap = null
        _state.update { it.copy(adSet = false) }
    }

    fun export(clip: SuggestedClip) {
        val uri = _state.value.videoUri ?: return
        val app = getApplication<Application>()

        viewModelScope.launch {
            _state.update {
                it.copy(busy = "Рендерю клип…", progress = 0f, error = null, output = null)
            }

            try {
                val outFile = File(app.cacheDir, "clip_${clip.startMs}.mp4")
                ClipExporter.export(
                    context = app,
                    uri = uri,
                    startMs = clip.startMs,
                    endMs = clip.endMs,
                    ad = adBitmap,
                    outFile = outFile,
                    onProgress = { value -> _state.update { it.copy(progress = value) } }
                )

                val galleryUri = withContext(Dispatchers.IO) {
                    Gallery.publishVideo(
                        app,
                        outFile,
                        "ads_${System.currentTimeMillis()}.mp4"
                    )
                }
                outFile.delete()

                _state.update { it.copy(busy = null, progress = null, output = galleryUri) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        busy = null,
                        progress = null,
                        error = error.message ?: "Ошибка рендера"
                    )
                }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

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
