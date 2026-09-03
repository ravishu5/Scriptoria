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

    init {
        createTab("https://duckduckgo.com", makeActive = true)
    }

    fun getActiveTab(): TabModel? {
        val currentId = _activeTabId.value
        return _tabs.value.firstOrNull { it.id == currentId }
    }

    fun createTab(url: String = "https://duckduckgo.com", makeActive: Boolean = true): String {
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

        val updated = currentList.filter { it.id != tabId }
        if (updated.isEmpty()) {
            // Keep at least one tab
            createTab("https://duckduckgo.com", makeActive = true)
        } else {
            _tabs.value = updated
            if (_activeTabId.value == tabId) {
                _activeTabId.value = updated.last().id
            }
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
            else -> "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
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
}
