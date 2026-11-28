package com.adsstudio

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
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
 * Настоящий рендер клипа: кроп под выбранный формат, непрозрачная видео-реклама
 * со звуком и паузой основного клипа, аппаратное кодирование H.264/AAC.
 *
 * Правила вставки:
 * - клип до 1 минуты: один баннер ровно в середине;
 * - длиннее 1 минуты: 0:30, 1:30, 2:30… в готовом видео;
 * - количество вставок считается по длине исходного клипа, поэтому у клипа
 *   2:30 третий баннер на 2:30 не ставится, а у 2:50 — ставится.
 */
object ClipExporter {

    const val MAX_AD_MS = 10_000L
    const val MAX_AD_SPEED = 1.1f
    private const val AD_FIRST_MS = 30_000L
    private const val AD_EVERY_MS = 60_000L
    private const val MIN_TAIL_MS = 5_000L
    private const val SINGLE_AD_LIMIT_MS = 60_000L

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
        val adSourceDuration = adDurationMs?.coerceAtLeast(1L) ?: 0L
        if (adUri == null || adSourceDuration <= 0L) {
            items += mainItem(uri, startMs, endMs, outputWidth, outputHeight)
            return items
        }

        val targets = adTargets(clipDuration)
        if (targets.isEmpty()) {
            items += mainItem(uri, startMs, endMs, outputWidth, outputHeight)
            return items
        }

        val plan = adPlan(adSourceDuration)
        var sourceCursor = startMs
        var outputCursor = 0L
        for (target in targets) {
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
            items += adItem(adUri, plan, outputWidth, outputHeight)
            outputCursor += plan.outputDurationMs
        }

        if (sourceCursor < endMs) {
            items += mainItem(uri, sourceCursor, endMs, outputWidth, outputHeight)
        }
        return items
    }

    private fun adTargets(clipDurationMs: Long): List<Long> {
        if (clipDurationMs <= SINGLE_AD_LIMIT_MS) {
            val middle = clipDurationMs / 2
            return if (middle > 0L) listOf(middle) else emptyList()
        }

        val targets = mutableListOf<Long>()
        val lastAllowed = clipDurationMs - MIN_TAIL_MS
        var target = AD_FIRST_MS
        while (target <= lastAllowed) {
            targets += target
            target += AD_EVERY_MS
        }
        return targets
    }

    /**
     * Если реклама длиннее 10 секунд, ускоряем её не больше чем до x1.1
     * и обрезаем так, чтобы на выходе получилось ровно до 10 секунд.
     */
    private data class AdPlan(
        val sourceEndMs: Long,
        val outputDurationMs: Long,
        val speed: Float
    )

    private fun adPlan(sourceDurationMs: Long): AdPlan {
        if (sourceDurationMs <= MAX_AD_MS) {
            return AdPlan(
                sourceEndMs = sourceDurationMs,
                outputDurationMs = sourceDurationMs,
                speed = 1f
            )
        }
        val speed = (sourceDurationMs.toFloat() / MAX_AD_MS.toFloat())
            .coerceIn(1f, MAX_AD_SPEED)
        val sourceEnd = (MAX_AD_MS * speed).toLong().coerceAtMost(sourceDurationMs)
        val outputDuration = (sourceEnd / speed).toLong().coerceAtMost(MAX_AD_MS)
        return AdPlan(
            sourceEndMs = sourceEnd,
            outputDurationMs = outputDuration,
            speed = speed
        )
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
            .setEffects(effects(outputWidth, outputHeight, 1f))
            .build()
    }

    private fun adItem(
        uri: Uri,
        plan: AdPlan,
        outputWidth: Int,
        outputHeight: Int
    ): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(0L)
            .setEndPositionMs(plan.sourceEndMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setEffects(effects(outputWidth, outputHeight, plan.speed))
            .build()
    }

    private fun effects(
        outputWidth: Int,
        outputHeight: Int,
        speed: Float
    ): Effects {
        val videoEffects = mutableListOf<Effect>()
        if (speed > 1.001f) {
            videoEffects += SpeedChangeEffect(speed)
        }
        videoEffects += Presentation.createForWidthAndHeight(
            outputWidth,
            outputHeight,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )

        val audioEffects = if (speed > 1.001f) {
            listOf<AudioProcessor>(
                SonicAudioProcessor().apply { setSpeed(speed) }
            )
        } else {
            emptyList()
        }
        return Effects(audioEffects, videoEffects)
    }
}
