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
data class VisualHint(
    val score: Float,
    val cuts: Int,
    val label: String
)

object VisualHints {

    private val shortFractions = listOf(0.18, 0.5, 0.82)
    private val longFractions = listOf(0.08, 0.25, 0.5, 0.75, 0.92)

    /** Возвращает список той же длины, что и clips: null — кадры не удалось прочитать. */
    fun analyzeScores(
        context: Context,
        uri: Uri,
        clips: List<SuggestedClip>
    ): List<VisualHint?> {
        if (clips.isEmpty()) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            clips.map { clip -> analyzeClip(retriever, clip) }
        } catch (_: Throwable) {
            List(clips.size) { null }
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun analyze(
        context: Context,
        uri: Uri,
        clips: List<SuggestedClip>
    ): Map<Int, String> = analyzeScores(context, uri, clips)
        .mapIndexedNotNull { index, hint -> hint?.let { index to it.label } }
        .toMap()

    private fun analyzeClip(retriever: MediaMetadataRetriever, clip: SuggestedClip): VisualHint? {
        val duration = clip.endMs - clip.startMs
        val fractions = if (duration > 90_000L) longFractions else shortFractions
        val frames = fractions.mapNotNull { fraction ->
            val timeMs = clip.startMs + ((clip.endMs - clip.startMs) * fraction).toLong()
            runCatching {
                retriever.getFrameAtTime(
                    timeMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }.getOrNull()
        }
        if (frames.size < 2) {
            frames.forEach { it.recycle() }
            return null
        }

        val signatures = frames.map { signature(it) }
        frames.forEach { it.recycle() }

        val diffs = signatures.zipWithNext().map { (a, b) -> distance(a, b) }
        val diff = if (diffs.isEmpty()) 0f else diffs.average().toFloat()
        val score = (diff / 45f).coerceIn(0f, 1f)
        val cuts = diffs.count { it >= 35 }

        val label = when {
            cuts >= 2 || score >= 0.6f -> "динамично, есть склейки"
            score >= 0.25f -> "умеренное движение"
            else -> "статично, говорящая голова"
        }
        return VisualHint(score, cuts, label)
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
