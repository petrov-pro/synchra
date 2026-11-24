package com.petroff.synchra

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Conversations stay on this phone: no Google backup, no copy to a new phone. A restored
 * copy of the encrypted settings would also crash the app (its Keystore key doesn't move).
 */
@RunWith(AndroidJUnit4::class)
class BackupPolicyTest {

    @Test
    fun appDataIsNeverBackedUp() {
        val info = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo

        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
