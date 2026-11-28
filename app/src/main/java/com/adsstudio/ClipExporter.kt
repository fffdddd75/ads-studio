package com.adsstudio

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Настоящий рендер клипа: кроп под выбранный формат, видео-реклама в середине
 * каждой минуты, аппаратное кодирование H.264/AAC через Media3 Transformer.
 */
object ClipExporter {

    const val MAX_AD_MS = 10_000L
    private const val AD_FIRST_MS = 30_000L
    private const val AD_EVERY_MS = 60_000L

    suspend fun export(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        adUri: Uri?,
        adDurationMs: Long?,
        outputWidth: Int,
        outputHeight: Int,
        outFile: File,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.Main) {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val items = buildSequenceItems(
            uri = uri,
            startMs = startMs,
            endMs = endMs,
            adUri = adUri,
            adDurationMs = adDurationMs,
            outputWidth = outputWidth,
            outputHeight = outputHeight
        )
        if (items.isEmpty()) error("Нечего рендерить")

        val sequence = EditedMediaItemSequence(items)
        val composition = Composition.Builder(sequence).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()

        coroutineScope {
            val progressJob = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) ==
                        Transformer.PROGRESS_STATE_AVAILABLE
                    ) {
                        onProgress(holder.progress / 100f)
                    }
                    delay(300)
                }
            }

            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(exportException)
                            }
                        }
                    })
                    continuation.invokeOnCancellation { transformer.cancel() }
                    transformer.start(composition, outFile.absolutePath)
                }
            } finally {
                progressJob.cancel()
            }
        }

        onProgress(1f)
        outFile
    }

    /**
     * Реклама встаёт в готовом клипе на 0:30, 1:30, 2:30, 3:30…
     * Длительность рекламы учитывается, поэтому следующая вставка снова
     * попадает ровно на середину следующей минуты готового видео.
     */
    private fun buildSequenceItems(
        uri: Uri,
        startMs: Long,
        endMs: Long,
        adUri: Uri?,
        adDurationMs: Long?,
        outputWidth: Int,
        outputHeight: Int
    ): List<EditedMediaItem> {
        val clipDuration = (endMs - startMs).coerceAtLeast(0L)
        if (clipDuration <= 0L) return emptyList()

        val items = mutableListOf<EditedMediaItem>()
        val adDuration = adDurationMs
            ?.coerceIn(1L, MAX_AD_MS)
            ?: 0L
        if (adUri == null || adDuration <= 0L) {
            items += mainItem(uri, startMs, endMs, outputWidth, outputHeight)
            return items
        }

        var sourceCursor = startMs
        var outputCursor = 0L
        var target = AD_FIRST_MS
        while (target <= clipDuration && sourceCursor < endMs) {
            val contentDuration = (target - outputCursor).coerceAtLeast(0L)
            val segmentEnd = (sourceCursor + contentDuration).coerceAtMost(endMs)
            if (segmentEnd > sourceCursor) {
                items += mainItem(
                    uri = uri,
                    startMs = sourceCursor,
                    endMs = segmentEnd,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight
                )
                outputCursor += segmentEnd - sourceCursor
                sourceCursor = segmentEnd
            }
            if (sourceCursor >= endMs) break

            items += adItem(adUri, adDuration, outputWidth, outputHeight)
            outputCursor += adDuration
            target += AD_EVERY_MS
        }

        if (sourceCursor < endMs) {
            items += mainItem(uri, sourceCursor, endMs, outputWidth, outputHeight)
        }
        return items
    }

    private fun mainItem(
        uri: Uri,
        startMs: Long,
        endMs: Long,
        outputWidth: Int,
        outputHeight: Int
    ): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setEffects(effects(outputWidth, outputHeight))
            .build()
    }

    private fun adItem(
        uri: Uri,
        durationMs: Long,
        outputWidth: Int,
        outputHeight: Int
    ): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(0L)
            .setEndPositionMs(durationMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setEffects(effects(outputWidth, outputHeight))
            .build()
    }

    private fun effects(outputWidth: Int, outputHeight: Int): Effects {
        val videoEffects = listOf<Effect>(
            Presentation.createForWidthAndHeight(
                outputWidth,
                outputHeight,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
        )
        return Effects(emptyList<AudioProcessor>(), videoEffects)
    }
}
