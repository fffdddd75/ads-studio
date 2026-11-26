package com.adsstudio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает локальную LLM (Qwen2.5-0.5B q8) с GitHub Releases.
 * Докачка по HTTP Range, проверка точного размера.
 */
class LlmModelManager(private val context: Context) {

    private val dir = File(context.filesDir, "llm")
    private val model = File(dir, MODEL_FILE)

    fun isReady(): Boolean = model.exists() && model.length() == EXPECTED_BYTES

    fun reset() {
        if (model.exists()) model.delete()
    }

    suspend fun ensure(onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext model.absolutePath

        dir.mkdirs()
        if (model.exists() && model.length() > EXPECTED_BYTES) model.delete()

        download(onProgress)

        if (!isReady()) {
            model.delete()
            error("Нейросеть скачалась не полностью. Нажми «Распознать речь» ещё раз — продолжу.")
        }
        model.absolutePath
    }

    private fun download(onProgress: (Float) -> Unit) {
        var start = if (model.exists()) model.length() else 0L
        if (start > EXPECTED_BYTES) {
            model.delete()
            start = 0L
        }

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AdsStudio/0.3")
            setRequestProperty("Accept-Encoding", "identity")
            if (start > 0) setRequestProperty("Range", "bytes=$start-")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            val append = start > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                error("Не удалось скачать нейросеть: HTTP $code")
            }
            if (!append) start = 0L

            val remaining = connection.contentLengthLong
            val total = if (remaining > 0) start + remaining else EXPECTED_BYTES
            var done = start

            connection.inputStream.use { input ->
                FileOutputStream(model, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            onProgress((done.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            if (model.length() != EXPECTED_BYTES) {
                error(
                    "Скачивание оборвалось: ${model.length() / 1048576} из " +
                        "${EXPECTED_BYTES / 1048576} МБ. Нажми «Распознать речь» ещё раз — продолжу."
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MODEL_FILE = "qwen2.5-0.5b-instruct-q8.task"
        private const val MODEL_URL =
            "https://github.com/fffdddd75/ads-studio/releases/download/" +
                "llm-qwen-0.5b-v1/qwen2.5-0.5b-instruct-q8.task"
        private const val EXPECTED_BYTES = 546_660_344L
    }
}
