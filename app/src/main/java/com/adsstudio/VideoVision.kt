package com.adsstudio

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs

/**
 * Лёгкий видеомодуль: смотрит кадры вокруг выбранных моментов, находит
 * склейки и движение и достраивает границы клипа. Это не 3B-видеомодель,
 * память почти не тратит: 40–60 кадров на клип.
 *
 * Зачем: 0.5B-редактор часто выбирает начало истории и обрывает её на
 * 10-й секунде, до развязки. Здесь мы доклеиваем продолжение по речи,
 * склейкам и движению, а не режем по первому попавшемуся предложению.
 */
data class SceneHint(
    val startCutMs: Long?,
    val endCutMs: Long?,
    val motionEndMs: Long?,
    val activity: Float
)

object VideoVision {

    private const val SAMPLE_STEP_MS = 600L
    private const val PRE_ROLL_MS = 3_000L
    private const val POST_ROLL_MS = 15_000L
    private val genericLeadIns = listOf(
        "вторая на сегодня", "первая на сегодня", "первый на сегодня",
        "ещё одна", "еще одна", "следующая история", "следующая новость",
        "а теперь", "тем временем", "напомню", "как вы знаете", "как вы помните",
        "в этом видео", "сегодня расскажу", "сегодня поговорим", "продолжение",
        "ранее", "до этого", "итак", "и так"
    )

    private val specificMarkers = listOf(
        "гольф", "авари", "трагед", "дтп", "машин", "водител", "мужчин",
        "парн", "девушк", "ребят", "улиц", "район", "город", "университет",
        "номер", "истор", "случа", "произош", "столкнов", "вреза", "погиб",
        "пострада", "погибл"
    )

    private val datePattern = Regex(
        "\\b\\d{1,2}\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)"
    )

    private const val CUT_THRESHOLD = 42f
    private const val MOTION_THRESHOLD = 18f
    private const val MIN_CLIP_MS = 16_000L

