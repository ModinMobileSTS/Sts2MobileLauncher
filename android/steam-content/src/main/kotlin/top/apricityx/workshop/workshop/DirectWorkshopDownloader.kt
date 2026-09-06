package top.apricityx.workshop.workshop

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request

class DirectWorkshopDownloader(private val client: OkHttpClient) {
    suspend fun download(
        request: WorkshopDownloadRequest,
        item: ResolvedWorkshopItem.DirectUrlItem,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val outputFile = File(request.outputDir, sanitizeFileName(item.fileName))
            val partialFile = File(request.outputDir, "${outputFile.name}.part")
            outputFile.parentFile?.mkdirs()
            if (outputFile.isFile()) {
                if (item.size == null || outputFile.length() == item.size) {
                    log("Reusing completed direct download ${outputFile.name}")
                    emit(
                        DownloadEvent.Progress(
                            writtenBytes = outputFile.length(),
                            totalBytes = item.size,
                            completedFiles = 1,
                            totalFiles = 1,
                        )
                    )
                    emitCompletedFile(outputFile, emit)
                    return@withContext
                }

                log("Found incomplete direct download file, moving it to partial cache")
                if (!partialFile.exists()) {
                    outputFile.copyTo(partialFile, overwrite = true)
                }
                outputFile.delete()
            }

            log("Starting direct file download")
            var lastError: Throwable? = null

            for (attempt in 1..MAX_DIRECT_DOWNLOAD_ATTEMPTS) {
                val existingBytes = partialFile.takeIf(File::exists)?.length() ?: 0L
                if (existingBytes > 0L) {
                    log("Resuming direct file_url download from $existingBytes bytes")
                }

                try {
                    val request =
                        Request.Builder()
                            .url(item.fileUrl)
                            .apply {
                                if (existingBytes > 0L) {
                                    header("Range", "bytes=$existingBytes-")
                                }
                            }
                            .build()

                    coroutineScope {
                        val call = client.newCall(request)
                        val cancellation =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    awaitCancellation()
                                } finally {
                                    call.cancel()
                                }
                            }
                        try {
                            call.execute().use { response ->
                                if (existingBytes > 0L && response.code == 200) {
                                    log(
                                        "Direct download server ignored range request, restarting from zero"
                                    )
                                    partialFile.delete()
                                    return@use
                                }

                                if (!response.isSuccessful) {
                                    throw WorkshopDownloadException(
                                        "Direct download failed: ${response.code}"
                                    )
                                }

                                val append = existingBytes > 0L && response.code == 206
                                val totalBytes =
                                    item.size
                                        ?: response.body
                                            ?.contentLength()
                                            ?.takeIf { it >= 0 }
                                            ?.let { contentLength ->
                                                if (append) existingBytes + contentLength
                                                else contentLength
                                            }

                                val progress = DirectProgressEmitter(totalBytes, emit)
                                if (append) {
                                    progress.emit(existingBytes, force = true)
                                }

                                response.body?.byteStream()?.use { input ->
                                    FileOutputStream(partialFile, append)
                                        .buffered(IO_BUFFER_SIZE)
                                        .use { output ->
                                            val buffer = ByteArray(IO_BUFFER_SIZE)
                                            var written = existingBytes
                                            var bytesSinceYield = 0L

                                            while (true) {
                                                val read = input.read(buffer)
                                                if (read == -1) {
                                                    break
                                                }
                                                output.write(buffer, 0, read)
                                                written += read
                                                bytesSinceYield += read
                                                progress.emit(written)
                                                if (bytesSinceYield >= IO_YIELD_BYTES) {
                                                    yield()
                                                    bytesSinceYield = 0L
                                                }
                                            }
                                            progress.emit(written, force = true)
                                        }
                                }
                                    ?: throw WorkshopDownloadException(
                                        "Direct download body was empty"
                                    )
                            }
                        } finally {
                            cancellation.cancel()
                        }
                    }

                    if (!partialFile.isFile) {
                        continue
                    }
                    if (!partialFile.renameTo(outputFile)) {
                        partialFile.copyTo(outputFile, overwrite = true)
                        partialFile.delete()
                    }
                    emitCompletedFile(outputFile, emit)
                    return@withContext
                } catch (error: Throwable) {
                    if (
                        error is CancellationException ||
                            error is InterruptedException ||
                            error is Error
                    )
                        throw error
                    lastError = error
                    log(
                        "Direct download attempt $attempt/$MAX_DIRECT_DOWNLOAD_ATTEMPTS failed: ${error.message}"
                    )
                }
            }

            throw WorkshopDownloadException("Direct download exhausted retries", lastError)
        }

    private fun sanitizeFileName(fileName: String): String =
        fileName.replace('\\', '_').replace('/', '_').ifBlank { "workshop.bin" }

    private suspend fun emitCompletedFile(outputFile: File, emit: suspend (DownloadEvent) -> Unit) {
        emit(
            DownloadEvent.FileCompleted(
                DownloadedFileInfo(
                    relativePath = outputFile.name,
                    sizeBytes = outputFile.length(),
                    modifiedEpochMillis = outputFile.lastModified(),
                )
            )
        )
    }

    private class DirectProgressEmitter(
        private val totalBytes: Long?,
        private val emit: suspend (DownloadEvent) -> Unit,
    ) {
        private var lastEmittedAtMs = 0L
        private var lastEmittedBytes = -1L

        suspend fun emit(writtenBytes: Long, force: Boolean = false) {
            val now = System.currentTimeMillis()
            val byteDelta =
                if (lastEmittedBytes < 0L) Long.MAX_VALUE
                else (writtenBytes - lastEmittedBytes).coerceAtLeast(0L)
            if (
                !force &&
                    byteDelta < PROGRESS_EMIT_BYTES &&
                    now - lastEmittedAtMs < PROGRESS_EMIT_INTERVAL_MS
            ) {
                return
            }
            lastEmittedAtMs = now
            lastEmittedBytes = writtenBytes
            emit(
                DownloadEvent.Progress(
                    writtenBytes = writtenBytes,
                    totalBytes = totalBytes,
                    completedFiles = 0,
                    totalFiles = 1,
                )
            )
        }
    }

    companion object {
        private const val MAX_DIRECT_DOWNLOAD_ATTEMPTS = 3
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val IO_YIELD_BYTES = 1024 * 1024L
        private const val PROGRESS_EMIT_BYTES = 512 * 1024L
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L
    }
}
