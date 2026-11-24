package com.petroff.transyncai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/** Long press copies [text] to the clipboard; blank text copies nothing. */
@Composable
fun Modifier.copyOnLongPress(label: String, text: String): Modifier {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val currentText by rememberUpdatedState(text)
    return pointerInput(label) {
        detectTapGestures(onLongPress = {
            if (currentText.isBlank()) return@detectTapGestures
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(label, currentText))
            // Android 13+ shows its own "Copied" confirmation; older versions show nothing.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
