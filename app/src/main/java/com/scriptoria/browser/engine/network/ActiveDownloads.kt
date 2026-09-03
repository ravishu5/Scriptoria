package com.scriptoria.browser.engine.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

enum class DownloadStatus { STARTING, RUNNING, FAILED }

data class ActiveDownload(
    val id: Int,
    val name: String,
    val url: String,
    val mimeType: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val bytesWritten: Long = 0L,
    /** Null when the server sent no Content-Length, which makes progress unknowable. */
    val totalBytes: Long? = null,
    val bytesPerSecond: Long = 0L,
    val status: DownloadStatus = DownloadStatus.STARTING,
    val error: String? = null
) {
    val percent: Int?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { ((bytesWritten * 100) / it).toInt().coerceIn(0, 100) }

    val etaSeconds: Long?
        get() {
            val total = totalBytes ?: return null
            if (bytesPerSecond <= 0L || total <= bytesWritten) return null
            return (total - bytesWritten) / bytesPerSecond
        }
}

/**
 * In-memory registry of downloads currently in flight, so the Downloads screen can show
 * live progress rather than only the files already on disk.
 *
 * Deliberately not persisted: the foreground service holds the transfers, and if the process
 * dies the transfers die with it — a restored entry would be describing something that is no
 * longer running.
 */
object ActiveDownloads {

    private val _downloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val downloads: StateFlow<List<ActiveDownload>> = _downloads.asStateFlow()

    private val _completions = MutableStateFlow(0L)

    /** Incremented whenever a download finishes, so file listings can refresh themselves. */
    val completions: StateFlow<Long> = _completions.asStateFlow()

    private val idGenerator = AtomicInteger(1)

    /**
     * Ids live here rather than in the service because failed entries outlive the service that
     * created them. A per-instance counter would restart at 1 once the service stopped and
     * collide with a failed entry still on screen, producing duplicate list keys.
     */
    fun nextId(): Int = idGenerator.getAndIncrement()

    fun start(
        id: Int,
        name: String,
        url: String,
        mimeType: String?,
        userAgent: String?,
        referer: String?
    ) {
        _downloads.update { current ->
            current + ActiveDownload(
                id = id,
                name = name,
                url = url,
                mimeType = mimeType,
                userAgent = userAgent,
                referer = referer
            )
        }
    }

    fun progress(id: Int, name: String, written: Long, total: Long?, bytesPerSecond: Long) {
        _downloads.update { current ->
            current.map {
                if (it.id == id) {
                    it.copy(
                        name = name,
                        bytesWritten = written,
                        totalBytes = total,
                        bytesPerSecond = bytesPerSecond,
                        status = DownloadStatus.RUNNING
                    )
                } else {
                    it
                }
            }
        }
    }

    fun fail(id: Int, message: String) {
        _downloads.update { current ->
            current.map {
                if (it.id == id) {
                    it.copy(status = DownloadStatus.FAILED, error = message, bytesPerSecond = 0L)
                } else {
                    it
                }
            }
        }
    }

    /** Removes the entry and signals listeners that a new file exists on disk. */
    fun finish(id: Int) {
        _downloads.update { current -> current.filterNot { it.id == id } }
        _completions.update { it + 1 }
    }

    /** Removes the entry without signalling a completion (cancelled, or dismissed by the user). */
    fun remove(id: Int) {
        _downloads.update { current -> current.filterNot { it.id == id } }
    }
}
