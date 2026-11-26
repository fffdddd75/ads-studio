package com.adsstudio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Достаёт аудиодорожку из видео и пишет её в WAV 16 kHz mono PCM16 —
 * именно в таком виде её ждёт Vosk.
 */
object AudioExtractor {

    private const val TARGET_RATE = 16000

    suspend fun toWav(
        context: Context,
        uri: Uri,
        outFile: File,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("В этом видео нет звуковой дорожки")

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный аудиокодек")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()

            val writer = WavWriter(outFile)
            var sampleRate = format.getIntOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = format.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = LinearResampler(sampleRate, TARGET_RATE) { writer.write(it) }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inputIndex = dec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = dec.getInputBuffer(inputIndex) ?: error("Нет входного буфера")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            dec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                            if (durationUs > 0) {
                                onProgress((pts.toFloat() / durationUs).coerceIn(0f, 0.99f))
                            }
                        }
                    }
                }

                val outputIndex = dec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = dec.outputFormat
                        val newRate = outFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        if (newRate != sampleRate) {
                            resampler.flush()
                            sampleRate = newRate
                            resampler = LinearResampler(sampleRate, TARGET_RATE) { writer.write(it) }
                        }
                    }

                    outputIndex >= 0 -> {
                        val output = dec.getOutputBuffer(outputIndex)
                        if (output != null && bufferInfo.size > 0) {
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            output.order(ByteOrder.LITTLE_ENDIAN)
                            consume(output, channels, pcmEncoding, resampler)
                        }
                        dec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            resampler.flush()
            writer.close()
            onProgress(1f)
            outFile
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    private fun consume(
        buffer: ByteBuffer,
        channels: Int,
        encoding: Int,
        resampler: LinearResampler
    ) {
        val channelCount = channels.coerceAtLeast(1)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val frames = buffer.remaining() / (4 * channelCount)
            repeat(frames) {
                var sum = 0f
                repeat(channelCount) { sum += buffer.float }
                resampler.push(sum / channelCount)
            }
        } else {
            val frames = buffer.remaining() / (2 * channelCount)
            repeat(frames) {
                var sum = 0f
                repeat(channelCount) { sum += buffer.short / 32768f }
                resampler.push(sum / channelCount)
            }
        }
    }

    private fun MediaFormat.getIntOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private class LinearResampler(
        inRate: Int,
        outRate: Int,
        private val sink: (Short) -> Unit
    ) {
        private val ratio = inRate.toDouble() / outRate
        private var index = 0L
        private var previous = 0f
        private var current = 0f
        private var position = 0.0

        fun push(sample: Float) {
            if (index == 0L) {
                previous = sample
                current = sample
            } else {
                previous = current
                current = sample
            }
            if (index > 0) {
                while (position < index.toDouble()) {
                    val t = (position - (index - 1)).toFloat().coerceIn(0f, 1f)
                    emit(previous + (current - previous) * t)
                    position += ratio
                }
            }
            index++
        }

        fun flush() {
            if (index == 0L) return
            while (position < index.toDouble()) {
                emit(current)
                position += ratio
            }
        }

        private fun emit(value: Float) {
            val v = (value * 32767f).toInt().coerceIn(-32768, 32767)
            sink(v.toShort())
        }
    }

    private class WavWriter(private val file: File) {
        private val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
        private val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        private var samples = 0L

        init {
            out.write(ByteArray(44))
        }

        fun write(sample: Short) {
            if (buffer.remaining() < 2) drain()
            buffer.putShort(sample)
            samples++
        }

        private fun drain() {
            out.write(buffer.array(), 0, buffer.position())
            buffer.clear()
        }

        fun close() {
            drain()
            out.close()

            val dataSize = samples * 2
            RandomAccessFile(file, "rw").use { raf ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt((36 + dataSize).toInt())
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16)
                header.putShort(1)
                header.putShort(1)
                header.putInt(TARGET_RATE)
                header.putInt(TARGET_RATE * 2)
                header.putShort(2)
                header.putShort(16)
                header.put("data".toByteArray())
                header.putInt(dataSize.toInt())
                raf.seek(0)
                raf.write(header.array())
            }
        }
    }
}
