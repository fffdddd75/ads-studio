package com.adsstudio

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Обёртка над Vosk: WAV 16 kHz mono -> фразы с таймкодами.
 * Vosk считает нативно и не требует NDK-сборки.
 */
class VoskAsr(modelDir: String) : Closeable {

    private val model = Model(modelDir)
    private val recognizer = Recognizer(model, 16000f).apply { setWords(true) }

    fun transcribe(wav: File, onProgress: (Float) -> Unit = {}): List<Utterance> {
        val words = mutableListOf<Word>()
        val result = mutableListOf<Utterance>()
        val total = wav.length().coerceAtLeast(1)

        fun flushWords() {
            if (words.isEmpty()) return
            val start = words.first().startMs
            val end = words.last().endMs
            result += Utterance(
                startMs = start,
                endMs = end.coerceAtLeast(start + 500),
                text = words.joinToString(" ") { it.text }
            )
            words.clear()
        }

        RandomAccessFile(wav, "r").use { file ->
            file.seek(44)
            val buffer = ByteArray(8192)
            var done = 0L
            while (true) {
                val read = file.read(buffer)
                if (read <= 0) break
                val even = read - (read % 2)
                if (recognizer.acceptWaveForm(buffer, even)) {
                    addWords(recognizer.result, words) { flushWords() }
                }
                done += even
                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
        }

        addWords(recognizer.finalResult, words) { flushWords() }
        flushWords()
        return result
    }

    private fun addWords(
        json: String,
        into: MutableList<Word>,
        flush: () -> Unit
    ) {
        val array = JSONObject(json).optJSONArray("result") ?: return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val text = item.optString("word").trim()
            if (text.isEmpty()) continue

            val start = (item.optDouble("start", 0.0) * 1000).toLong()
            val end = (item.optDouble("end", 0.0) * 1000).toLong()

            if (into.isNotEmpty() && start - into.last().endMs > 800) flush()

            into += Word(text, start, end.coerceAtLeast(start + 100))
            if (into.size >= 30) flush()
        }
    }

    override fun close() {
        recognizer.close()
        model.close()
    }

    private data class Word(
        val text: String,
        val startMs: Long,
        val endMs: Long
    )
}
