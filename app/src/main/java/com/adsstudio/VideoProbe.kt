package com.adsstudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int
)

object VideoProbe {

    fun probe(context: Context, uri: Uri): VideoInfo {
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

            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            var width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            var height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0

            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val tmp = width
                width = height
                height = tmp
            }

            if (width <= 0 || height <= 0) {
                error("Не удалось прочитать разрешение видео")
            }

            return VideoInfo(duration, width, height)
        } finally {
            retriever.release()
        }
    }
}
