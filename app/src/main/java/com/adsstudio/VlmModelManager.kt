package com.adsstudio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает Qwen2.5-VL-3B (GGUF + mmproj) для локального «зрения».
 * Докачка по HTTP Range, два зеркала, проверка точных размеров.
 */
class VlmModelManager(private val context: Context) {

    private val dir = File(context.filesDir, "vlm")
    private val model = File(dir, MODEL_FILE)
    private val mmproj = File(dir, MMPROJ_FILE)

    fun isReady(): Boolean =
        model.exists() && model.length() == MODEL_BYTES &&
            mmproj.exists() && mmproj.length() == MMPROJ_BYTES

    fun reset() {
        if (model.exists()) model.delete()
        if (mmproj.exists()) mmproj.delete()
    }

    suspend fun ensure(onProgress: (Float) -> Unit): Pair<String, String> =
        withContext(Dispatchers.IO) {
            if (isReady()) {
                return@withContext model.absolutePath to mmproj.absolutePath
            }

            dir.mkdirs()
            val required = TOTAL_BYTES + 300L * 1024 * 1024
            val free = dir.usableSpace
            if (free in 1L until required) {
                error(
                    "Для видео-ИИ нужно ~${TOTAL_BYTES / 1073741824.0} ГБ свободно. " +
                        "Сейчас свободно ${free / 1073741824.0} ГБ."
                )
            }

            val modelSpan = MODEL_BYTES.toFloat() / TOTAL_BYTES.toFloat()
            downloadFile(
                file = model,
                urlName = MODEL_URL_NAME,
                expected = MODEL_BYTES,
                baseProgress = 0f,
                span = modelSpan,
                onProgress = onProgress
            )
            downloadFile(
                file = mmproj,
                urlName = MMPROJ_URL_NAME,
                expected = MMPROJ_BYTES,
                baseProgress = modelSpan,
                span = 1f - modelSpan,
                onProgress = onProgress
            )

            if (!isReady()) {
                model.delete()
                mmproj.delete()
                error("Видео-ИИ скачался не полностью. Нажми «Распознать» ещё раз — продолжу.")
            }
            model.absolutePath to mmproj.absolutePath
        }

    private fun downloadFile(
        file: File,
        urlName: String,
        expected: Long,
        baseProgress: Float,
        span: Float,
        onProgress: (Float) -> Unit
    ) {
        if (file.exists() && file.length() > expected) file.delete()

        var attempts = 0
        var urlIndex = 0
        var lastReported = -1f

        while (file.length() < expected) {
            if (attempts++ >= 8) {
                error(
                    "Скачивание видео-ИИ оборвалось: ${file.length() / 1048576} из " +
                        "${expected / 1048576} МБ. Нажми «Распознать» ещё раз — продолжу."
                )
            }

            var connection: HttpURLConnection? = null
            try {
                val start = file.length()
                val url = URL(BASE_URLS[urlIndex % BASE_URLS.size] + urlName)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AdsStudio/0.4")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (start > 0) setRequestProperty("Range", "bytes=$start-")
                }

                connection.connect()
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_UNAVAILABLE ||
                    code == HttpURLConnection.HTTP_NOT_FOUND
                ) {
                    urlIndex++
                    continue
                }
                if (code == 416) {
                    file.delete()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK &&
                    code != HttpURLConnection.HTTP_PARTIAL
                ) {
                    error("HTTP $code")
                }

                val append = start > 0 && code == HttpURLConnection.HTTP_PARTIAL
                if (!append && file.exists()) file.delete()
                var done = if (append) start else 0L

                val remaining = connection.contentLengthLong
                val total = if (remaining > 0) done + remaining else expected

                connection.inputStream.use { input ->
                    FileOutputStream(file, append).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val fileProgress =
                                    (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                val value = baseProgress + span * fileProgress
                                if (value - lastReported >= 0.004f || value >= 1f) {
                                    lastReported = value
                                    onProgress(value.coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }

                if (file.length() >= expected) break
                attempts = 0
            } catch (error: Throwable) {
                if (file.length() >= expected) break
                urlIndex++
                if (attempts >= 8) {
                    error(
                        "Не удалось скачать видео-ИИ: ${error.message ?: "сеть"}. " +
                            "Проверь интернет и нажми «Распознать» ещё раз."
                    )
                }
                Thread.sleep(1500)
            } finally {
                connection?.disconnect()
            }
        }

        if (file.length() != expected) {
            file.delete()
            error(
                "Видео-ИИ скачался не полностью: ${file.length()} из $expected байт. " +
                    "Нажми «Распознать» ещё раз — продолжу."
            )
        }
    }

    companion object {
        const val TOTAL_LABEL = "2,6 ГБ"

        private const val MODEL_FILE = "qwen2.5-vl-3b-q4_k_m.gguf"
        private const val MMPROJ_FILE = "qwen2.5-vl-3b-mmproj-q8_0.gguf"
        private const val MODEL_URL_NAME = "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"
        private const val MMPROJ_URL_NAME = "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf"
        private const val MODEL_BYTES = 1_929_901_056L
        private const val MMPROJ_BYTES = 844_757_728L
        const val TOTAL_BYTES = MODEL_BYTES + MMPROJ_BYTES

        private val BASE_URLS = listOf(
            "https://hf-mirror.com/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/",
            "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/"
        )
    }
}
