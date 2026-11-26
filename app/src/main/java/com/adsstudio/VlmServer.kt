package com.adsstudio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class VlmPart(
    val text: String? = null,
    val imageBase64: String? = null
)

/**
 * Локальный llama-server с Qwen2.5-VL-3B.
 * Держит модель в памяти и отвечает по OpenAI-совместимому HTTP на 127.0.0.1.
 */
class VlmServer(private val context: Context) : Closeable {

    private var process: Process? = null
    private var port: Int = 18081
    private val log = File(context.filesDir, "vlm-server.log")

    private val baseUrl: String
        get() = "http://127.0.0.1:$port"

    suspend fun start(
        modelPath: String,
        mmprojPath: String,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (process?.isAlive == true && health()) return@withContext

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val server = File(nativeDir, "libllama-server.so")
        if (!server.exists()) {
            error("Видео-движок не найден в сборке")
        }
        server.setExecutable(true)

        // Если сервер остался от прошлого запуска — просто переиспользуем его.
        if (health()) return@withContext
        if (process?.isAlive == true) stop()

        log.writeText("")

        val command = listOf(
            server.absolutePath,
            "-m", modelPath,
            "--mmproj", mmprojPath,
            "-c", "6144",
            "-np", "1",
            "-t", "4",
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--no-webui",
            "--jinja",
            "--image-max-tokens", "384",
            "--log-file", log.absolutePath,
            "--log-colors", "off"
        )

        val builder = ProcessBuilder(command)
        builder.directory(context.filesDir)
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        builder.environment()["LD_LIBRARY_PATH"] = nativeDir
        builder.environment()["GGML_BACKEND_PATH"] = nativeDir
        builder.environment()["HOME"] = context.filesDir.absolutePath
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath

        onStatus("Загружаю видео-ИИ…")
        val started = try {
            builder.start()
        } catch (error: Throwable) {
            error("Видео-ИИ не запустился: ${error.message ?: "ошибка"}")
        }
        process = started

        if (!waitForHealth(started, timeoutMs = 240_000)) {
            val tail = logTail()
            stop()
            error("Видео-ИИ не запустился${if (tail.isBlank()) "" else ": $tail"}")
        }
    }

    fun rank(parts: List<VlmPart>, maxTokens: Int = 96): String {
        val content = JSONArray()
        parts.forEach { part ->
            part.text?.let { text ->
                content.put(JSONObject().put("type", "text").put("text", text))
            }
            part.imageBase64?.let { image ->
                content.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$image")
                        )
                )
            }
        }

        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    "Ты — редактор коротких видео. Отвечай строго по инструкции, без рассуждений."
                )
        )
        messages.put(JSONObject().put("role", "user").put("content", content))

        val body = JSONObject()
            .put("model", "qwen2.5-vl-3b")
            .put("messages", messages)
            .put("temperature", 0.1)
            .put("top_p", 0.9)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .toString()

        val connection = (URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 600_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()
                error("HTTP $code${message?.let { ": ${it.take(180)}" } ?: ""}")
            }

            val root = JSONObject(text)
            return root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
        } finally {
            connection.disconnect()
        }
    }

    private fun waitForHealth(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return false
            if (health()) return true
            Thread.sleep(1000)
        }
        return false
    }

    private fun health(): Boolean {
        return try {
            val connection = (URL("$baseUrl/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
            }
            try {
                connection.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                connection.disconnect()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun logTail(): String {
        return runCatching {
            log.readLines().takeLast(12).joinToString(" ").take(400)
        }.getOrDefault("")
    }

    fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            running.destroy()
            if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
        }
    }

    override fun close() {
        stop()
    }
}
