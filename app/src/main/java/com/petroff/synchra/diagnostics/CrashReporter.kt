package com.petroff.synchra.diagnostics

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Anonymous crash reports. Only what the app did ("session started"), never what was said:
 * no transcripts, translations, contexts, summaries or API keys may be passed here.
 */
interface CrashReporter {
    /** Sending starts only after the user agreed; off until then (see the manifest). */
    fun setEnabled(enabled: Boolean)

    /** A step shown with the next crash report, to see what led up to it. */
    fun log(event: String)
}

object NoOpCrashReporter : CrashReporter {
    override fun setEnabled(enabled: Boolean) = Unit
    override fun log(event: String) = Unit
}

class FirebaseCrashReporter(private val crashlytics: FirebaseCrashlytics) : CrashReporter {
    override fun setEnabled(enabled: Boolean) = crashlytics.setCrashlyticsCollectionEnabled(enabled)
    override fun log(event: String) = crashlytics.log(event)

    companion object {
        /**
         * Firebase only starts when the build had a google-services.json (see app/build.gradle.kts);
         * without it there is nothing to report to.
         */
        fun createOrNoOp(context: Context): CrashReporter =
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseCrashReporter(FirebaseCrashlytics.getInstance())
            } else {
                NoOpCrashReporter
            }
    }
}