    fun refineClips(
        context: Context,
        uri: Uri,
        clips: List<SuggestedClip>,
        utterances: List<Utterance>,
        totalDurationMs: Long,
        maxClipMs: Long = 60_000L
    ): List<SuggestedClip> {
        if (clips.isEmpty()) return clips
        val limitMs = maxClipMs.coerceIn(MIN_CLIP_MS, 240_000L)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            clips.map { clip ->
                val scene = runCatching { analyzeScene(retriever, clip, totalDurationMs, limitMs) }
                    .getOrNull()
                extendClip(clip, utterances, scene, totalDurationMs, limitMs)
            }.sortedBy { it.startMs }
        } catch (_: Throwable) {
            clips
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun analyzeScene(
        retriever: MediaMetadataRetriever,
        clip: SuggestedClip,
        totalDurationMs: Long,
        maxClipMs: Long
    ): SceneHint? {
        if (totalDurationMs <= 0) return null
        val from = (clip.startMs - PRE_ROLL_MS).coerceAtLeast(0)
        val to = (clip.endMs + POST_ROLL_MS).coerceAtMost(totalDurationMs)
        if (to - from < 1_000) return null

        val maxSamples = if (maxClipMs > 120_000L) 60 else 40
        val step = maxOf(SAMPLE_STEP_MS, (to - from) / maxSamples)
        var time = from
        var previous: IntArray? = null
        var sum = 0f
        var count = 0
        var motionEnd: Long? = null
        val cuts = mutableListOf<Long>()

        while (time <= to && count < maxSamples) {
            val bitmap = runCatching {
                retriever.getFrameAtTime(
                    time * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }.getOrNull()
            if (bitmap != null) {
                val signature = signature(bitmap)
                bitmap.recycle()
                val previousSignature = previous
                if (previousSignature != null) {
                    val diff = distance(previousSignature, signature)
                    sum += diff
                    count++
                    if (diff >= CUT_THRESHOLD) cuts += time
                    if (time > clip.endMs && diff >= MOTION_THRESHOLD) motionEnd = time
                }
                previous = signature
            }
            time += step
        }

        if (count == 0) return null

        val startCut = cuts
            .filter { it in (clip.startMs - 2_500)..(clip.startMs + 1_000) }
            .minByOrNull { abs(it - clip.startMs) }
        val endCut = cuts
            .filter { it in (clip.endMs - 500)..(clip.endMs + 3_500) }
            .minByOrNull { abs(it - clip.endMs) }

        return SceneHint(
            startCutMs = startCut,
            endCutMs = endCut,
            motionEndMs = motionEnd,
            activity = sum / count
        )
    }

    private fun extendClip(
        clip: SuggestedClip,
        utterances: List<Utterance>,
        scene: SceneHint?,
        totalDurationMs: Long,
        maxClipMs: Long
    ): SuggestedClip {
        if (utterances.isEmpty()) return clip

        var start = clip.startMs.coerceAtLeast(0)
        var end = if (totalDurationMs > 0) {
            clip.endMs.coerceAtMost(totalDurationMs)
        } else {
            clip.endMs
        }

        var text = clip.text
        var lastEnd = end

        // Заходим назад: если перед клипом была завязка/вопрос, берём её с собой.
        var before = utterances.indexOfLast { it.endMs <= start + 300 }
        while (before >= 0) {
            val previous = utterances[before]
            val gap = start - previous.endMs
            if (gap > 3_500) break
            val useful = ClipPicker.hasSetup(previous.text) ||
                ClipPicker.isQuestion(previous.text) ||
                ClipPicker.needsPayoff(previous.text) ||
                ClipPicker.endsWithContinuation(previous.text)
            if (!useful) break
            start = previous.startMs
            text = (previous.text + " " + text).trim()
            before--
        }

        val overlapping = utterances.filter {
            it.endMs >= start - 500 && it.startMs <= end + 1_200
        }
        if (overlapping.isNotEmpty()) {
            text = overlapping.joinToString(" ") { it.text }
            lastEnd = maxOf(end, overlapping.lastOrNull()?.endMs ?: end)
        }

        val cursor = utterances.indexOfLast { it.startMs <= lastEnd }
        var nextIndex = cursor + 1
        val motionEnd = scene?.motionEndMs
        val endCut = scene?.endCutMs
        while (nextIndex in utterances.indices) {
            val next = utterances[nextIndex]
            val currentDuration = lastEnd - start
            val gapLimit = when {
                ClipPicker.needsPayoff(text) -> 5_000L
                currentDuration < MIN_CLIP_MS -> 4_000L
                else -> 2_500L
            }
            if (next.startMs - lastEnd > gapLimit) break
            if (totalDurationMs > 0 && next.endMs > totalDurationMs) break
            if (next.endMs - start > maxClipMs) break

            val shouldExtend = currentDuration < MIN_CLIP_MS ||
                ClipPicker.needsPayoff(text) ||
                ClipPicker.endsWithContinuation(text) ||
                ClipPicker.containsPayoff(next.text) ||
                (motionEnd != null && next.startMs <= motionEnd + 1_500) ||
                (endCut != null && next.startMs <= endCut + 1_200)
            if (!shouldExtend) break

            lastEnd = next.endMs
            text = (text + " " + next.text).trim()
            nextIndex++
        }
        end = lastEnd

        // Если рядом склейка, добираем кадр до неё, чтобы не обрывать сцену.
        scene?.endCutMs?.let { cut ->
            if (cut > end && cut - end <= 2_500 && cut - start <= maxClipMs) {
                end = cut
            }
        }

        // Если движение продолжается после речи, даём ему дойти до паузы.
        scene?.motionEndMs?.let { motion ->
            val target = motion + 500
            if (target > end && target - end <= 2_500 && target - start <= maxClipMs) {
                end = target
            }
        }

        // Начало сцены: добавляем до 1,2 с контекста, если клип стартует сразу после склейки.
        scene?.startCutMs?.let { cut ->
            if (cut < start && start - cut <= 1_200) start = cut
        }

        start = start.coerceAtLeast(0)
        if (totalDurationMs > 0) end = end.coerceAtMost(totalDurationMs)
        if (end - start > maxClipMs) end = start + maxClipMs
        if (end <= start) return clip

        // Если клип начинается с «вторая на сегодня / в этом видео / итак»,
        // сдвигаем старт к первой конкретной фразе: дате, герою или событию.
        trimGenericLeadIn(start, end, utterances)?.let { trimmed ->
            if (trimmed < end - 2_000) start = trimmed
        }

        val finalText = utterances
            .filter { it.endMs >= start && it.startMs <= end }
            .joinToString(" ") { it.text }
        return clip.copy(
            startMs = start,
            endMs = end,
            text = finalText.ifBlank { clip.text }
        )
    }

    private fun trimGenericLeadIn(
        start: Long,
        end: Long,
        utterances: List<Utterance>
    ): Long? {
        val opening = utterances.filter {
            it.startMs >= start - 400 && it.startMs <= start + 15_000 && it.endMs <= end
        }
        val first = opening.firstOrNull() ?: return null
        val firstLower = first.text.lowercase()
        val generic = genericLeadIns.any { firstLower.contains(it) }
        if (!generic) return null
        if (datePattern.containsMatchIn(firstLower)) return null

        val specific = opening.drop(1).firstOrNull {
            it.startMs - start <= 15_000 && isSpecific(it.text)
        } ?: return null
        return (specific.startMs - 300L).coerceAtLeast(start)
    }

    private fun isSpecific(text: String): Boolean {
        val lower = text.lowercase()
        if (datePattern.containsMatchIn(lower)) return true
        return specificMarkers.any { lower.contains(it) }
    }

    private fun signature(bitmap: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 12, 12, true)
        val pixels = IntArray(144)
        scaled.getPixels(pixels, 0, 12, 0, 0, 12, 12)
        if (scaled !== bitmap) scaled.recycle()

        val result = IntArray(pixels.size)
        pixels.forEachIndexed { index, pixel ->
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            result[index] = (red * 3 + green * 4 + blue) / 8
        }
        return result
    }

    private fun distance(a: IntArray, b: IntArray): Float {
        var sum = 0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toFloat() / a.size
    }
}
