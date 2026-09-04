package com.scriptoria.browser.ui.browser

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.scriptoria.browser.engine.webview.ScriptoriaWebChromeClient
import com.scriptoria.browser.engine.webview.ScriptoriaWebView
import com.scriptoria.browser.ScriptoriaApp
import com.scriptoria.browser.engine.webview.ScriptoriaWebViewClient
import com.scriptoria.browser.ui.browser.components.ActiveScriptsBottomSheet
import com.scriptoria.browser.ui.browser.components.BrowserMenuBottomSheet
import com.scriptoria.browser.ui.browser.components.FloatingIdmDownloadButton
import com.scriptoria.browser.ui.browser.components.InstallScriptBottomSheet
import com.scriptoria.browser.ui.browser.components.Omnibox
import com.scriptoria.browser.ui.browser.components.TabSwitcherDialog
import com.scriptoria.browser.ui.browser.components.VideoDownloadBottomSheet

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onNavigateToUserscripts: () -> Unit,
    onNavigateToConsole: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val pendingInstallMeta by viewModel.pendingInstallMeta.collectAsState()
    val showActiveScriptsSheet by viewModel.showActiveScriptsSheet.collectAsState()
    val showVideoDownloadSheet by viewModel.showVideoDownloadSheet.collectAsState()
    val showTabSwitcher by viewModel.showTabSwitcher.collectAsState()
    val showMenu by viewModel.showMenu.collectAsState()

    val activeTab = remember(tabs, activeTabId) {
        tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()
    }

    // Handle Android system back button to navigate back in webview history
    BackHandler(enabled = activeTab?.webView?.canGoBack() == true) {
        activeTab?.webView?.goBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                Omnibox(
                    url = activeTab?.url?.takeIf { it != HOME_URL } ?: "",
                    isLoading = activeTab?.isLoading ?: false,
                    activeScriptsCount = activeTab?.activeScriptsCount ?: 0,
                    tabsCount = tabs.size,
                    detectedVideosCount = activeTab?.detectedVideos?.size ?: 0,
                    onUrlSubmit = { url -> activeTab?.let { viewModel.loadUrl(it.id, url) } },
                    onReload = { activeTab?.webView?.reload() },
                    onStop = { activeTab?.webView?.stopLoading() },
                    onOpenActiveScripts = { viewModel.openActiveScriptsSheet() },
                    onOpenVideoDownload = { viewModel.openVideoDownloadSheet() },
                    onOpenTabSwitcher = { viewModel.openTabSwitcher() },
                    onOpenMenu = { viewModel.openMenu() }
                )

                // Page Loading Progress Indicator
                AnimatedVisibility(visible = activeTab?.isLoading == true) {
                    val progressFloat = (activeTab?.progress ?: 0) / 100f
                    LinearProgressIndicator(
                        progress = { progressFloat },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        },
        bottomBar = {
            // Bottom navigation toolbar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(52.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { activeTab?.webView?.goBack() },
                        enabled = activeTab?.webView?.canGoBack() == true,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }

                    IconButton(
                        onClick = { activeTab?.webView?.goForward() },
                        enabled = activeTab?.webView?.canGoForward() == true,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }

                    IconButton(
                        onClick = { activeTab?.let { viewModel.loadUrl(it.id, HOME_URL) } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }

                    IconButton(
                        onClick = { viewModel.openTabSwitcher() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = "Tabs")
                    }

                    IconButton(
                        onClick = { viewModel.openMenu() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Active Tab's WebView Container
            activeTab?.let { tab ->
                // Keyed by tab id. Without a key Compose reuses one AndroidView node across
                // tab switches, so factory runs only for the first tab and every other tab
                // shows that tab's page.
                key(tab.id) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val existing = tab.webView
                        if (existing != null) {
                            // Reused so switching away and back keeps the page and history.
                            // It must be detached first: a View cannot join a second parent
                            // while the previous one still holds it.
                            (existing.parent as? ViewGroup)?.removeView(existing)
                            existing
                        } else ScriptoriaWebView(
                            context = ctx,
                            tabId = tab.id,
                            onOpenNewTab = { url, makeActive ->
                                viewModel.createTab(url, makeActive)
                            }
                        ).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            webViewClient = ScriptoriaWebViewClient(
                                userscriptManager = viewModel.userscriptManager,
                                adblockManager = this@apply.adblockManager,
                                onUrlChange = { newUrl ->
                                    viewModel.updateTabUrl(tab.id, newUrl)
                                    viewModel.updateTabNavigation(tab.id, canGoBack(), canGoForward())
                                },
                                onUserscriptUrlDetected = { scriptUrl ->
                                    viewModel.handleUserscriptUrlDetected(scriptUrl)
                                },
                                onScriptsActiveCountChanged = { count, scripts ->
                                    viewModel.updateActiveScripts(tab.id, count, scripts)
                                }
                            )

                            webChromeClient = ScriptoriaWebChromeClient(
                                onProgressChanged = { p ->
                                    viewModel.updateTabProgress(tab.id, p)
                                },
                                onTitleReceived = { t ->
                                    viewModel.updateTabTitle(tab.id, t)
                                }
                            )

                            loadUrl(tab.url)
                        }.also { tab.webView = it }
                    },
                    onRelease = { view ->
                        // Detach on dispose so the next tab can adopt this view cleanly.
                        (view.parent as? ViewGroup)?.removeView(view)
                    }
                )
                }
            }

            // Floating IDM download button when video detected on page
            val detectedVideos = activeTab?.detectedVideos ?: emptyList()
            FloatingIdmDownloadButton(
                videosCount = detectedVideos.size,
                onClick = { viewModel.openVideoDownloadSheet() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            )
        }
    }

    // Modal Bottom Sheets & Dialogs
    if (showVideoDownloadSheet) {
        VideoDownloadBottomSheet(
            videos = activeTab?.detectedVideos ?: emptyList(),
            onDownload = { video, quality ->
                viewModel.downloadVideo(video, quality)
            },
            onScanAgain = { viewModel.scanForVideos() },
            onDismiss = { viewModel.dismissVideoDownloadSheet() }
        )
    }

    if (showActiveScriptsSheet) {
        ActiveScriptsBottomSheet(
            scripts = activeTab?.activeScripts ?: emptyList(),
            onToggleScript = { id, enabled -> viewModel.toggleScriptFromSheet(id, enabled) },
            onNavigateToManager = onNavigateToUserscripts,
            onDismiss = { viewModel.dismissActiveScriptsSheet() }
        )
    }

    pendingInstallMeta?.let { meta ->
        InstallScriptBottomSheet(
            metadata = meta,
            onConfirm = { viewModel.confirmInstall() },
            onDismiss = { viewModel.dismissInstall() }
        )
    }

    if (showTabSwitcher) {
        TabSwitcherDialog(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelectTab = { id -> viewModel.selectTab(id) },
            onCloseTab = { id -> viewModel.closeTab(id) },
            onNewTab = { viewModel.createTab(makeActive = true) },
            onDismiss = { viewModel.dismissTabSwitcher() }
        )
    }

    if (showMenu) {
        val adblockManager = (context.applicationContext as ScriptoriaApp).adblockManager
        val currentHost = remember(activeTab?.url) {
            activeTab?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
        }

        BrowserMenuBottomSheet(
            adblockHost = currentHost,
            isAdblockOnForSite = currentHost != null &&
                adblockManager.preferences.isEnabled &&
                !adblockManager.preferences.isAllowlisted(currentHost),
            onToggleAdblockForSite = { blockingOn ->
                currentHost?.let {
                    adblockManager.preferences.setAllowlisted(it, allowed = !blockingOn)
                    // Rules are applied as the document loads, so the change only shows on a reload.
                    activeTab?.webView?.reload()
                }
                viewModel.dismissMenu()
            },
            onNewTab = { viewModel.createTab(makeActive = true) },
            onOpenDownloads = onNavigateToDownloads,
            onOpenVideoDownload = { viewModel.openVideoDownloadSheet() },
            detectedVideosCount = activeTab?.detectedVideos?.size ?: 0,
            onOpenUserscripts = onNavigateToUserscripts,
            onOpenConsole = onNavigateToConsole,
            onToggleDesktop = {
                activeTab?.webView?.settings?.let { s ->
                    val isDesktop = s.userAgentString.contains("X11; Linux x86_64")
                    if (isDesktop) {
                        s.userAgentString = ""
                    } else {
                        s.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
                    }
                    activeTab.webView?.reload()
                }
            },
            onClearData = {
                activeTab?.webView?.clearCache(true)
                activeTab?.webView?.clearFormData()
                activeTab?.webView?.clearHistory()
            },
            onOpenSettings = onNavigateToSettings,
            onDismiss = { viewModel.dismissMenu() }
        )
    }
}
