package com.scriptoria.browser.data

import com.scriptoria.browser.data.model.DownloadType
import com.scriptoria.browser.data.repository.DownloadManagerRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadManagerRepositoryTest {

    @Test
    fun testResolveDownloadTypes() {
        assertEquals(DownloadType.VIDEO, DownloadManagerRepository.resolveDownloadType("video.mp4", "video/mp4"))
        assertEquals(DownloadType.VIDEO, DownloadManagerRepository.resolveDownloadType("clip.mkv", "application/octet-stream"))
        assertEquals(DownloadType.IMAGE, DownloadManagerRepository.resolveDownloadType("photo.jpg", "image/jpeg"))
        assertEquals(DownloadType.AUDIO, DownloadManagerRepository.resolveDownloadType("song.mp3", "audio/mpeg"))
        assertEquals(DownloadType.ARCHIVE, DownloadManagerRepository.resolveDownloadType("files.zip", "application/zip"))
        assertEquals(DownloadType.DOCUMENT, DownloadManagerRepository.resolveDownloadType("doc.pdf", "application/pdf"))
        assertEquals(DownloadType.OTHER, DownloadManagerRepository.resolveDownloadType("data.bin", "application/octet-stream"))
    }

    @Test
    fun testFormatSize() {
        assertEquals("500 B", DownloadManagerRepository.formatSize(500))
        assertEquals("10 KB", DownloadManagerRepository.formatSize(10240))
        assertEquals("21.2 MB", DownloadManagerRepository.formatSize(22229811))
    }

    @Test
    fun testGuessMimeType() {
        assertEquals("video/mp4", DownloadManagerRepository.guessMimeType("sample.mp4"))
        assertEquals("application/zip", DownloadManagerRepository.guessMimeType("archive.zip"))
        assertEquals("image/png", DownloadManagerRepository.guessMimeType("icon.png"))
    }
}
