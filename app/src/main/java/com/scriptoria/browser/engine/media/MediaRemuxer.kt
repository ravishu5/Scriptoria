package com.scriptoria.browser.engine.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Rewraps already-encoded audio and video into a single MP4.
 *
 * This is a container change, not a transcode: samples are copied across untouched, so it is fast
 * and lossless. It serves the two cases where the bytes we can download are not yet a file anyone
 * can play — a concatenated MPEG-TS stream from HLS, and a site that publishes its video and audio
 * as separate adaptive tracks.
 */
object MediaRemuxer {

    private const val TAG = "MediaRemuxer"
    private const val MIN_BUFFER_BYTES = 1 shl 20      // 1 MB
    private const val MAX_BUFFER_BYTES = 16 shl 20     // 16 MB

    /** Raised when the inputs hold no track this device can put into an MP4. */
    class UnsupportedMediaException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /**
     * Writes every audio and video track found across [inputs] into [output] as MP4.
     *
     * Inputs are read in order and the first video track and first audio track win, which is what
     * pairs a video-only file with an audio-only one. A single input carrying both is equally fine.
     *
     * @param onProgress fraction in 0..1, called as samples are copied.
     */
    fun remux(
        inputs: List<File>,
        output: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ) {
        require(inputs.isNotEmpty()) { "no inputs to remux" }

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = mutableListOf<MediaExtractor>()
        // Per extractor: which of its tracks maps to which track of the muxer.
        val trackMaps = mutableListOf<Pair<MediaExtractor, Map<Int, Int>>>()
        var hasVideo = false
        var hasAudio = false
        var bufferBytes = MIN_BUFFER_BYTES
        var started = false

        try {
            for (input in inputs) {
                val extractor = MediaExtractor()
                extractor.setDataSource(input.absolutePath)
                extractors += extractor

                val map = mutableMapOf<Int, Int>()
                for (track in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")
                    if (!isVideo && !isAudio) continue
                    if (isVideo && hasVideo) continue
                    if (isAudio && hasAudio) continue

                    val muxerTrack = try {
                        muxer.addTrack(format)
                    } catch (e: Exception) {
                        // MP4 cannot hold every codec — Opus and VP9 in particular depend on the
                        // device's API level — and there is no point failing the whole file for a
                        // track we could not have written anyway.
                        Log.w(TAG, "Skipping track with unsupported format $mime: ${e.message}")
                        continue
                    }

                    if (isVideo) hasVideo = true else hasAudio = true
                    extractor.selectTrack(track)
                    map[track] = muxerTrack
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val declared = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        bufferBytes = maxOf(bufferBytes, declared)
                    }
                }
                if (map.isNotEmpty()) trackMaps += extractor to map
            }

            if (trackMaps.isEmpty()) {
                throw UnsupportedMediaException("No audio or video track could be written to MP4")
            }

            muxer.start()
            started = true

            val totalBytes = inputs.sumOf { it.length() }.coerceAtLeast(1L)
            var copiedBytes = 0L
            val buffer = ByteBuffer.allocate(bufferBytes.coerceAtMost(MAX_BUFFER_BYTES))
            val info = MediaCodec.BufferInfo()

            for ((extractor, map) in trackMaps) {
                while (true) {
                    if (isCancelled()) return
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break

                    val muxerTrack = map[extractor.sampleTrackIndex]
                    if (muxerTrack != null) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = extractor.sampleTime
                        // Only the key-frame flag carries over; the others describe decoding, not
                        // storage, and MediaMuxer rejects them.
                        info.flags = if (extractor.sampleFlags and
                            MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        ) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                        muxer.writeSampleData(muxerTrack, buffer, info)
                    }

                    copiedBytes += size
                    onProgress((copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                    extractor.advance()
                }
            }
        } catch (e: UnsupportedMediaException) {
            throw e
        } catch (e: Exception) {
            throw UnsupportedMediaException("Could not rewrap the download as MP4: ${e.message}", e)
        } finally {
            if (started) {
                // stop() throws if no sample was ever written, which would mask the real cause.
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }
}
