package com.petroff.synchra.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Needs the Nemotron 3.5 files in the app's filesDir/model (the 650 MB model is not bundled):
 *   adb push <extracted model dir>/. /data/local/tmp/nemotron35/
 *   adb shell run-as com.petroff.synchra sh -c 'mkdir -p files/model && cp -r /data/local/tmp/nemotron35/. files/model/'
 * Skipped when they are absent.
 */
@RunWith(AndroidJUnit4::class)
class SherpaOnnxAsrEngineInstrumentedTest {

    @Test
    fun recognizesEnglishClip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "model")
        val paths = ModelPaths(
            encoder = File(dir, "encoder.int8.onnx").absolutePath,
            decoder = File(dir, "decoder.int8.onnx").absolutePath,
            joiner = File(dir, "joiner.int8.onnx").absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath,
        )
        assumeTrue("model not on the device", listOf(paths.encoder, paths.decoder, paths.joiner, paths.tokens).all { File(it).length() > 0 })

        val audio = WavFileAudioSource(InstrumentationRegistry.getInstrumentation().context.assets, EN_WAV)
        val engine = SherpaOnnxAsrEngine(audio, { createNemotronDecoder(paths, language = "en") })
        val texts = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val done = CountDownLatch(1)
        engine.start(
            onSegment = { segment -> synchronized(texts) { if (segment.isFinal) texts.add(segment.text) else latest = segment.text } },
            onError = { errors.add(it); done.countDown() },
        )
        // The WAV source plays the clip to its end on its own; then decoding needs a moment.
        done.await(40, TimeUnit.SECONDS)
        engine.stop()

        val recognized = synchronized(texts) { (texts + latest).joinToString(" ") }.lowercase()
        assertTrue("errors: $errors", errors.isEmpty())
        assertTrue("got: $recognized", EXPECTED_WORDS.any { it in recognized })
    }

    @Volatile private var latest = ""

    private companion object {
        // "After early nightfall the yellow lamps would light up…" (also used by SonioxE2ETest).
        const val EN_WAV = "test_wavs/0.wav"
        val EXPECTED_WORDS = listOf("nightfall", "yellow", "lamps")
    }
}
