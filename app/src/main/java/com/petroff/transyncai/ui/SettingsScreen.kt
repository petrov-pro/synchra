package com.petroff.transyncai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentApiKey: String?,
    currentBufferSize: Int,
    onSaveApiKey: (String) -> Unit,
    onSaveBufferSize: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var apiKeyField by remember { mutableStateOf(currentApiKey ?: "") }
    var bufferSizeField by remember { mutableStateOf(currentBufferSize.toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextField(
                value = apiKeyField,
                onValueChange = { apiKeyField = it },
                label = { Text("Kimi API key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Context buffer size: ${bufferSizeField.toInt()} phrases")
            Slider(
                value = bufferSizeField,
                onValueChange = { bufferSizeField = it },
                valueRange = 2f..20f,
                steps = 17,
            )

            Button(onClick = {
                onSaveApiKey(apiKeyField)
                onSaveBufferSize(bufferSizeField.toInt())
                onBack()
            }) {
                Text("Save")
            }
        }
    }
}
