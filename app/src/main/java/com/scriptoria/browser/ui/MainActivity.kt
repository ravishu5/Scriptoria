package com.scriptoria.browser.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.scriptoria.browser.ScriptoriaApp
import com.scriptoria.browser.ui.browser.BrowserScreen
import com.scriptoria.browser.ui.browser.BrowserViewModel
import com.scriptoria.browser.ui.console.ScriptConsoleScreen
import com.scriptoria.browser.ui.downloads.DownloadsScreen
import com.scriptoria.browser.ui.editor.ScriptEditorScreen
import com.scriptoria.browser.ui.manager.ScriptDetailScreen
import com.scriptoria.browser.ui.manager.ScriptListScreen
import com.scriptoria.browser.data.config.AppConfig
import com.scriptoria.browser.data.config.UpdateStatus
import com.scriptoria.browser.ui.settings.SettingsScreen
import com.scriptoria.browser.ui.update.UpdateGate

sealed class AppScreen {
    object Browser : AppScreen()
    object UserscriptList : AppScreen()
    data class UserscriptDetail(val scriptId: Long) : AppScreen()
    data class UserscriptEditor(val scriptId: Long) : AppScreen()
    object UserscriptConsole : AppScreen()
    object Downloads : AppScreen()
    object Settings : AppScreen()
}

class MainActivity : ComponentActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestDownloadPermissions()

        // Handle VIEW intent if user opened a link from another app
        intent?.data?.toString()?.let { incomingUrl ->
            if (incomingUrl.isNotBlank()) {
                browserViewModel.createTab(incomingUrl, makeActive = true)
            }
        }

        setContent {
            val userscriptManager = (application as ScriptoriaApp).userscriptManager
            var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Browser) }

            // Version gate. Checked once per launch and off the critical path: the browser is
            // usable while this resolves, and stays usable if it never does.
            val configRepository = (application as ScriptoriaApp).appConfigRepository
            var appConfig by remember { mutableStateOf<AppConfig?>(null) }
            var updateStatus by remember { mutableStateOf(UpdateStatus.NONE) }
            var updateDismissed by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val config = configRepository.refresh()
                appConfig = config
                updateStatus = configRepository.statusFor(config)
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6366F1),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFF312E81),
                    onPrimaryContainer = Color(0xFFE0E7FF),
                    secondary = Color(0xFF38BDF8),
                    surface = Color(0xFF0F172A),
                    onSurface = Color(0xFFF8FAFC),
                    surfaceVariant = Color(0xFF1E293B),
                    onSurfaceVariant = Color(0xFFCBD5E1),
                    background = Color(0xFF020617),
                    onBackground = Color(0xFFF8FAFC)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (val screen = currentScreen) {
                        is AppScreen.Browser -> {
                            BrowserScreen(
                                viewModel = browserViewModel,
                                onNavigateToUserscripts = { currentScreen = AppScreen.UserscriptList },
                                onNavigateToConsole = { currentScreen = AppScreen.UserscriptConsole },
                                onNavigateToDownloads = { currentScreen = AppScreen.Downloads },
                                onNavigateToSettings = { currentScreen = AppScreen.Settings }
                            )
                        }

                        is AppScreen.UserscriptList -> {
                            ScriptListScreen(
                                userscriptManager = userscriptManager,
                                onNavigateBack = { currentScreen = AppScreen.Browser },
                                onSelectScript = { id -> currentScreen = AppScreen.UserscriptDetail(id) },
                                onEditScript = { id -> currentScreen = AppScreen.UserscriptEditor(id) }
                            )
                        }

                        is AppScreen.UserscriptDetail -> {
                            ScriptDetailScreen(
                                scriptId = screen.scriptId,
                                userscriptManager = userscriptManager,
                                onNavigateBack = { currentScreen = AppScreen.UserscriptList },
                                onEditScript = { id -> currentScreen = AppScreen.UserscriptEditor(id) }
                            )
                        }

                        is AppScreen.UserscriptEditor -> {
                            ScriptEditorScreen(
                                scriptId = screen.scriptId,
                                userscriptManager = userscriptManager,
                                onNavigateBack = { currentScreen = AppScreen.UserscriptDetail(screen.scriptId) }
                            )
                        }

                        is AppScreen.UserscriptConsole -> {
                            ScriptConsoleScreen(
                                onNavigateBack = { currentScreen = AppScreen.Browser }
                            )
                        }

                        is AppScreen.Downloads -> {
                            val app = application as ScriptoriaApp
                            DownloadsScreen(
                                downloadPreferences = app.downloadPreferences,
                                downloadRepository = app.downloadManagerRepository,
                                onNavigateBack = { currentScreen = AppScreen.Browser }
                            )
                        }

                        is AppScreen.Settings -> {
                            SettingsScreen(
                                userscriptManager = userscriptManager,
                                downloadPreferences = (application as ScriptoriaApp).downloadPreferences,
                                adblockManager = (application as ScriptoriaApp).adblockManager,
                                onNavigateBack = { currentScreen = AppScreen.Browser }
                            )
                        }
                    }

                    UpdateGate(
                        status = if (updateDismissed && updateStatus == UpdateStatus.OPTIONAL) {
                            UpdateStatus.NONE
                        } else {
                            updateStatus
                        },
                        config = appConfig,
                        onDismiss = { updateDismissed = true }
                    )
                }
            }
        }
    }

    /**
     * Downloads report progress through notifications, and on pre-scoped-storage devices they
     * write to public storage. Both are runtime permissions that were declared but never asked
     * for, so downloads ran silently (or failed) on modern and old devices respectively.
     */
    private fun requestDownloadPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermissions.launch(needed.toTypedArray())
        }
    }
}
