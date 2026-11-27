package com.adsstudio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Лёгкий анализ звука без отдельной модели: громкость, паузы и темп речи.
 * Читает тот же WAV 16 кГц, который уже сделали для Vosk, поэтому память
 * почти не тратится: файл читается кусками, кадры не декодируются.
 */
data class AudioHint(
    val energy: Float,
    val silence: Float,
    val speechRate: Float,
    val peak: Float
)

object AudioFeatures {

    fun analyze(wav: File, clips: List<SuggestedClip>): List<AudioHint?> {
        if (clips.isEmpty() || !wav.exists()) return List(clips.size) { null }
        return runCatching {
            RandomAccessFile(wav, "r").use { file ->
                val info = readInfo(file)
                if (info == null) {
                    List(clips.size) { null }
                } else {
                    clips.map { clip -> analyzeClip(file, info, clip) }
                }
            }
        }.getOrDefault(List(clips.size) { null })
    }

    private data class WavInfo(
        val dataOffset: Long,
        val sampleRate: Int,
        val channels: Int,
        val bits: Int
    ) {
        val bytesPerFrame: Int get() = channels * (bits / 8)
    }

    private fun readInfo(file: RandomAccessFile): WavInfo? {
        if (file.length() < 44) return null
        file.seek(0)
        val header = ByteArray(12)
        file.readFully(header)
        if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null

        var pos = 12L
        var sampleRate = 16000
        var channels = 1
        var bits = 16
        var dataOffset = -1L

        while (pos + 8 <= file.length()) {
            file.seek(pos)
            val id = ByteArray(4)
            file.readFully(id)
            val size = readIntLe(file)
            if (size < 0) break
            val name = String(id)
            if (name == "fmt ") {
                val fmt = ByteArray(size.coerceAtMost(32))
                file.readFully(fmt)
                if (fmt.size >= 16) {
                    channels = leShort(fmt, 2)
                    sampleRate = leInt(fmt, 4)
                    bits = leShort(fmt, 14)
                }
            } else if (name == "data") {
                dataOffset = pos + 8
                break
            }
            pos += 8 + size + (size and 1)
        }

        if (dataOffset < 0 || channels <= 0 || bits != 16 || sampleRate <= 0) return null
        return WavInfo(dataOffset, sampleRate, channels, bits)
    }

    private fun analyzeClip(
        file: RandomAccessFile,
        info: WavInfo,
        clip: SuggestedClip
    ): AudioHint? {
        val startSample = clip.startMs.coerceAtLeast(0) * info.sampleRate / 1000
        val endSample = clip.endMs.coerceAtLeast(clip.startMs) * info.sampleRate / 1000
        val startByte = info.dataOffset + startSample * info.bytesPerFrame
        val endByte = (info.dataOffset + endSample * info.bytesPerFrame)
            .coerceAtMost(file.length())
        if (endByte <= startByte) return null

        file.seek(startByte)
        var remaining = endByte - startByte
        val buffer = ByteArray(64 * 1024)
        var sumSquares = 0.0
        var samples = 0L
        var peak = 0
        var windowSquares = 0.0
        var windowSamples = 0
        var quietWindows = 0
        var windows = 0
        val windowSize = (info.sampleRate / 10).coerceAtLeast(1)
        val quietRms = 0.004

        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            file.readFully(buffer, 0, toRead)
            var i = 0
            while (i + 1 < toRead) {
                val raw = ((buffer[i + 1].toInt() and 0xFF) shl 8) or
                    (buffer[i].toInt() and 0xFF)
                val value = if (raw >= 32768) raw - 65536 else raw
                val absolute = if (value < 0) -value else value
                if (absolute > peak) peak = absolute

                val normalized = value / 32768.0
                val square = normalized * normalized
                sumSquares += square
                windowSquares += square
                samples++
                windowSamples++

                if (windowSamples >= windowSize) {
                    if (sqrt(windowSquares / windowSamples) < quietRms) quietWindows++
                    windows++
                    windowSquares = 0.0
                    windowSamples = 0
                }
                i += 2
            }
            remaining -= toRead
        }

        if (windowSamples > 0) {
            if (sqrt(windowSquares / windowSamples) < quietRms) quietWindows++
            windows++
        }
        if (samples == 0L) return null

        val rms = sqrt(sumSquares / samples)
        val db = 20.0 * log10(rms + 1e-9)
        val energy = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
        val durationSec = (clip.endMs - clip.startMs).coerceAtLeast(1) / 1000.0
        val words = clip.text.split(Regex("\\s+")).count { it.isNotBlank() }
        val speechRate = (words / durationSec).toFloat()

        return AudioHint(
            energy = energy,
            silence = if (windows > 0) quietWindows.toFloat() / windows else 0f,
            speechRate = speechRate,
            peak = (peak / 32768f).coerceIn(0f, 1f)
        )
    }

    private fun readIntLe(file: RandomAccessFile): Int {
        val bytes = ByteArray(4)
        file.readFully(bytes)
        return leInt(bytes, 0)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
