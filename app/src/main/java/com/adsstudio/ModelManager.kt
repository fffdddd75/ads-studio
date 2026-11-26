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
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Скачивает и распаковывает русскую модель Vosk при первом запуске.
 *
 * Сеть на телефоне рвётся, поэтому:
 *  - качаем с докачкой (HTTP Range);
 *  - проверяем размер и целостность zip;
 *  - битый архив удаляем и качаем заново, а не падаем с ZLIB-ошибкой.
 */
class ModelManager(private val context: Context) {

    private val root = File(context.filesDir, "asr")
    private val archive = File(context.cacheDir, "vosk-model-small-ru.zip")

    fun findModelDir(): File? {
        if (!root.isDirectory) return null
        return root.listFiles()?.firstOrNull { dir ->
            dir.isDirectory && File(dir, "am/final.mdl").exists()
        }
    }

    suspend fun ensure(onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        findModelDir()?.let { return@withContext it.absolutePath }

        root.mkdirs()

        // Огрызок без zip-заголовка докачивать бессмысленно.
        if (archive.exists() && !hasZipHeader(archive)) archive.delete()

        if (!archive.exists() || !isValidZip(archive)) {
            download(onProgress)
        }

        onProgress(-1f)
        root.deleteRecursively()
        root.mkdirs()

        try {
            unzip(archive, root)
        } catch (error: Exception) {
            archive.delete()
            root.deleteRecursively()
            throw IllegalStateException(
                "Архив модели оказался битым. Проверь интернет и попробуй ещё раз.",
                error
            )
        }

        archive.delete()

        findModelDir()?.absolutePath
            ?: run {
                root.deleteRecursively()
                error("В архиве модели не нашлось нужных файлов")
            }
    }

    private fun download(onProgress: (Float) -> Unit) {
        var start = if (archive.exists()) archive.length() else 0L
        if (start > MAX_ARCHIVE_BYTES) {
            archive.delete()
            start = 0L
        }

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AdsStudio/0.1")
            if (start > 0) setRequestProperty("Range", "bytes=$start-")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            val append = start > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                error("Не удалось скачать модель: HTTP $code")
            }
            if (!append) start = 0L

            val remaining = connection.contentLengthLong
            val total = if (remaining > 0) start + remaining else -1L
            var done = start

            connection.inputStream.use { input ->
                FileOutputStream(archive, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            val size = archive.length()
            if (total > 0 && size != total) {
                error(
                    "Скачивание оборвалось: ${size / 1048576} из ${total / 1048576} МБ. " +
                        "Нажми «Распознать речь» ещё раз — продолжу с этого места."
                )
            }
            if (!isValidZip(archive)) {
                archive.delete()
                error("Файл модели скачался повреждённым. Попробуй ещё раз.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidZip(file: File): Boolean = try {
        ZipFile(file).use { it.size() > 0 }
    } catch (_: Exception) {
        false
    }

    private fun hasZipHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                input.read(header) == 4 &&
                    header[0] == 'P'.code.toByte() &&
                    header[1] == 'K'.code.toByte()
            }
        } catch (_: Exception) {
            false
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
        private const val MAX_ARCHIVE_BYTES = 300L * 1024 * 1024
    }
}
