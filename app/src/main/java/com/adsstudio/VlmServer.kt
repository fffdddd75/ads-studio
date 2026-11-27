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
    private val pidFile = File(context.filesDir, "vlm-server.pid")

    private val baseUrl: String
        get() = "http://127.0.0.1:$port"

    suspend fun start(
        modelPath: String,
        mmprojPath: String,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (process?.isAlive == true && health()) return@withContext

        cleanupOrphan(context, keepPid = process?.let { pidOf(it) })
        if (process?.isAlive == true) stop()

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val server = File(nativeDir, "libllama-server.so")
        if (!server.exists()) {
            error("Видео-движок не найден в сборке")
        }
        server.setExecutable(true)

        log.writeText("")
        port = freePort()

        val command = listOf(
            server.absolutePath,
            "-m", modelPath,
            "--mmproj", mmprojPath,
            "-c", "1024",
            "-np", "1",
            "-b", "512",
            "-ub", "256",
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--no-webui",
            "--jinja",
            "--image-max-tokens", "96",
            "--no-warmup",
            "--log-colors", "off"
        ) + cpuAffinityArgs()

        val builder = ProcessBuilder(command)
        builder.directory(context.filesDir)
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        builder.environment().remove("LD_PRELOAD")
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
        pidFile.writeText(pidOf(started).toString())

        if (!waitForHealth(started, timeoutMs = 240_000)) {
            val tail = logTail()
            stop()
            error("Видео-ИИ не запустился${if (tail.isBlank()) "" else ": $tail"}")
        }
    }

    fun rank(parts: List<VlmPart>, maxTokens: Int = 32): String {
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
            .put("stop", JSONArray().put("\n"))
            .put("stream", false)
            .toString()

        val connection = (URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 300_000
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
        } catch (timeout: java.net.SocketTimeoutException) {
            val progress = lastProgress()
            error(
                if (progress.isBlank()) "слишком долго думал"
                else "слишком долго думал: $progress"
            )
        } catch (connect: java.net.ConnectException) {
            error("не отвечает")
        } catch (io: java.io.IOException) {
            val tail = logTail()
            error(
                if (tail.isBlank()) "соединение оборвалось"
                else "соединение оборвалось: $tail"
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun lastProgress(): String {
        return runCatching {
            val line = log.readLines()
                .lastOrNull { it.contains("prompt processing") }
                ?: return ""
            val tokens = Regex("n_tokens =\\s*(\\d+)")
                .find(line)?.groupValues?.getOrNull(1)
            val speed = Regex("([\\d.]+) tokens per second")
                .find(line)?.groupValues?.getOrNull(1)
            listOfNotNull(
                tokens?.let { "обработано $it токенов" },
                speed?.let { "$it ток/с" }
            ).joinToString(", ")
        }.getOrDefault("")
    }

    /**
     * На big.LITTLE llama.cpp по умолчанию садится на первые N ядер, а это
     * обычно маленькие. Привязываем потоки к самым быстрым ядрам: на типичном
     * 8-ядерном телефоне это 4–7, что даёт кратный рост префилла.
     */
    private fun cpuAffinityArgs(): List<String> {
        val cpus = bigCores(Runtime.getRuntime().availableProcessors())
            ?: return listOf("-t", "6")
        val threads = cpus.size.coerceIn(2, 6)
        return listOf("-t", threads.toString(), "--cpu-mask", maskOf(cpus))
    }

    private fun bigCores(cores: Int): List<Int>? {
        if (cores < 4) return null

        val freqs = (0 until cores).map { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText()
                    .trim()
                    .toLong()
            }.getOrNull()
        }

        if (freqs.all { it != null }) {
            val max = freqs.filterNotNull().maxOrNull() ?: return null
            val big = freqs.indices.filter { (freqs[it] ?: 0L) >= max * 85 / 100 }
            if (big.isNotEmpty() && big.size < cores) return big
        }

        // Фолбэк: верхняя половина ядер. На 8-ядерных это 4–7.
        if (cores >= 8) return (cores / 2 until cores).toList()
        if (cores == 6) return listOf(4, 5)
        return null
    }

    private fun maskOf(cpus: List<Int>): String {
        var mask = 0L
        cpus.forEach { mask = mask or (1L shl it) }
        return java.lang.Long.toHexString(mask).uppercase()
    }

    private fun pidOf(process: Process): Long = runCatching {
        Process::class.java.getMethod("pid").invoke(process) as Long
    }.getOrDefault(-1L)

    private fun freePort(): Int = runCatching {
        java.net.ServerSocket(0).use { it.localPort }
    }.getOrDefault(18081)

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
        pidFile.delete()
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

    companion object {
        /**
         * Убивает llama-server, оставшийся от прошлого процесса приложения
         * (обновление/force-stop). Иначе он держит порт и 2,8 ГБ памяти.
         */
        fun cleanupOrphan(context: Context, keepPid: Long? = null, scan: Boolean = true) {
            val killed = mutableSetOf<Long>()

            fun kill(pid: Long) {
                if (pid <= 0 || pid == keepPid || !killed.add(pid)) return
                val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull() ?: return
                if (!cmdline.contains("libllama-server")) return
                val ok = runCatching {
                    ProcessBuilder("/system/bin/kill", "-9", pid.toString())
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(3, TimeUnit.SECONDS)
                }.isSuccess
                if (!ok) runCatching { android.os.Process.killProcess(pid.toInt()) }
            }

            val file = File(context.filesDir, "vlm-server.pid")
            val pid = runCatching { file.readText().trim().toLong() }.getOrNull()
            file.delete()
            if (pid != null) kill(pid)

            // Страховка для версий без PID-файла: ищем свои llama-server в /proc.
            if (scan) {
                File("/proc").listFiles()?.forEach { dir ->
                    val candidate = dir.name.toIntOrNull() ?: return@forEach
                    kill(candidate.toLong())
                }
            }
        }
    }
}
