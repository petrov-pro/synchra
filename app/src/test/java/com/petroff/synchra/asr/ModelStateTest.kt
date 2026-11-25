package com.petroff.synchra.asr

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStateTest {

    @Test
    fun `ready model with no work is Ready`() {
        assertEquals(ModelState.Ready, modelStateOf(ready = true, work = null, percent = 0))
        assertEquals(ModelState.Ready, modelStateOf(ready = true, work = WorkInfo.State.SUCCEEDED, percent = 100))
    }

    @Test
    fun `running or queued work shows its percent`() {
        assertEquals(ModelState.Downloading(37), modelStateOf(ready = false, work = WorkInfo.State.RUNNING, percent = 37))
        assertEquals(ModelState.Downloading(0), modelStateOf(ready = false, work = WorkInfo.State.ENQUEUED, percent = 0))
    }

    @Test
    fun `failed work without a model is Failed`() {
        assertEquals(ModelState.Failed, modelStateOf(ready = false, work = WorkInfo.State.FAILED, percent = 12))
    }

    @Test
    fun `cancelled or never started is NotDownloaded`() {
        assertEquals(ModelState.NotDownloaded, modelStateOf(ready = false, work = WorkInfo.State.CANCELLED, percent = 12))
        assertEquals(ModelState.NotDownloaded, modelStateOf(ready = false, work = null, percent = 0))
    }
}
