package com.petroff.transyncai.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.petroff.transyncai.AppContainer

class SessionViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return SessionViewModel(
            speechRecognizer = container.buildSpeechRecognizer(),
            translator = container.translator,
            configRepository = container.configRepository,
            historyRepository = container.historyRepository,
        ) as T
    }
}
