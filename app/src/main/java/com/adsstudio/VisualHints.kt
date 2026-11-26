package com.adsstudio

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs

/**
 * Лёгкий «взгляд» на видео без тяжёлой видеомодели:
 * выдёргивает несколько кадров из каждого кандидата и сравнивает их.
 * Если картинка меняется — в кадре что-то происходит, а не говорящая голова.
 */
object VisualHints {

    private val fractions = listOf(0.15, 0.5, 0.85)

    fun analyze(context: Context, uri: Uri, clips: List<SuggestedClip>): Map<Int, String> {
        if (clips.isEmpty()) return emptyMap()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            clips.mapIndexedNotNull { index, clip ->
                val frames = fractions.mapNotNull { fraction ->
                    val timeMs = clip.startMs + ((clip.endMs - clip.startMs) * fraction).toLong()
                    runCatching {
                        retriever.getFrameAtTime(
                            timeMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }.getOrNull()
                }
                if (frames.size < 2) return@mapIndexedNotNull null

                val signatures = frames.map { signature(it) }
                frames.forEach { it.recycle() }

                val diff = signatures.zipWithNext()
                    .sumOf { (a, b) -> distance(a, b) } / (signatures.size - 1)

                val hint = when {
                    diff >= 28 -> "динамично, картинка меняется"
                    diff >= 12 -> "умеренное движение"
                    else -> "статичный кадр, говорящая голова"
                }
                index to hint
            }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun signature(bitmap: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        if (scaled !== bitmap) scaled.recycle()

        val result = IntArray(pixels.size * 3)
        pixels.forEachIndexed { i, pixel ->
            result[i * 3] = (pixel shr 16) and 0xFF
            result[i * 3 + 1] = (pixel shr 8) and 0xFF
            result[i * 3 + 2] = pixel and 0xFF
        }
        return result
    }

    private fun distance(a: IntArray, b: IntArray): Int {
        var sum = 0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size
    }
}
