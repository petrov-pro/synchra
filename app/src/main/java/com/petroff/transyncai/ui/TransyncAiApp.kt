package com.petroff.transyncai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.petroff.transyncai.AppContainer
import com.petroff.transyncai.config.Engine
import com.petroff.transyncai.session.SessionViewModel
import com.petroff.transyncai.session.SessionViewModelFactory

@Composable
fun TransyncAiApp(container: AppContainer) {
    MaterialTheme {
        Surface {
            RequiresRecordAudioPermission {
                val navController = rememberNavController()
                val sessionViewModel: SessionViewModel = viewModel(
                    factory = SessionViewModelFactory(container)
                )
                val uiState by sessionViewModel.uiState.collectAsState()

                // Re-read config whenever the visible screen changes, so edits made in
                // Settings (API key, buffer size) are picked up as soon as the user
                // navigates back to Main, without needing ConfigRepository to be reactive.
                // Also nudge the ViewModel's own canStart, which ConfigRepository changes
                // don't otherwise propagate into (canStart only recomputes on init/setViewMode).
                val backStackEntry by navController.currentBackStackEntryAsState()
                var config by remember { mutableStateOf(container.configRepository.getConfig()) }
                LaunchedEffect(backStackEntry) {
                    config = container.configRepository.getConfig()
                    sessionViewModel.refreshCanStart()
                }

                // Both navigation paths into Settings set this explicitly (true from a blocked
                // view-mode tab, false from the gear icon) so it always reflects how the most
                // recent navigation got here, rather than needing an explicit reset somewhere.
                var focusApiKeyOnSettingsOpen by remember { mutableStateOf(false) }

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            uiState = uiState,
                            currentLanguagePair = config.selectedLanguagePair,
                            currentConversationContext = config.conversationContext,
                            onToggleListening = {
                                if (uiState.isListening) sessionViewModel.stop() else sessionViewModel.start()
                            },
                            onViewModeChange = sessionViewModel::setViewMode,
                            onOpenSettings = {
                                focusApiKeyOnSettingsOpen = false
                                navController.navigate("settings")
                            },
                            onSaveConversationContext = {
                                container.configRepository.setConversationContext(it)
                                // No navigation happens here (unlike Settings), so refresh the
                                // locally held config directly — otherwise re-opening the
                                // Context dialog in the same session would show stale text.
                                config = container.configRepository.getConfig()
                            },
                            hasApiKey = if (config.engine == Engine.SONIOX) {
                                !config.sonioxApiKey.isNullOrBlank()
                            } else {
                                !config.apiKey.isNullOrBlank()
                            },
                            onApiKeyRequired = {
                                focusApiKeyOnSettingsOpen = true
                                navController.navigate("settings")
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            currentApiKey = config.apiKey,
                            currentBufferSize = config.bufferSize,
                            currentChunkSizeWords = config.chunkSizeWords,
                            currentGeneralContext = config.generalContext,
                            languagePairs = config.languagePairs,
                            selectedLanguagePairIndex = config.selectedLanguagePairIndex,
                            onSelectLanguagePair = { container.configRepository.setSelectedLanguagePairIndex(it) },
                            onSaveApiKey = { container.configRepository.setApiKey(it) },
                            onSaveBufferSize = { container.configRepository.setBufferSize(it) },
                            onSaveChunkSizeWords = { container.configRepository.setChunkSizeWords(it) },
                            onSaveGeneralContext = { container.configRepository.setGeneralContext(it) },
                            onBack = { navController.popBackStack() },
                            autoFocusApiKey = focusApiKeyOnSettingsOpen,
                            currentEngine = config.engine,
                            currentSonioxApiKey = config.sonioxApiKey,
                            onSaveEngine = { container.configRepository.setEngine(it) },
                            onSaveSonioxApiKey = { container.configRepository.setSonioxApiKey(it) },
                        )
                    }
                }
            }
        }
    }
}
