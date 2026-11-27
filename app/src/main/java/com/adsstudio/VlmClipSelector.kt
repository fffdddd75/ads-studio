package com.adsstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max

/**
 * Готовит два общих коллажа кадров и просит Qwen2.5-VL выбрать сильные фрагменты.
 * Первый лист — начало всех фрагментов, второй — конец. Итого 12 кадров на 6
 * кандидатов: по 6 кадров на лист, поэтому каждая ячейка крупнее, чем раньше.
 */
object VlmClipSelector {

    private const val SHEET_W = 1024
    private const val SHEET_H = 576
    private const val HEADER_H = 44
    private const val COLS = 3
    private const val ROWS = 2
    private const val CELL_W = SHEET_W / COLS          // 341
    private const val CELL_H = (SHEET_H - HEADER_H) / ROWS  // 266
    private const val PAD = 6
    private const val LABEL_H = 44
    private const val FRAME_W = CELL_W - PAD * 2       // 329
    private const val FRAME_H = CELL_H - LABEL_H - PAD // 216

    // Два момента на фрагмент: начало и конец.
    private val moments = listOf(
        0.12 to "начало",
        0.88 to "конец"
    )

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
            parts += VlmPart(text = candidatesText(candidates))
            sheets.forEachIndexed { index, sheet ->
                parts += VlmPart(
                    text = if (index == 0) {
                        "Коллаж 1 — кадры в начале фрагментов."
                    } else {
                        "Коллаж 2 — кадры в конце фрагментов."
                    }
                )
                parts += VlmPart(imageBase64 = encode(sheet))
            }
            parts += VlmPart(
                text = "Ответь только 5 номеров через запятую, от лучшего к худшему. " +
                    "Без слов."
            )
            onFramesReady()
            val response = server.rank(parts, maxTokens = 24)
            return parse(response, candidates.size, maxClips)
        } finally {
            sheets.forEach { it.recycle() }
        }
    }

    private fun intro(count: Int, maxClips: Int): String =
        "Два коллажа: начало и конец ${count.coerceAtMost(COLS * ROWS)} фрагментов. " +
            "Номера и таймкоды подписаны.\n" +
            "Выбери $maxClips самых живых для Shorts: действие, эмоция, конфликт, польза. " +
            "Избегай говорящей головы, приветствий и воды."

    private fun candidatesText(candidates: List<SuggestedClip>): String {
        return candidates.take(COLS * ROWS).mapIndexed { index, clip ->
            "Фрагмент ${index + 1} (${time(clip.startMs)}–${time(clip.endMs)}): " +
                clip.text.replace(Regex("\\s+"), " ").take(60)
        }.joinToString("\n")
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
        val sheets = ArrayList<Bitmap>(moments.size)
        try {
            retriever.setDataSource(context, uri)
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            val count = candidates.size.coerceAtMost(COLS * ROWS)
            if (count == 0) return emptyList()
            val total = count * moments.size
            var done = 0

            moments.forEachIndexed { momentIndex, moment ->
                val fraction = moment.first
                val label = moment.second

                val sheet = Bitmap.createBitmap(SHEET_W, SHEET_H, Bitmap.Config.ARGB_8888)
                sheets += sheet
                val canvas = Canvas(sheet)
                canvas.drawColor(Color.rgb(12, 12, 16))
                drawHeader(canvas, momentIndex, moments.size, label, count)

                for (index in 0 until count) {
                    val clip = candidates[index]
                    val cellX = (index % COLS) * CELL_W
                    val cellY = HEADER_H + (index / COLS) * CELL_H

                    val timeMs = clip.startMs + ((clip.endMs - clip.startMs) * fraction).toLong()
                    val frame = runCatching {
                        retriever.getFrameAtTime(
                            timeMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }.getOrNull()

                    if (frame == null) {
                        drawMissing(canvas, cellX + PAD, cellY + PAD)
                    } else {
                        val rotated = rotate(frame, rotation)
                        val cropped = centerCrop(rotated, FRAME_W, FRAME_H)
                        canvas.drawBitmap(
                            cropped,
                            (cellX + PAD).toFloat(),
                            (cellY + PAD).toFloat(),
                            null
                        )
                        if (cropped !== rotated) cropped.recycle()
                        if (rotated !== frame) rotated.recycle()
                        frame.recycle()
                    }

                    drawCellLabel(canvas, cellX, cellY + PAD + FRAME_H, index + 1, clip)
                    drawCellBorder(canvas, cellX, cellY)

                    done++
                    onProgress(done.toFloat() / total.toFloat())
                }
            }
        } catch (e: Exception) {
            // Не роняем анализ: отправим те листы, которые успели собрать.
        } finally {
            runCatching { retriever.release() }
        }
        return sheets
    }

    private fun drawHeader(canvas: Canvas, index: Int, total: Int, label: String, count: Int) {
        val bg = Paint().apply { color = Color.rgb(20, 20, 27) }
        canvas.drawRect(0f, 0f, SHEET_W.toFloat(), HEADER_H.toFloat(), bg)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(
            "Коллаж ${index + 1} из $total · $label фрагментов · $count шт.",
            14f,
            30f,
            title
        )

        val line = Paint().apply {
            color = 0x33FFFFFF.toInt()
            strokeWidth = 1f
        }
        canvas.drawLine(0f, HEADER_H.toFloat(), SHEET_W.toFloat(), HEADER_H.toFloat(), line)
    }

    private fun drawCellLabel(
        canvas: Canvas,
        cellX: Int,
        stripY: Int,
        number: Int,
        clip: SuggestedClip
    ) {
        val bg = Paint().apply { color = Color.rgb(22, 22, 30) }
        canvas.drawRect(
            cellX.toFloat(),
            stripY.toFloat(),
            (cellX + CELL_W).toFloat(),
            (stripY + LABEL_H).toFloat(),
            bg
        )

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 122, 61)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Фрагмент $number", cellX + 10f, stripY + 30f, accent)

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 19f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(
            "${time(clip.startMs)}–${time(clip.endMs)}",
            (cellX + CELL_W - 10).toFloat(),
            stripY + 30f,
            timePaint
        )
    }

    private fun drawMissing(canvas: Canvas, x: Int, y: Int) {
        val bg = Paint().apply { color = Color.rgb(38, 38, 46) }
        canvas.drawRect(
            x.toFloat(),
            y.toFloat(),
            (x + FRAME_W).toFloat(),
            (y + FRAME_H).toFloat(),
            bg
        )
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 120, 132)
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("нет кадра", x + FRAME_W / 2f, y + FRAME_H / 2f, text)
    }

    private fun drawCellBorder(canvas: Canvas, cellX: Int, cellY: Int) {
        val line = Paint().apply {
            color = 0x33FFFFFF.toInt()
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(
            cellX.toFloat(),
            cellY.toFloat(),
            (cellX + CELL_W).toFloat(),
            (cellY + CELL_H).toFloat(),
            line
        )
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

    private fun encode(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
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
