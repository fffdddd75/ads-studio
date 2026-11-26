package com.adsstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max

/**
 * Готовит коллажи кадров и просит Qwen2.5-VL выбрать сильные фрагменты.
 */
object VlmClipSelector {

    private const val SHEET_W = 640
    private const val SHEET_H = 360
    private const val CELL_W = 320
    private const val CELL_H = 180
    private val fractions = listOf(0.12, 0.5, 0.88)

    fun select(
        context: Context,
        uri: Uri,
        candidates: List<SuggestedClip>,
        maxClips: Int,
        server: VlmServer,
        onProgress: (Float) -> Unit = {},
        onFramesReady: () -> Unit = {}
    ): List<Int> {
        val sheets = buildSheets(context, uri, candidates, onProgress)
        try {
            val parts = mutableListOf<VlmPart>()
            parts += VlmPart(text = intro(candidates.size, maxClips))
            candidates.forEachIndexed { index, clip ->
                parts += VlmPart(text = candidateText(index, clip))
                sheets.getOrNull(index)?.let { sheet ->
                    parts += VlmPart(imageBase64 = encode(sheet))
                }
            }
            parts += VlmPart(
                text = "Ответь только номерами через запятую, от лучшего к худшему. " +
                    "Без объяснений. Например: 2, 5, 1, 4, 3."
            )
            onFramesReady()
            val response = server.rank(parts, maxTokens = 96)
            return parse(response, candidates.size, maxClips)
        } finally {
            sheets.forEach { it.recycle() }
        }
    }

    private fun intro(count: Int, maxClips: Int): String =
        "Ниже $count фрагментов одного видео. На каждом коллаже три кадра: начало, " +
            "середина и конец. В четвёртой ячейке — номер фрагмента и таймкод.\n" +
            "Выбери $maxClips самых интересных для коротких видео (Shorts/Reels): " +
            "действие, эмоция, конфликт, интрига, польза, неожиданность.\n" +
            "Избегай: статичная говорящая голова, приветствие, вода, реклама, " +
            "перечисление без мысли."

    private fun candidateText(index: Int, clip: SuggestedClip): String {
        val text = clip.text.replace(Regex("\\s+"), " ").take(300)
        return "Фрагмент ${index + 1} (${time(clip.startMs)}–${time(clip.endMs)}): $text"
    }

    fun parse(response: String, candidateCount: Int, maxClips: Int): List<Int> {
        val cleaned = response.lineSequence()
            .joinToString("\n") { line -> line.replace(Regex("^\\s*\\d+[.)]\\s*"), "") }
        return Regex("\\d+")
            .findAll(cleaned)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..candidateCount }
            .distinct()
            .take(maxClips)
            .toList()
    }

    private fun buildSheets(
        context: Context,
        uri: Uri,
        candidates: List<SuggestedClip>,
        onProgress: (Float) -> Unit
    ): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        val sheets = ArrayList<Bitmap>(candidates.size)
        try {
            retriever.setDataSource(context, uri)
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            candidates.forEachIndexed { index, clip ->
                val sheet = Bitmap.createBitmap(SHEET_W, SHEET_H, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sheet)
                canvas.drawColor(Color.rgb(12, 12, 16))

                val frames = fractions.mapNotNull { fraction ->
                    val timeMs = clip.startMs + ((clip.endMs - clip.startMs) * fraction).toLong()
                    runCatching {
                        retriever.getFrameAtTime(
                            timeMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }.getOrNull()
                }

                val slots = listOf(0 to 0, CELL_W to 0, 0 to CELL_H)
                slots.forEachIndexed { slot, (x, y) ->
                    val frame = frames.getOrNull(slot)
                    if (frame == null) {
                        drawMissing(canvas, x, y)
                    } else {
                        val rotated = rotate(frame, rotation)
                        val cropped = centerCrop(rotated, CELL_W, CELL_H)
                        canvas.drawBitmap(cropped, x.toFloat(), y.toFloat(), null)
                        if (cropped !== rotated) cropped.recycle()
                        if (rotated !== frame) rotated.recycle()
                        frame.recycle()
                        drawFrameLabel(canvas, x, y, (slot + 1).toString())
                    }
                }

                drawInfo(canvas, index + 1, clip)
                drawGrid(canvas)
                sheets += sheet
                onProgress((index + 1).toFloat() / candidates.size.toFloat())
            }
        } finally {
            runCatching { retriever.release() }
        }
        return sheets
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun centerCrop(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val scale = max(
            width.toFloat() / bitmap.width.toFloat(),
            height.toFloat() / bitmap.height.toFloat()
        )
        val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(width)
        val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(height)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val x = (scaledW - width) / 2
        val y = (scaledH - height) / 2
        val cropped = Bitmap.createBitmap(scaled, x, y, width, height)
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }

    private fun drawFrameLabel(canvas: Canvas, x: Int, y: Int, label: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pad = 8f
        val rect = RectF(
            x + 8f,
            y + 8f,
            x + 8f + paint.measureText(label) + pad * 2,
            y + 8f + 26f
        )
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        canvas.drawRoundRect(rect, 6f, 6f, bg)
        canvas.drawText(label, x + 8f + pad, y + 8f + 20f, paint)
    }

    private fun drawMissing(canvas: Canvas, x: Int, y: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 90, 100)
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "нет кадра",
            x + CELL_W / 2f,
            y + CELL_H / 2f,
            paint
        )
    }

    private fun drawInfo(canvas: Canvas, number: Int, clip: SuggestedClip) {
        val bg = Paint().apply { color = Color.rgb(24, 24, 32) }
        canvas.drawRect(
            CELL_W.toFloat(),
            CELL_H.toFloat(),
            SHEET_W.toFloat(),
            SHEET_H.toFloat(),
            bg
        )

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 122, 61)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Фрагмент $number", CELL_W + 18f, CELL_H + 44f, accent)

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
        }
        canvas.drawText(
            "${time(clip.startMs)}–${time(clip.endMs)}",
            CELL_W + 18f,
            CELL_H + 76f,
            timePaint
        )

        val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(160, 160, 172)
            textSize = 16f
        }
        canvas.drawText("начало · середина · конец", CELL_W + 18f, CELL_H + 108f, hint)
    }

    private fun drawGrid(canvas: Canvas) {
        val line = Paint().apply {
            color = 0x33FFFFFF
            strokeWidth = 1f
        }
        canvas.drawLine(CELL_W.toFloat(), 0f, CELL_W.toFloat(), SHEET_H.toFloat(), line)
        canvas.drawLine(0f, CELL_H.toFloat(), SHEET_W.toFloat(), CELL_H.toFloat(), line)
    }

    private fun encode(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun time(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
