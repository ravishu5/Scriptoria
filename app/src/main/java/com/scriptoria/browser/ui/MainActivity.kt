package com.scriptoria.browser.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import com.scriptoria.browser.ui.settings.SettingsScreen

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Handle VIEW intent if user opened a link from another app
        intent?.data?.toString()?.let { incomingUrl ->
            if (incomingUrl.isNotBlank()) {
                browserViewModel.createTab(incomingUrl, makeActive = true)
            }
        }

        setContent {
            val userscriptManager = (application as ScriptoriaApp).userscriptManager
            var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Browser) }

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
                                onNavigateBack = { currentScreen = AppScreen.Browser }
                            )
                        }
                    }
                }
            }
        }
    }
}
