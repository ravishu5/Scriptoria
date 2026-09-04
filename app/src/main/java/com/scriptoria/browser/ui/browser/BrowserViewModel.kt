package com.scriptoria.browser.ui.browser

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scriptoria.browser.ScriptoriaApp
import com.scriptoria.browser.data.repository.InstalledScript
import com.scriptoria.browser.engine.executor.UserscriptManager
import com.scriptoria.browser.engine.parser.UserscriptMetadata
import com.scriptoria.browser.engine.parser.UserscriptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

import kotlinx.coroutines.flow.update
import com.scriptoria.browser.engine.media.DetectedVideo
import com.scriptoria.browser.engine.media.VideoFormat
import com.scriptoria.browser.engine.media.VideoQualityOption
import com.scriptoria.browser.engine.network.DownloadService

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ScriptoriaApp
    val userscriptManager: UserscriptManager = app.userscriptManager

    private val _tabs = MutableStateFlow<List<TabModel>>(emptyList())
    val tabs: StateFlow<List<TabModel>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>("")
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    // Installation Prompt State
    private val _pendingInstallMeta = MutableStateFlow<UserscriptMetadata?>(null)
    val pendingInstallMeta: StateFlow<UserscriptMetadata?> = _pendingInstallMeta.asStateFlow()
    private var pendingInstallCode: String? = null
    private var pendingInstallUrl: String? = null

    // Active scripts sheet
    private val _showActiveScriptsSheet = MutableStateFlow(false)
    val showActiveScriptsSheet: StateFlow<Boolean> = _showActiveScriptsSheet.asStateFlow()

    // Tab switcher sheet
    private val _showTabSwitcher = MutableStateFlow(false)
    val showTabSwitcher: StateFlow<Boolean> = _showTabSwitcher.asStateFlow()

    // Browser Menu sheet
    private val _showMenu = MutableStateFlow(false)
    val showMenu: StateFlow<Boolean> = _showMenu.asStateFlow()

    // Video Download sheet (IDM style)
    private val _showVideoDownloadSheet = MutableStateFlow(false)
    val showVideoDownloadSheet: StateFlow<Boolean> = _showVideoDownloadSheet.asStateFlow()

    init {
        createTab(HOME_URL, makeActive = true)
        viewModelScope.launch {
            app.videoDetectionManager.detectedVideosByTab.collect { map ->
                _tabs.update { tabsList ->
                    tabsList.map { tab ->
                        val videos = map[tab.id] ?: emptyList()
                        if (tab.detectedVideos != videos) tab.copy(detectedVideos = videos) else tab
                    }
                }
            }
        }
    }

    fun getActiveTab(): TabModel? {
        val currentId = _activeTabId.value
        return _tabs.value.firstOrNull { it.id == currentId }
    }

    fun createTab(url: String = HOME_URL, makeActive: Boolean = true): String {
        val newTab = TabModel(url = url)
        _tabs.value = _tabs.value + newTab
        if (makeActive || _activeTabId.value.isEmpty()) {
            _activeTabId.value = newTab.id
        }
        return newTab.id
    }

    fun closeTab(tabId: String) {
        val currentList = _tabs.value
        val tabToClose = currentList.firstOrNull { it.id == tabId }
        tabToClose?.webView?.destroy()

        // Commit the removal first. Closing the last tab used to skip this and append the
        // replacement to the old list, leaving the destroyed WebView in the switcher.
        _tabs.value = currentList.filter { it.id != tabId }

        if (_tabs.value.isEmpty()) {
            _activeTabId.value = ""
            createTab(HOME_URL, makeActive = true)
        } else if (_activeTabId.value == tabId) {
            // Prefer the neighbour to the left, which is what closing a tab usually means.
            val closedIndex = currentList.indexOfFirst { it.id == tabId }
            val next = _tabs.value.getOrNull(closedIndex - 1) ?: _tabs.value.first()
            _activeTabId.value = next.id
        }
    }

    fun selectTab(tabId: String) {
        _activeTabId.value = tabId
        _showTabSwitcher.value = false
    }

    fun updateTabUrl(tabId: String, url: String) {
        updateTab(tabId) { it.copy(url = url) }
    }

    fun updateTabTitle(tabId: String, title: String) {
        updateTab(tabId) { it.copy(title = title) }
    }

    fun updateTabProgress(tabId: String, progress: Int) {
        updateTab(tabId) { it.copy(progress = progress, isLoading = progress in 1..99) }
    }

    fun updateTabNavigation(tabId: String, canGoBack: Boolean, canGoForward: Boolean) {
        updateTab(tabId) { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun updateActiveScripts(tabId: String, count: Int, scripts: List<InstalledScript>) {
        updateTab(tabId) { it.copy(activeScriptsCount = count, activeScripts = scripts) }
    }

    private fun updateTab(tabId: String, transform: (TabModel) -> TabModel) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) transform(it) else it
        }
    }

    fun loadUrl(tabId: String, input: String) {
        val trimmed = input.trim()
        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://") -> trimmed
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
        }

        val tab = _tabs.value.firstOrNull { it.id == tabId }
        tab?.webView?.loadUrl(url)
    }

    fun handleUserscriptUrlDetected(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                val resp = app.httpClient.newCall(req).execute()
                val code = resp.body?.string().orEmpty()
                resp.close()

                if (code.isNotBlank()) {
                    val meta = UserscriptParser.parse(code, url)
                    pendingInstallCode = code
                    pendingInstallUrl = url
                    _pendingInstallMeta.value = meta
                }
            } catch (e: Exception) {
                Log.e("BrowserViewModel", "Failed to fetch userscript from $url", e)
            }
        }
    }

    fun confirmInstall() {
        val code = pendingInstallCode ?: return
        val url = pendingInstallUrl
        viewModelScope.launch {
            userscriptManager.installScript(code, url)
            dismissInstall()
            // Reload active tab to let new script execute if matching
            getActiveTab()?.webView?.reload()
        }
    }

    fun dismissInstall() {
        _pendingInstallMeta.value = null
        pendingInstallCode = null
        pendingInstallUrl = null
    }

    fun openActiveScriptsSheet() {
        _showActiveScriptsSheet.value = true
    }

    fun dismissActiveScriptsSheet() {
        _showActiveScriptsSheet.value = false
    }

    fun openTabSwitcher() {
        _showTabSwitcher.value = true
    }

    fun dismissTabSwitcher() {
        _showTabSwitcher.value = false
    }

    fun openMenu() {
        _showMenu.value = true
    }

    fun dismissMenu() {
        _showMenu.value = false
    }

    fun toggleScriptFromSheet(scriptId: Long, enabled: Boolean) {
        viewModelScope.launch {
            userscriptManager.toggleScript(scriptId, enabled)
            getActiveTab()?.webView?.reload()
        }
    }

    fun openVideoDownloadSheet() {
        _showVideoDownloadSheet.value = true
        scanForVideos()
    }

    fun dismissVideoDownloadSheet() {
        _showVideoDownloadSheet.value = false
    }

    fun scanForVideos() {
        getActiveTab()?.webView?.scanForVideos()
    }

    fun downloadVideo(video: DetectedVideo, quality: VideoQualityOption) {
        val rawTitle = video.title.trim().ifBlank { "video" }
        val sanitizedTitle = rawTitle.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val ext = when (quality.format) {
            // Provisional only: HlsDownloader renames to .ts or .mp4 once it has seen whether
            // the segments are MPEG-TS or fragmented MP4.
            VideoFormat.HLS -> "mp4"
            VideoFormat.WEBM -> "webm"
            VideoFormat.OTHER -> "mp4"
            VideoFormat.MP4 -> "mp4"
            VideoFormat.DASH -> "mp4"
        }
        val qualitySuffix = if (quality.quality.isNotBlank() && !quality.quality.contains("Source", ignoreCase = true)) {
            "_" + quality.quality.replace(Regex("""\s+"""), "_").replace("/", "_")
        } else ""
        val fileName = "${sanitizedTitle}${qualitySuffix}.${ext}"
        val mimeType = when (quality.format) {
            VideoFormat.HLS -> "application/vnd.apple.mpegurl"
            VideoFormat.WEBM -> "video/webm"
            else -> "video/mp4"
        }
        val userAgent = getActiveTab()?.webView?.settings?.userAgentString

        DownloadService.enqueue(
            context = app.applicationContext,
            url = quality.streamUrl,
            fileName = fileName,
            mimeType = mimeType,
            userAgent = userAgent,
            referer = video.pageUrl,
            audioUrl = quality.audioStreamUrl
        )
        dismissVideoDownloadSheet()
    }
}
