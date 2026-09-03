package com.scriptoria.browser.ui.browser

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.scriptoria.browser.engine.webview.ScriptoriaWebChromeClient
import com.scriptoria.browser.engine.webview.ScriptoriaWebView
import com.scriptoria.browser.engine.webview.ScriptoriaWebViewClient
import com.scriptoria.browser.ui.browser.components.ActiveScriptsBottomSheet
import com.scriptoria.browser.ui.browser.components.BrowserMenuBottomSheet
import com.scriptoria.browser.ui.browser.components.InstallScriptBottomSheet
import com.scriptoria.browser.ui.browser.components.Omnibox
import com.scriptoria.browser.ui.browser.components.TabSwitcherDialog

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
                    url = activeTab?.url ?: "",
                    isLoading = activeTab?.isLoading ?: false,
                    activeScriptsCount = activeTab?.activeScriptsCount ?: 0,
                    tabsCount = tabs.size,
                    onUrlSubmit = { url -> activeTab?.let { viewModel.loadUrl(it.id, url) } },
                    onReload = { activeTab?.webView?.reload() },
                    onStop = { activeTab?.webView?.stopLoading() },
                    onOpenActiveScripts = { viewModel.openActiveScriptsSheet() },
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
                        onClick = { activeTab?.let { viewModel.loadUrl(it.id, "https://duckduckgo.com") } },
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
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val webView = tab.webView ?: ScriptoriaWebView(
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
                        }

                        tab.webView = webView
                        webView
                    },
                    update = { view ->
                        // View is kept up to date
                    }
                )
            }
        }
    }

    // Modal Bottom Sheets & Dialogs
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
        BrowserMenuBottomSheet(
            onNewTab = { viewModel.createTab(makeActive = true) },
            onOpenDownloads = onNavigateToDownloads,
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
