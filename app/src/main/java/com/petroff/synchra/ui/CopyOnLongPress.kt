package com.petroff.synchra.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
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
            Log.d(TAG, "long press on $label (${currentText.length} chars)")
            if (currentText.isBlank()) return@detectTapGestures
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(label, currentText))
            // Always confirm: stock Android 13+ has its own copy overlay, but HyperOS (the
            // target POCO) shows nothing, so the copy looked like it failed.
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        })
    }
}

private const val TAG = "Synchra/Copy"
