package com.adsstudio

import android.graphics.Bitmap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Настоящий рендер клипа: кроп под выбранный формат, опциональный рекламный оверлей,
 * аппаратное кодирование H.264/AAC через Media3 Transformer.
 */
object ClipExporter {

    suspend fun export(
        context: android.content.Context,
        uri: android.net.Uri,
        startMs: Long,
        endMs: Long,
        ad: Bitmap?,
        outputWidth: Int,
        outputHeight: Int,
        outFile: java.io.File,
        onProgress: (Float) -> Unit
    ): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val clipping = androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()

        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()

        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        videoEffects += androidx.media3.effect.Presentation.createForWidthAndHeight(
            outputWidth,
            outputHeight,
            androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )

        if (ad != null) {
            val scale = (outputWidth * 0.26f) / ad.width.coerceAtLeast(1)
            val settings = androidx.media3.effect.OverlaySettings.Builder()
                .setScale(scale, scale)
                .setAlphaScale(0.9f)
                .setBackgroundFrameAnchor(0.55f, 0.55f)
                .build()
            val overlay = androidx.media3.effect.BitmapOverlay
                .createStaticBitmapOverlay(ad, settings)
            videoEffects += androidx.media3.effect.OverlayEffect(
                com.google.common.collect.ImmutableList.of(overlay)
            )
        }

        val edited = androidx.media3.transformer.EditedMediaItem.Builder(mediaItem)
            .setEffects(
                androidx.media3.transformer.Effects(
                    emptyList<androidx.media3.common.audio.AudioProcessor>(),
                    videoEffects
                )
            )
            .build()

        val sequence = androidx.media3.transformer.EditedMediaItemSequence(listOf(edited))
        val composition = androidx.media3.transformer.Composition.Builder(sequence).build()

        val transformer = androidx.media3.transformer.Transformer.Builder(context)
            .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
            .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
            .build()

        kotlinx.coroutines.coroutineScope {
            val progressJob = launch {
                val holder = androidx.media3.transformer.ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) ==
                        androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE
                    ) {
                        onProgress(holder.progress / 100f)
                    }
                    kotlinx.coroutines.delay(300)
                }
            }

            try {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    transformer.addListener(object : androidx.media3.transformer.Transformer.Listener {
                        override fun onCompleted(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: androidx.media3.transformer.ExportResult
                        ) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: androidx.media3.transformer.ExportException
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
}
