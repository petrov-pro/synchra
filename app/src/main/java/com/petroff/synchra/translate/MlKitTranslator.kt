package com.petroff.synchra.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Languages ML Kit translates on the phone, as ISO codes. */
fun mlKitTargetCodes(): Set<String> = TranslateLanguage.getAllLanguages().toSet()

/** ML Kit on-device translation: free, offline once each language's model (~30 MB) is on the phone. */
class MlKitTranslator : Translator {

    @Volatile private var client: com.google.mlkit.nl.translate.Translator? = null

    override suspend fun prepare(source: String, target: String): Result<Unit> = runCatching {
        val from = requireNotNull(TranslateLanguage.fromLanguageTag(source)) { "On-device translation doesn't support $source" }
        val to = requireNotNull(TranslateLanguage.fromLanguageTag(target)) { "On-device translation doesn't support $target" }
        close()
        val newClient = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        client = newClient
        // Any network: the user pressed Start and the models are small.
        newClient.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    override suspend fun translate(text: String): Result<String> = runCatching {
        val current = checkNotNull(client) { "prepare() first" }
        current.translate(text).await()
    }

    override fun close() {
        client?.close()
        client = null
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
