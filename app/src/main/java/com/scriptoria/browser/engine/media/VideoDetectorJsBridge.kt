package com.scriptoria.browser.engine.media

import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * JavaScript interface and multi-channel DOM/network observer for detecting in-page HTML5 video elements.
 */
class VideoDetectorJsBridge(
    private val webViewRef: WeakReference<WebView>,
    private val tabId: String,
    private val detectionManager: VideoDetectionManager
) {
    companion object {
        const val INTERFACE_NAME = "VideoDetectorBridge"

        val SCRIPT_INJECTION = """
            (function() {
                // The script is registered as a document-start script and also injected from
                // onPageStarted and onPageFinished. Without this guard each page ends up with
                // three MutationObservers and three repeating timers scanning the same DOM.
                if (window.__scriptoriaVideoDetectorInstalled) return;
                window.__scriptoriaVideoDetectorInstalled = true;

                // Every reporting path is re-entrant — resource timing replays its whole buffer,
                // the timer fires seven times, and media events fire repeatedly — so without this
                // the same handful of URLs crosses the JS bridge hundreds of times per page.
                var reported = Object.create(null);

                function isYouTube() {
                    return /(^|\.)youtube\.com${'$'}|(^|\.)youtube-nocookie\.com${'$'}|(^|\.)youtu\.be${'$'}/
                        .test(window.location.hostname);
                }

                var lastYouTubeVideoId = '';

                /**
                 * YouTube's media URLs cannot be used as found: they are adaptive, so a sniffed
                 * videoplayback URL is video-only or audio-only and downloads to a silent clip or
                 * a bare audio track. The page's own player response lists the progressive
                 * (muxed) renditions, which are the only ones that stand alone.
                 */
                function reportYouTubeFormats() {
                    try {
                        if (!isYouTube()) return;
                        var pr = window.ytInitialPlayerResponse;
                        if (!pr || !pr.streamingData) return;

                        var details = pr.videoDetails || {};
                        var videoId = details.videoId || '';
                        if (!videoId || videoId === lastYouTubeVideoId) return;

                        function collect(list) {
                            var out = [];
                            for (var i = 0; i < list.length; i++) {
                                var f = list[i];
                                // A format behind signatureCipher needs the player's JS challenge
                                // solved to build a working URL, which is not something we do.
                                if (!f.url) continue;
                                out.push({
                                    url: f.url,
                                    qualityLabel: f.qualityLabel || '',
                                    width: f.width || 0,
                                    height: f.height || 0,
                                    bitrate: f.bitrate || 0,
                                    contentLength: f.contentLength || '',
                                    mimeType: f.mimeType || ''
                                });
                            }
                            return out;
                        }

                        // formats are progressive (one file, both tracks); adaptiveFormats are
                        // video-only and audio-only and have to be paired and rewrapped natively.
                        var progressive = collect(pr.streamingData.formats || []);
                        var adaptive = collect(pr.streamingData.adaptiveFormats || []);
                        if (!progressive.length && !adaptive.length) return;

                        lastYouTubeVideoId = videoId;
                        var payload = JSON.stringify({
                            videoId: videoId,
                            title: details.title || document.title || '',
                            durationSeconds: parseFloat(details.lengthSeconds) || 0,
                            formats: progressive,
                            adaptive: adaptive
                        });
                        if (window.$INTERFACE_NAME && window.$INTERFACE_NAME.onExtractedFormats) {
                            window.$INTERFACE_NAME.onExtractedFormats(payload, window.location.href);
                        }
                    } catch (e) {}
                }

                // Global scan function accessible to Android native
                window.__scriptoriaScanVideos = function() {
                    try {
                        reportYouTubeFormats();
                        findAndReportAllVideos();
                        checkResourceTiming();
                    } catch (e) {}
                };

                function notifyNative(src, mimeType, title, duration) {
                    if (!src || typeof src !== 'string') return;
                    src = src.trim();
                    if (!src || src.startsWith('blob:') || src.startsWith('data:') || src.startsWith('javascript:')) return;
                    if (!src.startsWith('http://') && !src.startsWith('https://')) {
                        try {
                            src = new URL(src, window.location.href).href;
                        } catch (e) {
                            return;
                        }
                    }

                    // A duration discovered later is worth re-reporting once; a bare repeat is not.
                    var dur = parseFloat(duration) || 0;
                    var key = src + '|' + (dur > 0 ? '1' : '0');
                    if (reported[key]) return;
                    reported[key] = true;

                    var videoTitle = title || document.title || '';
                    var page = window.location.href;

                    if (window.$INTERFACE_NAME && window.$INTERFACE_NAME.onVideoFound) {
                        try {
                            window.$INTERFACE_NAME.onVideoFound(src, mimeType || '', videoTitle, dur, page);
                        } catch (e) {}
                    }
                }

                function isMediaUrl(url) {
                    if (!url || typeof url !== 'string') return false;
                    var lower = url.toLowerCase();
                    // Handled by reportYouTubeFormats; sniffing here only yields split tracks.
                    if (isYouTube()) return false;
                    // Matched at the end of the path, never anywhere in the URL: streaming
                    // sites name the segment directory after the source file, as in
                    // ".../video.mp4/seg-1-v1-a1.ts", so a substring test reports every segment
                    // of a stream as its own downloadable video.
                    var path = lower.split('?')[0].split('#')[0];
                    function pathEndsWith(list) {
                        for (var n = 0; n < list.length; n++) {
                            if (path.slice(-list[n].length) === list[n]) return true;
                        }
                        return false;
                    }
                    // Segments are pieces of a stream; only the manifest is downloadable.
                    if (pathEndsWith(['.ts', '.m4s', '.aac', '.vtt', '.key'])) return false;
                    if (pathEndsWith(['.m3u8', '.mpd'])) return true;
                    if (pathEndsWith(['.mp4', '.webm', '.m4v', '.flv', '.mkv', '.mov',
                                      '.avi', '.3gp'])) {
                        return true;
                    }
                    // "/video/" and "/manifest/" are path markers a stream's segments share with
                    // its manifest, so they are deliberately not here.
                    if (lower.includes('videoplayback') || lower.includes('mime=video') ||
                        lower.includes('content-type=video') || lower.includes('format=mp4') ||
                        lower.includes('format=hls')) {
                        return true;
                    }
                    return false;
                }

                var resourceCursor = 0;
                function checkResourceTiming() {
                    try {
                        if (!window.performance || !window.performance.getEntriesByType) return;
                        var entries = window.performance.getEntriesByType('resource');
                        // The buffer only grows, so re-walking it from the start on every call
                        // re-examines everything already seen.
                        for (var i = resourceCursor; i < entries.length; i++) {
                            var entry = entries[i];
                            var name = entry.name;
                            var initiator = entry.initiatorType;
                            if (initiator === 'video' || initiator === 'media' || isMediaUrl(name)) {
                                notifyNative(name, '', document.title, 0);
                            }
                        }
                        resourceCursor = entries.length;
                    } catch (e) {}
                }

                function reportVideoElement(video) {
                    if (!video) return;
                    var dur = video.duration || 0;
                    var title = video.getAttribute('title') || video.getAttribute('aria-label') || document.title || '';

                    // 1. Direct src or currentSrc
                    var src = video.currentSrc || video.src || video.getAttribute('src');
                    if (src && !src.startsWith('blob:') && !src.startsWith('data:')) {
                        notifyNative(src, video.type || '', title, dur);
                    }

                    // 2. Data attributes
                    var dataSrc = video.getAttribute('data-src') || video.getAttribute('data-url') || video.getAttribute('data-video-url');
                    if (dataSrc) {
                        notifyNative(dataSrc, '', title, dur);
                    }

                    // 3. Child <source> elements
                    try {
                        var sources = video.querySelectorAll('source');
                        for (var i = 0; i < sources.length; i++) {
                            var s = sources[i];
                            var sSrc = s.src || s.getAttribute('src') || s.getAttribute('data-src');
                            if (sSrc && !sSrc.startsWith('blob:') && !sSrc.startsWith('data:')) {
                                notifyNative(sSrc, s.type || '', title, dur);
                            }
                        }
                    } catch (e) {}

                    // 4. Attach playback listeners
                    if (!video.__scriptoriaListenersAttached) {
                        video.__scriptoriaListenersAttached = true;
                        video.addEventListener('play', function() { reportVideoElement(this); checkResourceTiming(); }, { passive: true });
                        video.addEventListener('loadedmetadata', function() { reportVideoElement(this); checkResourceTiming(); }, { passive: true });
                        video.addEventListener('canplay', function() { reportVideoElement(this); checkResourceTiming(); }, { passive: true });
                    }
                }

                var domDirty = true;
                function findAndReportAllVideos() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        reportVideoElement(videos[i]);
                    }

                    // Shadow roots have to be found by walking every element, which is far too
                    // expensive to repeat on a large page for each of the seven timer ticks and
                    // every media event. Only worth doing when something was actually added.
                    if (!domDirty) return;
                    domDirty = false;
                    try {
                        var allElements = document.querySelectorAll('*');
                        for (var j = 0; j < allElements.length; j++) {
                            var el = allElements[j];
                            if (el.shadowRoot) {
                                var shadowVideos = el.shadowRoot.querySelectorAll('video');
                                for (var k = 0; k < shadowVideos.length; k++) {
                                    reportVideoElement(shadowVideos[k]);
                                }
                            }
                        }
                    } catch (e) {}
                }

                // Monkey-patch window.fetch to catch video streams (MSE, HLS.js, Dash.js)
                if (!window.__scriptoriaFetchHooked) {
                    window.__scriptoriaFetchHooked = true;
                    var originalFetch = window.fetch;
                    if (typeof originalFetch === 'function') {
                        window.fetch = function(input, init) {
                            try {
                                var url = (typeof input === 'string') ? input : (input && input.url) ? input.url : '';
                                if (url && isMediaUrl(url)) {
                                    notifyNative(url, '', document.title, 0);
                                }
                            } catch (e) {}
                            return originalFetch.apply(this, arguments);
                        };
                    }
                }

                // Monkey-patch XMLHttpRequest to catch video streams
                if (!window.__scriptoriaXhrHooked) {
                    window.__scriptoriaXhrHooked = true;
                    var origXhrOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        try {
                            if (url && isMediaUrl(url)) {
                                notifyNative(url, '', document.title, 0);
                            }
                        } catch (e) {}
                        return origXhrOpen.apply(this, arguments);
                    };
                }

                // Monkey-patch HTMLVideoElement.prototype.play
                if (!window.__scriptoriaPlayHooked) {
                    window.__scriptoriaPlayHooked = true;
                    if (typeof HTMLVideoElement !== 'undefined' && HTMLVideoElement.prototype.play) {
                        var origPlay = HTMLVideoElement.prototype.play;
                        HTMLVideoElement.prototype.play = function() {
                            reportVideoElement(this);
                            checkResourceTiming();
                            return origPlay.apply(this, arguments);
                        };
                    }
                }

                // Initial scan
                reportYouTubeFormats();
                findAndReportAllVideos();
                checkResourceTiming();

                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', function() {
                        reportYouTubeFormats();
                        findAndReportAllVideos();
                        checkResourceTiming();
                    });
                }
                window.addEventListener('load', function() {
                    reportYouTubeFormats();
                    findAndReportAllVideos();
                    checkResourceTiming();
                });

                // MutationObserver for dynamic players
                try {
                    var obs = new MutationObserver(function(mutations) {
                        var found = false;
                        domDirty = true;
                        for (var i = 0; i < mutations.length; i++) {
                            var m = mutations[i];
                            if (m.addedNodes && m.addedNodes.length > 0) {
                                for (var j = 0; j < m.addedNodes.length; j++) {
                                    var node = m.addedNodes[j];
                                    if (node.nodeType === 1) {
                                        if (node.tagName === 'VIDEO') {
                                            reportVideoElement(node);
                                            found = true;
                                        } else if (node.querySelectorAll) {
                                            var vids = node.querySelectorAll('video');
                                            if (vids.length > 0) {
                                                for (var k = 0; k < vids.length; k++) {
                                                    reportVideoElement(vids[k]);
                                                }
                                                found = true;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (found) {
                            checkResourceTiming();
                        }
                    });
                    obs.observe(document.documentElement || document.body || document, { childList: true, subtree: true });
                } catch (e) {}

                // Players often appear well after load, so the page is rescanned for a while.
                var scanTimer = null;
                function startPeriodicScan() {
                    if (scanTimer) clearInterval(scanTimer);
                    var checkCount = 0;
                    scanTimer = setInterval(function() {
                        checkCount++;
                        reportYouTubeFormats();
                        findAndReportAllVideos();
                        checkResourceTiming();
                        if (checkCount > 6) {
                            clearInterval(scanTimer);
                            scanTimer = null;
                        }
                    }, 2500);
                }

                /**
                 * A single-page app swaps the video without ever loading a document, so
                 * onPageStarted never fires and native is never told to drop the previous page's
                 * results. Left alone, the old video stays in the sheet and the new one is missed
                 * entirely — the rescan has long since stopped, and a player that reuses its
                 * existing <video> element produces no mutation to notice.
                 */
                var lastHref = window.location.href;
                function onNavigated() {
                    if (window.location.href === lastHref) return;
                    lastHref = window.location.href;

                    // Everything remembered describes the document just left.
                    reported = Object.create(null);
                    lastYouTubeVideoId = '';
                    domDirty = true;
                    try {
                        resourceCursor = window.performance.getEntriesByType('resource').length;
                    } catch (e) {
                        resourceCursor = 0;
                    }

                    if (window.$INTERFACE_NAME && window.$INTERFACE_NAME.onLocationChanged) {
                        window.$INTERFACE_NAME.onLocationChanged(window.location.href);
                    }
                    startPeriodicScan();
                }

                ['pushState', 'replaceState'].forEach(function(name) {
                    var original = history[name];
                    if (typeof original !== 'function') return;
                    history[name] = function() {
                        var result = original.apply(this, arguments);
                        // The URL is only updated once the call returns.
                        setTimeout(onNavigated, 0);
                        return result;
                    };
                });
                window.addEventListener('popstate', function() { setTimeout(onNavigated, 0); });
                window.addEventListener('hashchange', function() { setTimeout(onNavigated, 0); });

                startPeriodicScan();
            })();
        """.trimIndent()
    }

    /**
     * The page navigated without loading a document, so whatever was detected belongs to the
     * document the user has left.
     */
    @JavascriptInterface
    fun onLocationChanged(url: String?) {
        detectionManager.clearTab(tabId)
    }

    /**
     * Formats extracted from a site's own player data rather than sniffed from the network.
     *
     * @param json {videoId, title, durationSeconds, formats:[{url, qualityLabel, width, height,
     *             bitrate, contentLength, mimeType}]}
     */
    @JavascriptInterface
    fun onExtractedFormats(json: String?, pageUrl: String?) {
        if (json.isNullOrBlank()) return
        detectionManager.onExtractedFormats(
            tabId = tabId,
            pageUrl = pageUrl.orEmpty(),
            json = json
        )
    }

    /**
     * Deliberately not overloaded: WebView's bridge resolves @JavascriptInterface methods by name
     * and arity, and same-named methods are documented as unsupported. The page-side code always
     * passes all five arguments.
     */
    @JavascriptInterface
    fun onVideoFound(
        mediaUrl: String,
        mimeType: String?,
        title: String?,
        duration: Double,
        pageUrl: String?
    ) {
        if (mediaUrl.isBlank()) return
        val effectivePageUrl = pageUrl?.takeIf { it.isNotBlank() } ?: ""
        val pageTitle = title?.takeIf { it.isNotBlank() } ?: ""
        val dur = if (duration > 0 && !duration.isNaN() && !duration.isInfinite()) duration else null

        detectionManager.onDomMediaDetected(
            tabId = tabId,
            pageUrl = effectivePageUrl,
            mediaUrl = mediaUrl,
            mimeType = mimeType,
            title = pageTitle,
            durationSeconds = dur
        )
    }
}
