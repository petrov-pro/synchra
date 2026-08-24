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
                val backStackEntry by navController.currentBackStackEntryAsState()
                var config by remember { mutableStateOf(container.configRepository.getConfig()) }
                LaunchedEffect(backStackEntry) {
                    config = container.configRepository.getConfig()
                }

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            uiState = uiState,
                            languagePairs = config.languagePairs,
                            selectedIndex = config.selectedLanguagePairIndex,
                            onSelectLanguagePair = { container.configRepository.setSelectedLanguagePairIndex(it) },
                            onToggleListening = {
                                if (uiState.isListening) sessionViewModel.stop() else sessionViewModel.start()
                            },
                            onViewModeChange = sessionViewModel::setViewMode,
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            currentApiKey = config.apiKey,
                            currentBufferSize = config.bufferSize,
                            onSaveApiKey = { container.configRepository.setApiKey(it) },
                            onSaveBufferSize = { container.configRepository.setBufferSize(it) },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
