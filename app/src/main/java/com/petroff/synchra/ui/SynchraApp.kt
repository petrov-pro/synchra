package com.petroff.synchra.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.petroff.synchra.history.ConversationViewModel
import com.petroff.synchra.history.HistoryViewModel
import com.petroff.synchra.AppContainer
import com.petroff.synchra.session.SessionViewModel
import com.petroff.synchra.session.SessionViewModelFactory

@Composable
fun SynchraApp(container: AppContainer) {
    MaterialTheme {
        Surface {
            RequiresRecordAudioPermission {
                val navController = rememberNavController()
                val context = LocalContext.current
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                val sessionViewModel: SessionViewModel = viewModel(
                    factory = SessionViewModelFactory(container)
                )
                val uiState by sessionViewModel.uiState.collectAsState()

                // Re-read config whenever the visible screen changes, so edits made in
                // Settings (API keys, context) are picked up as soon as the user
                // navigates back to Main, without needing ConfigRepository to be reactive.
                // Also nudge the ViewModel's own canStart, which ConfigRepository changes
                // don't otherwise propagate into (canStart only recomputes on init/setViewMode).
                val backStackEntry by navController.currentBackStackEntryAsState()
                var config by remember { mutableStateOf(container.configRepository.getConfig()) }
                val historyViewModel: HistoryViewModel = viewModel(
                    factory = viewModelFactory { initializer { HistoryViewModel(container.historyRepository) } }
                )
                LaunchedEffect(backStackEntry) {
                    config = container.configRepository.getConfig()
                    sessionViewModel.refreshCanStart()
                    // History may have changed (summaries added, records deleted).
                    historyViewModel.refresh()
                    sessionViewModel.refreshLastRecord()
                }

                // Both navigation paths into Settings set this explicitly (true from a blocked
                // view-mode tab, false from the gear icon) so it always reflects how the most
                // recent navigation got here, rather than needing an explicit reset somewhere.
                var focusApiKeyOnSettingsOpen by remember { mutableStateOf(false) }

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            uiState = uiState,
                            sourceLanguage = config.sourceLanguage,
                            targetLanguage = config.targetLanguage,
                            currentConversationContext = config.conversationContext,
                            onToggleListening = {
                                if (uiState.isListening) {
                                    sessionViewModel.stop()
                                } else {
                                    // The recording notification needs this on Android 13+; recording
                                    // works without it, the notification is just hidden.
                                    if (needsNotificationPermission(context)) {
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    sessionViewModel.start()
                                }
                            },
                            onViewModeChange = sessionViewModel::setViewMode,
                            onOpenSettings = {
                                focusApiKeyOnSettingsOpen = false
                                navController.navigateOnce("settings")
                            },
                            onSaveConversationContext = {
                                container.configRepository.setConversationContext(it)
                                // No navigation happens here (unlike Settings), so refresh the
                                // locally held config directly — otherwise re-opening the
                                // Context dialog in the same session would show stale text.
                                config = container.configRepository.getConfig()
                            },
                            onOpenHistory = { navController.navigateOnce("history") },
                            onSummary = {
                                uiState.lastRecordId?.let { navController.navigateOnce("conversation/$it?summarize=true") }
                            },
                            hasApiKey = !config.sonioxApiKey.isNullOrBlank(),
                            onApiKeyRequired = {
                                focusApiKeyOnSettingsOpen = true
                                navController.navigateOnce("settings")
                            },
                        )
                    }
                    composable("history") {
                        val records by historyViewModel.records.collectAsState()
                        val query by historyViewModel.searchQuery.collectAsState()
                        val newestFirst by historyViewModel.newestFirst.collectAsState()
                        HistoryScreen(
                            records = records,
                            searchQuery = query,
                            onSearchQueryChange = historyViewModel::setSearchQuery,
                            onRecordClick = { navController.navigateOnce("conversation/$it") },
                            onDeleteRecord = historyViewModel::delete,
                            newestFirst = newestFirst,
                            onToggleOrder = historyViewModel::toggleOrder,
                            onBack = { navController.popOnce() },
                        )
                    }
                    composable(
                        route = "conversation/{id}?summarize={summarize}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("summarize") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ),
                    ) { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val summarize = entry.arguments?.getBoolean("summarize") ?: false
                        val conversationViewModel: ConversationViewModel = viewModel(
                            key = "conversation-$id",
                            factory = viewModelFactory {
                                initializer {
                                    ConversationViewModel(
                                        id,
                                        container.historyRepository,
                                        container.summaryJobs,
                                        container.configRepository,
                                        summarize,
                                    )
                                }
                            },
                        )
                        val state by conversationViewModel.uiState.collectAsState()
                        ConversationScreen(
                            state = state,
                            onBack = { navController.popOnce() },
                            onSummaryClick = conversationViewModel::onSummaryClick,
                            onOriginalClick = conversationViewModel::onOriginalClick,
                            onViewModeChange = conversationViewModel::setViewMode,
                            onDismissError = {
                                val close = state.closeOnErrorDismiss
                                conversationViewModel.dismissError()
                                // Summary from the main screen failed: go back where it was pressed.
                                if (close) navController.popOnce()
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            currentSonioxApiKey = config.sonioxApiKey,
                            currentApiKey = config.apiKey,
                            currentGeneralContext = config.generalContext,
                            onSaveSonioxApiKey = { container.configRepository.setSonioxApiKey(it) },
                            onSaveApiKey = { container.configRepository.setApiKey(it) },
                            onSaveGeneralContext = { container.configRepository.setGeneralContext(it) },
                            onBack = { navController.popOnce() },
                            autoFocusApiKey = focusApiKeyOnSettingsOpen,
                            sourceLanguage = config.sourceLanguage,
                            targetLanguage = config.targetLanguage,
                            recentLanguages = config.recentLanguages,
                            onSourceLanguageChange = {
                                container.configRepository.setSourceLanguage(it.code)
                                config = container.configRepository.getConfig()
                            },
                            onTargetLanguageChange = {
                                container.configRepository.setTargetLanguage(it.code)
                                config = container.configRepository.getConfig()
                            },
                            onSwapLanguages = {
                                container.configRepository.swapLanguages()
                                config = container.configRepository.getConfig()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

