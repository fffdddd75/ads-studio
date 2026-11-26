package com.adsstudio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Скачивает и распаковывает русскую модель Vosk при первом запуске.
 * Модель не лежит в APK — иначе он весил бы лишние 45 МБ.
 */
class ModelManager(private val context: Context) {

    private val root = File(context.filesDir, "asr")
    private val archive = File(context.cacheDir, "vosk-model-small-ru.zip")

    fun findModelDir(): File? {
        if (!root.isDirectory) return null
        return root.listFiles()?.firstOrNull { dir ->
            File(dir, "am/final.mdl").exists()
        }
    }

    suspend fun ensure(onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        findModelDir()?.let { return@withContext it.absolutePath }

        root.mkdirs()
        if (!archive.exists() || archive.length() < 1_000_000) {
            download(onProgress)
        }

        onProgress(-1f)
        unzip(archive, root)
        archive.delete()

        findModelDir()?.absolutePath
            ?: error("Модель скачалась, но папку с моделью найти не удалось")
    }

    private fun download(onProgress: (Float) -> Unit) {
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AdsStudio/0.1")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Не удалось скачать модель: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(zipFile: File, destination: File) {
        val destinationPath = destination.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(destination, entry.name)
                val targetPath = target.canonicalPath

                if (!targetPath.startsWith(destinationPath + File.separator)) {
                    zip.closeEntry()
                    continue
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

    companion object {
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    }
}
