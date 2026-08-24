# TransyncAI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build TransyncAI, an Android app that translates spoken English into Russian in near real time using on-device streaming ASR (Sherpa-ONNX) and the Kimi K2.5 chat completions API.

**Architecture:** Mic audio → `SpeechRecognizer` (Sherpa-ONNX streaming Zipformer, on-device) → `ContextBuffer` (rolling phrase window) → `Translator` (Kimi K2.5 over HTTPS, skipped when view mode is Transcription-only) → `SessionViewModel` → Compose UI. Every collaborator (`SpeechRecognizer`, `Translator`, `SettingsStore`) is an interface; concrete implementations are swapped in at the composition root (`AppContainer`), so business logic is unit-testable without Android or network dependencies.

**Tech Stack:** Kotlin 2.3.20, Jetpack Compose (BOM 2026.08.00), AGP 9.3.0, Gradle 9.5.0, JDK 17, OkHttp 4.12.0 + kotlinx.serialization for the Kimi client, Sherpa-ONNX prebuilt AAR (v1.13.6), androidx.security `EncryptedSharedPreferences` for the API key.

**Spec:** `docs/superpowers/specs/2026-08-24-transyncai-design.md`

## Global Constraints

- Package id `com.petroff.transyncai`, app name **TransyncAI**.
- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`.
- Kotlin + Jetpack Compose only — no XML views.
- SOLID: `SpeechRecognizer` and `Translator` are interfaces; `SessionViewModel` depends only on abstractions, never on `SherpaOnnxAsrEngine` or `KimiTranslationClient` directly.
- TDD (red → green → refactor) for every non-UI, non-native-audio component: `ContextBuffer`, `ConfigRepository`, `KimiTranslationClient`, model-download logic, `SessionViewModel`.
- Settings are dynamic: build-time defaults come from `local.properties` (gitignored) via `BuildConfig` fields (the Android analogue of a `.env` file); runtime overrides live in `SharedPreferences` via `ConfigRepository`, so retuning buffer size or the language list never requires a rebuild.
- API key: entered once in Settings, stored only via Keystore-backed `EncryptedSharedPreferences`. Never hardcoded, never logged, never committed.
- Mic control is a **Start/Stop** button (continuous, not push-to-talk).
- View mode is a 3-way switch: Both / Transcription only / Translation only. In Transcription-only mode, no request is ever sent to the Kimi API.
- Missing/invalid API key blocks Start **only when translation is needed** (view mode ≠ Transcription-only) and routes the user to Settings.
- Network/API failures show an inline error and keep the session running; they never crash it.
- Permissions: `RECORD_AUDIO`, `INTERNET`.

## Known scope deviations from the spec

Surfaced during plan self-review — disclosed rather than silently dropped:

1. **ASR engine failure recovery is deferred.** The spec calls for a toast + auto-restart
   if the recognizer fails. `SherpaOnnxAsrEngine` (Task 7) does not currently catch/recover
   from native decode exceptions — a failure would propagate as an uncaught exception on the
   audio thread. Native decode failures are rare once model files are validated, but this
   should be a fast-follow if real-device testing (Task 11 Step 5 / the Post-plan manual
   check) surfaces recognizer instability.
2. **Settings screen ships without language-list-editing UI.** The spec's Components table
   lists "language list overrides" as a Settings responsibility. Per the user's explicit
   direction ("for first let it will be english... just keep in mind this"), v1 ships a
   single enabled pair and no CRUD UI for it — `ConfigRepository.setLanguagePairs`/
   `setSelectedLanguagePairIndex` (Task 4) already exist as the extension point for that UI
   when more languages are added.
3. **Missing-key handling is disable + inline message, not forced navigation.** The spec says
   "routes to Settings"; Task 8/9 instead disable the Start button and show an inline error
   (via `SessionUiState.canStart`/`errorMessage`), with the Settings gear always one tap away.
   This avoids a jarring auto-navigation and is materially the same UX outcome.

---

## Task 1: Dev environment — Android SDK, Gradle-ready JDK, emulator

**Files:**
- Create: `~/dev/android/transyncai/.envrc-notes.md` (N/A — this task is machine setup, no repo files created)

**Interfaces:** N/A (infrastructure task).

- [ ] **Step 1: Point Gradle at JDK 17**

This machine's default `java` resolves to JDK 8 and `javac` to JDK 21 — neither is what AGP 9.3 needs (JDK 17). Confirm JDK 17 is installed and note its path; it will be wired into `gradle.properties` in Task 2.

Run: `ls /usr/lib/jvm/java-17-openjdk-amd64/bin/java`
Expected: path exists, no error.

- [ ] **Step 2: Install Android cmdline-tools**

```bash
mkdir -p ~/Android/Sdk/cmdline-tools
cd /tmp
curl -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q cmdline-tools.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
rm cmdline-tools.zip
```

If that exact filename 404s (Google rotates build numbers), fetch the current URL from https://developer.android.com/studio#command-line-tools-only first and substitute it.

- [ ] **Step 3: Set ANDROID_HOME and install SDK packages**

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"' >> ~/.bashrc

yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "emulator" "system-images;android-34;google_apis;x86_64"
```

- [ ] **Step 4: Create and boot an AVD**

```bash
avdmanager create avd -n transyncai_avd -k "system-images;android-34;google_apis;x86_64" -d pixel_6 --force
$ANDROID_HOME/emulator/emulator -avd transyncai_avd -no-window -no-audio &
```

Run: `adb wait-for-device && adb shell getprop sys.boot_completed`
Expected: eventually prints `1` (poll every few seconds if it prints blank at first — full boot takes 30–90s).

- [ ] **Step 5: Verify device connectivity**

Run: `adb devices`
Expected: one line like `emulator-5554	device`.

Leave the emulator running for later tasks (`adb -s emulator-5554 shell` still works after this step). No commit — this task touches no repo files.

---

## Task 2: Project scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `local.properties`
- Create: `.gitignore`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/petroff/transyncai/MainActivity.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Test: `app/src/androidTest/java/com/petroff/transyncai/MainActivitySmokeTest.kt`

**Interfaces:** none yet (this task produces the buildable shell everything else attaches to).

- [ ] **Step 1: Write `.gitignore`**

```
.gradle/
build/
*.iml
.idea/
local.properties
captures/
.cxx/
app/release/
app/debug/
```

- [ ] **Step 2: Write `local.properties`**

```properties
sdk.dir=/home/petroff/Android/Sdk
transync.defaultBufferSize=6
transync.apiBaseUrl=https://api.moonshot.ai/v1
```

- [ ] **Step 3: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TransyncAI"
include(":app")
```

- [ ] **Step 4: Write root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
}
```

- [ ] **Step 5: Write `gradle.properties`**

```properties
org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 6: Generate the Gradle wrapper**

```bash
cd /home/petroff/dev/android/transyncai
export PATH="$HOME/Android/Sdk/cmdline-tools/latest/bin:$PATH"
gradle wrapper --gradle-version 9.5.0
```

If `gradle` isn't on PATH, download it once: `sdkmanager` doesn't ship standalone Gradle, so instead bootstrap via the wrapper properties directly — create `gradle/wrapper/gradle-wrapper.properties` by hand:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.0-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

then run `curl -o gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v9.5.0/gradle/wrapper/gradle-wrapper.jar` and create a minimal `gradlew` from the official template if `gradle wrapper` isn't available — but try the plain `gradle wrapper --gradle-version 9.5.0` route first, it's simpler.

- [ ] **Step 7: Write `app/build.gradle.kts`**

```kotlin
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.petroff.transyncai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.petroff.transyncai"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "int", "DEFAULT_BUFFER_SIZE",
            localProps.getProperty("transync.defaultBufferSize", "6")
        )
        buildConfigField(
            "String", "DEFAULT_API_BASE_URL",
            "\"${localProps.getProperty("transync.apiBaseUrl", "https://api.moonshot.ai/v1")}\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

Note: the `implementation(files("libs/sherpa-onnx-1.13.6.aar"))` dependency is deliberately **not** declared here — the AAR file itself doesn't exist until Task 7 downloads it, and referencing a missing file dependency would break this task's own build-verification step (Step 12). Task 7 adds that one dependency line once the file is in place.

- [ ] **Step 8: Write `app/src/main/AndroidManifest.xml`**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.TransyncAI">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.TransyncAI">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 9: Write `app/src/main/res/values/strings.xml` and `themes.xml`**

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">TransyncAI</string>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.TransyncAI" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 10: Write a placeholder `MainActivity.kt`**

```kotlin
package com.petroff.transyncai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("TransyncAI")
                }
            }
        }
    }
}
```

- [ ] **Step 11: Write the smoke test**

```kotlin
package com.petroff.transyncai

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsAppName() {
        composeRule.onNodeWithText("TransyncAI").assertExists()
    }
}
```

- [ ] **Step 12: Build and run on the emulator**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL, 1 test passed (`MainActivitySmokeTest`).

- [ ] **Step 13: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle/ app/
git commit -m "Scaffold TransyncAI Android project with Compose smoke test"
```

(`local.properties` stays untracked per `.gitignore` — confirm with `git status` that it's not staged.)

---

## Task 3: ContextBuffer (TDD)

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/context/ContextBuffer.kt`
- Test: `app/src/test/java/com/petroff/transyncai/context/ContextBufferTest.kt`

**Interfaces:**
- Produces: `class ContextBuffer(maxFinalPhrases: Int)` with `fun addSegment(text: String, isFinal: Boolean)`, `fun context(): String`, `fun currentPhrase(): String`, `fun clear()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.petroff.transyncai.context

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBufferTest {

    @Test
    fun `current phrase reflects the latest partial segment`() {
        val buffer = ContextBuffer(maxFinalPhrases = 3)
        buffer.addSegment("hello wor", isFinal = false)
        assertEquals("hello wor", buffer.currentPhrase())
    }

    @Test
    fun `final segment moves into context and clears current phrase`() {
        val buffer = ContextBuffer(maxFinalPhrases = 3)
        buffer.addSegment("hello world", isFinal = true)
        assertEquals("hello world", buffer.context())
        assertEquals("", buffer.currentPhrase())
    }

    @Test
    fun `context keeps only the most recent maxFinalPhrases`() {
        val buffer = ContextBuffer(maxFinalPhrases = 2)
        buffer.addSegment("one", isFinal = true)
        buffer.addSegment("two", isFinal = true)
        buffer.addSegment("three", isFinal = true)
        assertEquals("two three", buffer.context())
    }

    @Test
    fun `blank final segments are ignored`() {
        val buffer = ContextBuffer(maxFinalPhrases = 3)
        buffer.addSegment("real phrase", isFinal = true)
        buffer.addSegment("   ", isFinal = true)
        assertEquals("real phrase", buffer.context())
    }

    @Test
    fun `clear resets both context and current phrase`() {
        val buffer = ContextBuffer(maxFinalPhrases = 3)
        buffer.addSegment("one", isFinal = true)
        buffer.addSegment("partial", isFinal = false)
        buffer.clear()
        assertEquals("", buffer.context())
        assertEquals("", buffer.currentPhrase())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.context.ContextBufferTest"`
Expected: FAIL — `ContextBuffer` unresolved reference.

- [ ] **Step 3: Implement `ContextBuffer`**

```kotlin
package com.petroff.transyncai.context

class ContextBuffer(private val maxFinalPhrases: Int) {
    private val finalPhrases = ArrayDeque<String>()
    private var current = ""

    fun addSegment(text: String, isFinal: Boolean) {
        if (isFinal) {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                finalPhrases.addLast(trimmed)
                while (finalPhrases.size > maxFinalPhrases) {
                    finalPhrases.removeFirst()
                }
            }
            current = ""
        } else {
            current = text
        }
    }

    fun context(): String = finalPhrases.joinToString(" ")

    fun currentPhrase(): String = current

    fun clear() {
        finalPhrases.clear()
        current = ""
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.context.ContextBufferTest"`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/context/ContextBuffer.kt app/src/test/java/com/petroff/transyncai/context/ContextBufferTest.kt
git commit -m "Add ContextBuffer with TDD coverage"
```

---

## Task 4: Config layer — LanguagePair, AppConfig, SettingsStore, ConfigRepository (TDD)

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/config/LanguagePair.kt`
- Create: `app/src/main/java/com/petroff/transyncai/config/AppConfig.kt`
- Create: `app/src/main/java/com/petroff/transyncai/config/SettingsStore.kt`
- Create: `app/src/main/java/com/petroff/transyncai/config/ConfigRepository.kt`
- Test: `app/src/test/java/com/petroff/transyncai/config/FakeSettingsStore.kt`
- Test: `app/src/test/java/com/petroff/transyncai/config/ConfigRepositoryTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `data class LanguagePair(sourceCode, sourceName, targetCode, targetName)`; `data class AppConfig(bufferSize: Int, apiBaseUrl: String, apiKey: String?, languagePairs: List<LanguagePair>, selectedLanguagePairIndex: Int)`; `interface SettingsStore { getString, putString, getInt, putInt, contains }`; `interface ConfigRepository { fun getConfig(): AppConfig; fun setApiKey(key: String); fun setBufferSize(size: Int); fun setLanguagePairs(pairs: List<LanguagePair>); fun setSelectedLanguagePairIndex(index: Int) }`; `class DefaultConfigRepository(store: SettingsStore, defaultBufferSize: Int, defaultApiBaseUrl: String, defaultLanguagePairs: List<LanguagePair>) : ConfigRepository`.

- [ ] **Step 1: Write `LanguagePair.kt`**

```kotlin
package com.petroff.transyncai.config

data class LanguagePair(
    val sourceCode: String,
    val sourceName: String,
    val targetCode: String,
    val targetName: String,
)

val DEFAULT_LANGUAGE_PAIRS = listOf(
    LanguagePair(sourceCode = "en", sourceName = "English", targetCode = "ru", targetName = "Russian"),
)
```

- [ ] **Step 2: Write `AppConfig.kt`**

```kotlin
package com.petroff.transyncai.config

data class AppConfig(
    val bufferSize: Int,
    val apiBaseUrl: String,
    val apiKey: String?,
    val languagePairs: List<LanguagePair>,
    val selectedLanguagePairIndex: Int,
) {
    val selectedLanguagePair: LanguagePair
        get() = languagePairs[selectedLanguagePairIndex.coerceIn(languagePairs.indices)]
}
```

- [ ] **Step 3: Write `SettingsStore.kt`**

```kotlin
package com.petroff.transyncai.config

interface SettingsStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun contains(key: String): Boolean
}
```

- [ ] **Step 4: Write the failing `ConfigRepository` tests (with an in-memory fake store)**

```kotlin
package com.petroff.transyncai.config

class FakeSettingsStore : SettingsStore {
    private val strings = mutableMapOf<String, String>()
    private val ints = mutableMapOf<String, Int>()

    override fun getString(key: String, default: String) = strings[key] ?: default
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun getInt(key: String, default: Int) = ints[key] ?: default
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun contains(key: String) = strings.containsKey(key) || ints.containsKey(key)
}
```

```kotlin
package com.petroff.transyncai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConfigRepositoryTest {

    private lateinit var store: FakeSettingsStore
    private lateinit var repo: ConfigRepository

    @Before
    fun setUp() {
        store = FakeSettingsStore()
        repo = DefaultConfigRepository(
            store = store,
            defaultBufferSize = 6,
            defaultApiBaseUrl = "https://api.moonshot.ai/v1",
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
        )
    }

    @Test
    fun `falls back to build-time defaults when nothing overridden`() {
        val config = repo.getConfig()
        assertEquals(6, config.bufferSize)
        assertEquals("https://api.moonshot.ai/v1", config.apiBaseUrl)
        assertNull(config.apiKey)
        assertEquals(DEFAULT_LANGUAGE_PAIRS, config.languagePairs)
    }

    @Test
    fun `setApiKey persists and is returned by getConfig`() {
        repo.setApiKey("sk-test-123")
        assertEquals("sk-test-123", repo.getConfig().apiKey)
    }

    @Test
    fun `setBufferSize overrides the default at runtime`() {
        repo.setBufferSize(12)
        assertEquals(12, repo.getConfig().bufferSize)
    }

    @Test
    fun `setLanguagePairs overrides the default list`() {
        val custom = listOf(LanguagePair("en", "English", "es", "Spanish"))
        repo.setLanguagePairs(custom)
        assertEquals(custom, repo.getConfig().languagePairs)
    }

    @Test
    fun `setSelectedLanguagePairIndex is reflected in selectedLanguagePair`() {
        repo.setLanguagePairs(
            listOf(
                LanguagePair("en", "English", "ru", "Russian"),
                LanguagePair("en", "English", "es", "Spanish"),
            )
        )
        repo.setSelectedLanguagePairIndex(1)
        assertEquals("es", repo.getConfig().selectedLanguagePair.targetCode)
    }
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.config.ConfigRepositoryTest"`
Expected: FAIL — `DefaultConfigRepository` unresolved reference.

- [ ] **Step 6: Implement `ConfigRepository.kt`**

Language pairs and the selected index are serialized as a simple delimited string (`sourceCode|sourceName|targetCode|targetName` per pair, pairs joined by `;;`) since `SettingsStore` only exposes primitives.

```kotlin
package com.petroff.transyncai.config

interface ConfigRepository {
    fun getConfig(): AppConfig
    fun setApiKey(key: String)
    fun setBufferSize(size: Int)
    fun setLanguagePairs(pairs: List<LanguagePair>)
    fun setSelectedLanguagePairIndex(index: Int)
}

class DefaultConfigRepository(
    private val store: SettingsStore,
    private val defaultBufferSize: Int,
    private val defaultApiBaseUrl: String,
    private val defaultLanguagePairs: List<LanguagePair>,
) : ConfigRepository {

    override fun getConfig(): AppConfig = AppConfig(
        bufferSize = store.getInt(KEY_BUFFER_SIZE, defaultBufferSize),
        apiBaseUrl = store.getString(KEY_API_BASE_URL, defaultApiBaseUrl),
        apiKey = store.getString(KEY_API_KEY, "").ifBlank { null },
        languagePairs = readLanguagePairs(),
        selectedLanguagePairIndex = store.getInt(KEY_SELECTED_PAIR, 0),
    )

    override fun setApiKey(key: String) = store.putString(KEY_API_KEY, key)

    override fun setBufferSize(size: Int) = store.putInt(KEY_BUFFER_SIZE, size)

    override fun setLanguagePairs(pairs: List<LanguagePair>) {
        val encoded = pairs.joinToString(PAIR_SEPARATOR) {
            listOf(it.sourceCode, it.sourceName, it.targetCode, it.targetName).joinToString(FIELD_SEPARATOR)
        }
        store.putString(KEY_LANGUAGE_PAIRS, encoded)
    }

    override fun setSelectedLanguagePairIndex(index: Int) = store.putInt(KEY_SELECTED_PAIR, index)

    private fun readLanguagePairs(): List<LanguagePair> {
        if (!store.contains(KEY_LANGUAGE_PAIRS)) return defaultLanguagePairs
        val raw = store.getString(KEY_LANGUAGE_PAIRS, "")
        if (raw.isBlank()) return defaultLanguagePairs
        return raw.split(PAIR_SEPARATOR).mapNotNull { entry ->
            val fields = entry.split(FIELD_SEPARATOR)
            if (fields.size != 4) null else LanguagePair(fields[0], fields[1], fields[2], fields[3])
        }
    }

    companion object {
        private const val KEY_BUFFER_SIZE = "buffer_size"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_LANGUAGE_PAIRS = "language_pairs"
        private const val KEY_SELECTED_PAIR = "selected_pair"
        private const val PAIR_SEPARATOR = ";;"
        private const val FIELD_SEPARATOR = "|"
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.config.ConfigRepositoryTest"`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/config/ app/src/test/java/com/petroff/transyncai/config/
git commit -m "Add dynamic ConfigRepository with TDD coverage"
```

---

## Task 5: Translator + KimiTranslationClient (TDD)

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/translate/Translator.kt`
- Create: `app/src/main/java/com/petroff/transyncai/translate/KimiTranslationClient.kt`
- Test: `app/src/test/java/com/petroff/transyncai/translate/KimiTranslationClientTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (takes a plain `apiKeyProvider: () -> String?` and `baseUrl: String`, wired from `ConfigRepository` later in Task 11).
- Produces: `interface Translator { suspend fun translate(context: String, currentPhrase: String, sourceLang: String, targetLang: String): Result<String> }`; `class KimiTranslationClient(httpClient: OkHttpClient, baseUrl: String, apiKeyProvider: () -> String?) : Translator`.

- [ ] **Step 1: Write `Translator.kt`**

```kotlin
package com.petroff.transyncai.translate

interface Translator {
    suspend fun translate(
        context: String,
        currentPhrase: String,
        sourceLang: String,
        targetLang: String,
    ): Result<String>
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.petroff.transyncai.translate

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KimiTranslationClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KimiTranslationClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = KimiTranslationClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKeyProvider = { "sk-test-key" },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends the phrase and returns the translated text on success`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"Привет мир"}}]}"""
            )
        )

        val result = client.translate(
            context = "hello there",
            currentPhrase = "world",
            sourceLang = "en",
            targetLang = "ru",
        )

        assertEquals("Привет мир", result.getOrThrow())

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"kimi-k2.5\""))
        assertTrue(body.contains("world"))
    }

    @Test
    fun `returns failure on a non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        val result = client.translate("ctx", "phrase", "en", "ru")

        assertTrue(result.isFailure)
    }

    @Test
    fun `returns failure without a network call when the api key is missing`() = runTest {
        val noKeyClient = KimiTranslationClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKeyProvider = { null },
        )

        val result = noKeyClient.translate("ctx", "phrase", "en", "ru")

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.translate.KimiTranslationClientTest"`
Expected: FAIL — `KimiTranslationClient` unresolved reference.

- [ ] **Step 4: Implement `KimiTranslationClient.kt`**

```kotlin
package com.petroff.transyncai.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(
    val model: String = "kimi-k2.5",
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @kotlinx.serialization.SerialName("chat_template_kwargs")
    val chatTemplateKwargs: Map<String, Boolean> = mapOf("thinking" to false),
)

@Serializable
private data class ChatCompletionResponse(val choices: List<Choice>) {
    @Serializable
    data class Choice(val message: ChatMessage)
}

class KimiTranslationClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    private val apiKeyProvider: () -> String?,
) : Translator {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // model/temperature/chatTemplateKwargs are set via defaults, not overridden per-call
    }

    override suspend fun translate(
        context: String,
        currentPhrase: String,
        sourceLang: String,
        targetLang: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider() ?: return@withContext Result.failure(
            IllegalStateException("Kimi API key is not set")
        )

        runCatching {
            val requestBody = ChatCompletionRequest(
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = "You are a real-time interpreter. Translate CURRENT from " +
                            "$sourceLang to $targetLang. Use CONTEXT only to disambiguate. " +
                            "Reply with only the translation, no extra text.",
                    ),
                    ChatMessage(role = "user", content = "CONTEXT: $context\nCURRENT: $currentPhrase"),
                ),
            )

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(json.encodeToString(ChatCompletionRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Kimi API request failed: HTTP ${response.code}")
                }
                val bodyText = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), bodyText)
                parsed.choices.first().message.content.trim()
            }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.translate.KimiTranslationClientTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/translate/ app/src/test/java/com/petroff/transyncai/translate/
git commit -m "Add Kimi K2.5 translation client with TDD coverage"
```

---

## Task 6: Model file provider — download-on-first-run logic (TDD)

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/asr/ModelFiles.kt`
- Create: `app/src/main/java/com/petroff/transyncai/asr/ModelFileProvider.kt`
- Test: `app/src/test/java/com/petroff/transyncai/asr/ModelFileProviderTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `data class ModelPaths(encoder: String, decoder: String, joiner: String, tokens: String)`; `interface FileDownloader { fun download(url: String, dest: File) }`; `class ModelFileProvider(downloader: FileDownloader, modelDir: File) { fun ensureModelFiles(): ModelPaths }`.

This is the pure decision logic (which files are missing, where they land) — the actual HTTP download implementation is a thin OkHttp wrapper verified manually in Task 7, since exercising a real multi-ten-megabyte download in a unit test is slow and network-dependent.

- [ ] **Step 1: Write `ModelFiles.kt`**

```kotlin
package com.petroff.transyncai.asr

data class ModelPaths(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)

private const val MODEL_BASE_URL =
    "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main"

val MODEL_FILES = listOf(
    "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
    "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
    "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
    "tokens.txt",
)

fun modelFileUrl(filename: String): String = "$MODEL_BASE_URL/$filename"
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.petroff.transyncai.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeFileDownloader : FileDownloader {
    val requested = mutableListOf<String>()
    override fun download(url: String, dest: File) {
        requested.add(url)
        dest.writeText("fake-content-for-${dest.name}")
    }
}

class ModelFileProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `downloads every model file into modelDir when none exist`() {
        val downloader = FakeFileDownloader()
        val provider = ModelFileProvider(downloader, tmp.root)

        val paths = provider.ensureModelFiles()

        assertEquals(4, downloader.requested.size)
        assertTrue(File(paths.encoder).exists())
        assertTrue(File(paths.decoder).exists())
        assertTrue(File(paths.joiner).exists())
        assertTrue(File(paths.tokens).exists())
    }

    @Test
    fun `skips files that already exist and are non-empty`() {
        val downloader = FakeFileDownloader()
        File(tmp.root, "tokens.txt").writeText("already-here")

        ModelFileProvider(downloader, tmp.root).ensureModelFiles()

        assertTrue(downloader.requested.none { it.endsWith("tokens.txt") })
    }

    @Test
    fun `re-downloads a file that exists but is empty`() {
        val downloader = FakeFileDownloader()
        File(tmp.root, "tokens.txt").writeText("")

        ModelFileProvider(downloader, tmp.root).ensureModelFiles()

        assertTrue(downloader.requested.any { it.endsWith("tokens.txt") })
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.asr.ModelFileProviderTest"`
Expected: FAIL — `ModelFileProvider`/`FileDownloader` unresolved references.

- [ ] **Step 4: Implement `ModelFileProvider.kt`**

```kotlin
package com.petroff.transyncai.asr

import java.io.File

interface FileDownloader {
    fun download(url: String, dest: File)
}

class ModelFileProvider(
    private val downloader: FileDownloader,
    private val modelDir: File,
) {
    fun ensureModelFiles(): ModelPaths {
        modelDir.mkdirs()
        for (filename in MODEL_FILES) {
            val dest = File(modelDir, filename)
            if (!dest.exists() || dest.length() == 0L) {
                downloader.download(modelFileUrl(filename), dest)
            }
        }
        return ModelPaths(
            encoder = File(modelDir, MODEL_FILES[0]).absolutePath,
            decoder = File(modelDir, MODEL_FILES[1]).absolutePath,
            joiner = File(modelDir, MODEL_FILES[2]).absolutePath,
            tokens = File(modelDir, MODEL_FILES[3]).absolutePath,
        )
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.asr.ModelFileProviderTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/asr/ModelFiles.kt app/src/main/java/com/petroff/transyncai/asr/ModelFileProvider.kt app/src/test/java/com/petroff/transyncai/asr/ModelFileProviderTest.kt
git commit -m "Add model file download-on-first-run logic with TDD coverage"
```

---

## Task 7: Sherpa-ONNX integration — SpeechRecognizer, AudioSource, SherpaOnnxAsrEngine

**Files:**
- Create: `app/libs/sherpa-onnx-1.13.6.aar`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/petroff/transyncai/asr/SpeechRecognizer.kt`
- Create: `app/src/main/java/com/petroff/transyncai/asr/AudioSource.kt`
- Create: `app/src/main/java/com/petroff/transyncai/asr/MicAudioSource.kt`
- Create: `app/src/main/java/com/petroff/transyncai/asr/WavFileAudioSource.kt`
- Create: `app/src/main/java/com/petroff/transyncai/asr/SherpaOnnxAsrEngine.kt`
- Create: `app/src/main/java/com/petroff/transyncai/asr/OkHttpFileDownloader.kt`
- Create: `app/src/androidTest/assets/test_wavs/0.wav`
- Test: `app/src/androidTest/java/com/petroff/transyncai/asr/SherpaOnnxAsrEngineInstrumentedTest.kt`

**Interfaces:**
- Consumes: `ModelFileProvider`/`ModelPaths` (Task 6), `FileDownloader` (Task 6).
- Produces: `data class AsrSegment(text: String, isFinal: Boolean)`; `interface SpeechRecognizer { fun start(onSegment: (AsrSegment) -> Unit); fun stop() }`; `interface AudioSource { fun start(onSamples: (FloatArray) -> Unit); fun stop() }`; `class SherpaOnnxAsrEngine(audioSource: AudioSource, recognizerFactory: () -> OnlineRecognizer) : SpeechRecognizer`.

Native ASR + real microphone hardware cannot be meaningfully unit-tested — this task is verified with an instrumented test on the emulator instead, feeding a real WAV file through `WavFileAudioSource` so the exact same recognition code path used by the mic is exercised without depending on emulator audio routing.

- [ ] **Step 1: Download the Sherpa-ONNX AAR**

```bash
mkdir -p /home/petroff/dev/android/transyncai/app/libs
curl -L -o /home/petroff/dev/android/transyncai/app/libs/sherpa-onnx-1.13.6.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6/sherpa-onnx-1.13.6.aar
```

Run: `ls -lh app/libs/sherpa-onnx-1.13.6.aar`
Expected: file exists, several MB in size (bundles native `.so` for arm64-v8a, armeabi-v7a, x86, x86_64 plus the compiled `com.k2fsa.sherpa.onnx` Kotlin API classes — no NDK build needed).

- [ ] **Step 2: Add the AAR dependency to `app/build.gradle.kts`**

Task 2 deliberately left this out (the file didn't exist yet). Add one line to the `dependencies { ... }` block, immediately before `testImplementation("junit:junit:4.13.2")`:

```kotlin
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))

```

- [ ] **Step 3: Write `SpeechRecognizer.kt` and `AudioSource.kt` interfaces**

```kotlin
package com.petroff.transyncai.asr

data class AsrSegment(val text: String, val isFinal: Boolean)

interface SpeechRecognizer {
    fun start(onSegment: (AsrSegment) -> Unit)
    fun stop()
}
```

```kotlin
package com.petroff.transyncai.asr

interface AudioSource {
    /** sampleRate is always 16000 Hz, mono, matching the model's expected input. */
    fun start(onSamples: (FloatArray) -> Unit)
    fun stop()
}
```

- [ ] **Step 4: Implement `MicAudioSource.kt`**

```kotlin
package com.petroff.transyncai.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MicAudioSource : AudioSource {

    private val running = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(onSamples: (FloatArray) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize.coerceAtLeast(CHUNK_SAMPLES * 2),
        )

        running.set(true)
        audioRecord.startRecording()

        recordingThread = thread(name = "MicAudioSource") {
            val shortBuffer = ShortArray(CHUNK_SAMPLES)
            while (running.get()) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    val floatBuffer = FloatArray(read) { i -> shortBuffer[i] / 32768.0f }
                    onSamples(floatBuffer)
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }
    }

    override fun stop() {
        running.set(false)
        recordingThread?.join(timeoutMillis = 500)
        recordingThread = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600 // ~100ms at 16kHz
    }
}
```

- [ ] **Step 5: Implement `WavFileAudioSource.kt`**

```kotlin
package com.petroff.transyncai.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.WaveReader
import java.util.concurrent.atomic.AtomicBoolean

/** Feeds a bundled WAV asset through the same acceptWaveform path a real mic would use. */
class WavFileAudioSource(
    private val assetManager: AssetManager,
    private val assetPath: String,
) : AudioSource {

    private val running = AtomicBoolean(false)

    override fun start(onSamples: (FloatArray) -> Unit) {
        running.set(true)
        val wave = WaveReader.readWave(assetManager, assetPath)
        require(wave.sampleRate == MicAudioSource.SAMPLE_RATE) {
            "Test WAV must be ${MicAudioSource.SAMPLE_RATE}Hz to match the model's expected input, got ${wave.sampleRate}Hz"
        }
        var offset = 0
        while (running.get() && offset < wave.samples.size) {
            val end = (offset + CHUNK_SAMPLES).coerceAtMost(wave.samples.size)
            onSamples(wave.samples.copyOfRange(offset, end))
            offset = end
        }
        running.set(false)
    }

    override fun stop() {
        running.set(false)
    }

    companion object {
        private const val CHUNK_SAMPLES = 1600
    }
}
```

- [ ] **Step 6: Implement `SherpaOnnxAsrEngine.kt`**

```kotlin
package com.petroff.transyncai.asr

import com.k2fsa.sherpa.onnx.OnlineRecognizer

class SherpaOnnxAsrEngine(
    private val audioSource: AudioSource,
    private val recognizerFactory: () -> OnlineRecognizer,
) : SpeechRecognizer {

    override fun start(onSegment: (AsrSegment) -> Unit) {
        val recognizer = recognizerFactory()
        val stream = recognizer.createStream()

        audioSource.start { samples ->
            stream.acceptWaveform(samples, MicAudioSource.SAMPLE_RATE)
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            val isEndpoint = recognizer.isEndpoint(stream)
            val text = recognizer.getResult(stream).text
            if (text.isNotBlank() || isEndpoint) {
                onSegment(AsrSegment(text, isEndpoint))
            }
            if (isEndpoint) {
                recognizer.reset(stream)
            }
        }
    }

    override fun stop() {
        audioSource.stop()
    }
}
```

- [ ] **Step 7: Implement `OkHttpFileDownloader.kt`**

```kotlin
package com.petroff.transyncai.asr

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

class OkHttpFileDownloader(private val httpClient: OkHttpClient) : FileDownloader {
    override fun download(url: String, dest: File) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Failed to download $url: HTTP ${response.code}" }
            dest.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
    }
}
```

- [ ] **Step 8: Add the test WAV asset**

```bash
mkdir -p /home/petroff/dev/android/transyncai/app/src/androidTest/assets/test_wavs
curl -L -o /home/petroff/dev/android/transyncai/app/src/androidTest/assets/test_wavs/0.wav \
  https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/test_wavs/0.wav
```

- [ ] **Step 9: Write the instrumented test**

```kotlin
package com.petroff.transyncai.asr

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaOnnxAsrEngineInstrumentedTest {

    @Test
    fun recognizesKnownPhraseFromBundledWav() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(context.filesDir, "model")
        val paths = ModelFileProvider(OkHttpFileDownloader(OkHttpClient()), modelDir).ensureModelFiles()

        val recognizerFactory = {
            OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = paths.encoder,
                            decoder = paths.decoder,
                            joiner = paths.joiner,
                        ),
                        tokens = paths.tokens,
                        modelType = "zipformer2",
                        numThreads = 2,
                    ),
                )
            )
        }

        val audioSource = WavFileAudioSource(
            InstrumentationRegistry.getInstrumentation().context.assets,
            "test_wavs/0.wav",
        )
        val engine = SherpaOnnxAsrEngine(audioSource, recognizerFactory)

        val finalTexts = mutableListOf<String>()
        val latch = CountDownLatch(1)
        engine.start { segment ->
            if (segment.isFinal) {
                finalTexts.add(segment.text)
                latch.countDown()
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        engine.stop()

        val recognized = finalTexts.joinToString(" ").uppercase()
        assertTrue(
            "Expected recognized text to contain a known phrase from 0.wav, got: $recognized",
            recognized.contains("YELLOW LAMPS") || recognized.contains("NIGHTFALL"),
        )
    }
}
```

- [ ] **Step 10: Run the instrumented test on the emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.petroff.transyncai.asr.SherpaOnnxAsrEngineInstrumentedTest"`
Expected: BUILD SUCCESSFUL, 1 test passed. (First run downloads ~30MB of model files over the emulator's network — allow extra time.)

- [ ] **Step 11: Commit**

```bash
git add app/libs/sherpa-onnx-1.13.6.aar app/build.gradle.kts app/src/main/java/com/petroff/transyncai/asr/ app/src/androidTest/
git commit -m "Integrate Sherpa-ONNX streaming ASR engine with instrumented WAV test"
```

---

## Task 8: SessionViewModel + ViewMode orchestration (TDD)

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/session/ViewMode.kt`
- Create: `app/src/main/java/com/petroff/transyncai/session/SessionUiState.kt`
- Create: `app/src/main/java/com/petroff/transyncai/session/SessionViewModel.kt`
- Test: `app/src/test/java/com/petroff/transyncai/session/FakeSpeechRecognizer.kt`
- Test: `app/src/test/java/com/petroff/transyncai/session/FakeTranslator.kt`
- Test: `app/src/test/java/com/petroff/transyncai/session/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: `SpeechRecognizer`, `AsrSegment` (Task 7); `Translator` (Task 5); `ConfigRepository`, `AppConfig`, `LanguagePair` (Task 4).
- Produces: `enum class ViewMode { BOTH, TRANSCRIPTION_ONLY, TRANSLATION_ONLY }`; `data class SessionUiState(isListening, viewMode, sourceText, translatedText, errorMessage, canStart)`; `class SessionViewModel(speechRecognizer: SpeechRecognizer, translator: Translator, configRepository: ConfigRepository) : ViewModel()` exposing `val uiState: StateFlow<SessionUiState>`, `fun start()`, `fun stop()`, `fun setViewMode(mode: ViewMode)`.

- [ ] **Step 1: Write `ViewMode.kt` and `SessionUiState.kt`**

```kotlin
package com.petroff.transyncai.session

enum class ViewMode { BOTH, TRANSCRIPTION_ONLY, TRANSLATION_ONLY }
```

```kotlin
package com.petroff.transyncai.session

data class SessionUiState(
    val isListening: Boolean = false,
    val viewMode: ViewMode = ViewMode.BOTH,
    val sourceText: String = "",
    val translatedText: String = "",
    val errorMessage: String? = null,
    val canStart: Boolean = true,
)
```

- [ ] **Step 2: Write test fakes**

```kotlin
package com.petroff.transyncai.session

import com.petroff.transyncai.asr.AsrSegment
import com.petroff.transyncai.asr.SpeechRecognizer

class FakeSpeechRecognizer : SpeechRecognizer {
    var started = false
    var stopped = false
    private var listener: ((AsrSegment) -> Unit)? = null

    override fun start(onSegment: (AsrSegment) -> Unit) {
        started = true
        listener = onSegment
    }

    override fun stop() {
        stopped = true
    }

    fun emit(text: String, isFinal: Boolean) {
        listener?.invoke(AsrSegment(text, isFinal))
    }
}
```

```kotlin
package com.petroff.transyncai.session

import com.petroff.transyncai.translate.Translator

class FakeTranslator : Translator {
    val calls = mutableListOf<String>()
    var result: Result<String> = Result.success("translated")

    override suspend fun translate(
        context: String,
        currentPhrase: String,
        sourceLang: String,
        targetLang: String,
    ): Result<String> {
        calls.add(currentPhrase)
        return result
    }
}
```

- [ ] **Step 3: Write the failing `SessionViewModel` tests**

```kotlin
package com.petroff.transyncai.session

import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.FakeSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var recognizer: FakeSpeechRecognizer
    private lateinit var translator: FakeTranslator
    private lateinit var configRepository: ConfigRepository
    private lateinit var viewModel: SessionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        recognizer = FakeSpeechRecognizer()
        translator = FakeTranslator()
        configRepository = DefaultConfigRepository(
            store = FakeSettingsStore(),
            defaultBufferSize = 6,
            defaultApiBaseUrl = "https://api.moonshot.ai/v1",
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
        )
        configRepository.setApiKey("sk-test")
        viewModel = SessionViewModel(recognizer, translator, configRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start with a key in Both mode starts the recognizer`() {
        viewModel.start()
        assertTrue(recognizer.started)
        assertTrue(viewModel.uiState.value.isListening)
    }

    @Test
    fun `Both mode calls the translator for each final segment`() = runTest(dispatcher) {
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("hello world"), translator.calls)
        assertEquals("translated", viewModel.uiState.value.translatedText)
    }

    @Test
    fun `Transcription-only mode never calls the translator`() = runTest(dispatcher) {
        viewModel.setViewMode(ViewMode.TRANSCRIPTION_ONLY)
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(translator.calls.isEmpty())
        assertTrue(viewModel.uiState.value.sourceText.contains("hello world"))
    }

    @Test
    fun `Transcription-only mode can start even without an api key`() {
        configRepository.setApiKey("")
        viewModel.setViewMode(ViewMode.TRANSCRIPTION_ONLY)

        viewModel.start()

        assertTrue(recognizer.started)
    }

    @Test
    fun `Both mode blocks start when the api key is missing`() {
        configRepository.setApiKey("")
        viewModel.setViewMode(ViewMode.BOTH)

        viewModel.start()

        assertFalse(recognizer.started)
        assertFalse(viewModel.uiState.value.canStart)
    }

    @Test
    fun `canStart is false proactively, before any start attempt, when Both mode has no key`() {
        val noKeyRepo = DefaultConfigRepository(
            store = FakeSettingsStore(),
            defaultBufferSize = 6,
            defaultApiBaseUrl = "https://api.moonshot.ai/v1",
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
        )
        val freshViewModel = SessionViewModel(FakeSpeechRecognizer(), FakeTranslator(), noKeyRepo)

        assertFalse(freshViewModel.uiState.value.canStart)
    }

    @Test
    fun `stop stops the recognizer and updates state`() {
        viewModel.start()
        viewModel.stop()

        assertTrue(recognizer.stopped)
        assertFalse(viewModel.uiState.value.isListening)
    }

    @Test
    fun `translator failure sets an error but keeps the session listening`() = runTest(dispatcher) {
        translator.result = Result.failure(RuntimeException("network down"))
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("network down", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.isListening)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.session.SessionViewModelTest"`
Expected: FAIL — `SessionViewModel` unresolved reference.

- [ ] **Step 5: Implement `SessionViewModel.kt`**

```kotlin
package com.petroff.transyncai.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.petroff.transyncai.asr.AsrSegment
import com.petroff.transyncai.asr.SpeechRecognizer
import com.petroff.transyncai.config.AppConfig
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.context.ContextBuffer
import com.petroff.transyncai.translate.Translator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionViewModel(
    private val speechRecognizer: SpeechRecognizer,
    private val translator: Translator,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var contextBuffer = ContextBuffer(configRepository.getConfig().bufferSize)

    init {
        _uiState.update { it.copy(canStart = computeCanStart(it.viewMode)) }
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode, canStart = computeCanStart(mode)) }
    }

    private fun computeCanStart(mode: ViewMode): Boolean {
        val needsTranslation = mode != ViewMode.TRANSCRIPTION_ONLY
        return !needsTranslation || !configRepository.getConfig().apiKey.isNullOrBlank()
    }

    fun start() {
        val config = configRepository.getConfig()
        if (!computeCanStart(_uiState.value.viewMode)) {
            _uiState.update { it.copy(canStart = false, errorMessage = "Set your Kimi API key in Settings") }
            return
        }

        contextBuffer = ContextBuffer(config.bufferSize)
        _uiState.update {
            it.copy(isListening = true, sourceText = "", translatedText = "", errorMessage = null, canStart = true)
        }

        speechRecognizer.start { segment -> onSegment(segment, config) }
    }

    fun stop() {
        speechRecognizer.stop()
        _uiState.update { it.copy(isListening = false) }
    }

    private fun onSegment(segment: AsrSegment, config: AppConfig) {
        contextBuffer.addSegment(segment.text, segment.isFinal)
        val displaySource = listOf(contextBuffer.context(), contextBuffer.currentPhrase())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _uiState.update { it.copy(sourceText = displaySource) }

        if (_uiState.value.viewMode == ViewMode.TRANSCRIPTION_ONLY) return
        if (!segment.isFinal) return

        val pair = config.selectedLanguagePair
        viewModelScope.launch {
            translator.translate(contextBuffer.context(), segment.text, pair.sourceCode, pair.targetCode)
                .onSuccess { text -> _uiState.update { it.copy(translatedText = text, errorMessage = null) } }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message ?: "Translation failed") } }
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.petroff.transyncai.session.SessionViewModelTest"`
Expected: BUILD SUCCESSFUL, 8 tests passed.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/session/ app/src/test/java/com/petroff/transyncai/session/
git commit -m "Add SessionViewModel orchestration with TDD coverage"
```

---

## Task 9: Compose MainScreen UI

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/ui/MainScreen.kt`
- Test: `app/src/androidTest/java/com/petroff/transyncai/ui/MainScreenTest.kt`

**Interfaces:**
- Consumes: `SessionViewModel`, `SessionUiState`, `ViewMode` (Task 8); `LanguagePair` (Task 4).
- Produces: `@Composable fun MainScreen(uiState: SessionUiState, languagePairs: List<LanguagePair>, selectedIndex: Int, onSelectLanguagePair: (Int) -> Unit, onToggleListening: () -> Unit, onViewModeChange: (ViewMode) -> Unit, onOpenSettings: () -> Unit)`.

- [ ] **Step 1: Write the failing UI test**

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.session.SessionUiState
import com.petroff.transyncai.session.ViewMode
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startStopButtonTogglesLabel() {
        var listening = false
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = listening),
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedIndex = 0,
                onSelectLanguagePair = {},
                onToggleListening = { listening = !listening },
                onViewModeChange = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Start").assertExists()
    }

    @Test
    fun transcriptionOnlyModeHidesTranslationPanel() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(
                    viewMode = ViewMode.TRANSCRIPTION_ONLY,
                    sourceText = "hello world",
                    translatedText = "should not show",
                ),
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedIndex = 0,
                onSelectLanguagePair = {},
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("hello world").assertExists()
        composeRule.onNodeWithText("should not show").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.petroff.transyncai.ui.MainScreenTest"`
Expected: FAIL — `MainScreen` unresolved reference.

- [ ] **Step 3: Implement `MainScreen.kt`**

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.petroff.transyncai.config.LanguagePair
import com.petroff.transyncai.session.SessionUiState
import com.petroff.transyncai.session.ViewMode

@Composable
fun MainScreen(
    uiState: SessionUiState,
    languagePairs: List<LanguagePair>,
    selectedIndex: Int,
    onSelectLanguagePair: (Int) -> Unit,
    onToggleListening: () -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TransyncAI") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LanguagePairDropdown(languagePairs, selectedIndex, onSelectLanguagePair)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ViewMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = uiState.viewMode == mode,
                        onClick = { onViewModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ViewMode.entries.size),
                    ) {
                        Text(mode.label())
                    }
                }
            }

            Button(
                onClick = onToggleListening,
                enabled = uiState.isListening || uiState.canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uiState.isListening) "Stop" else "Start")
            }

            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (uiState.viewMode != ViewMode.TRANSLATION_ONLY) {
                Text("Source", style = MaterialTheme.typography.labelLarge)
                Text(uiState.sourceText, style = MaterialTheme.typography.headlineSmall)
            }
            if (uiState.viewMode != ViewMode.TRANSCRIPTION_ONLY) {
                Text("Translation", style = MaterialTheme.typography.labelLarge)
                Text(uiState.translatedText, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

private fun ViewMode.label(): String = when (this) {
    ViewMode.BOTH -> "Both"
    ViewMode.TRANSCRIPTION_ONLY -> "Transcription"
    ViewMode.TRANSLATION_ONLY -> "Translation"
}

@Composable
private fun LanguagePairDropdown(
    languagePairs: List<LanguagePair>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = languagePairs.getOrNull(selectedIndex) ?: languagePairs.first()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            readOnly = true,
            value = "${selected.sourceName} → ${selected.targetName}",
            onValueChange = {},
            label = { Text("Languages") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languagePairs.forEachIndexed { index, pair ->
                DropdownMenuItem(
                    text = { Text("${pair.sourceName} → ${pair.targetName}") },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.petroff.transyncai.ui.MainScreenTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/ui/MainScreen.kt app/src/androidTest/java/com/petroff/transyncai/ui/MainScreenTest.kt
git commit -m "Add MainScreen Compose UI with Start/Stop and view mode switch"
```

---

## Task 10: Compose SettingsScreen UI + real EncryptedSharedPreferences store + permission handling

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/config/EncryptedPrefsSettingsStore.kt`
- Create: `app/src/main/java/com/petroff/transyncai/ui/SettingsScreen.kt`
- Create: `app/src/main/java/com/petroff/transyncai/ui/PermissionHandling.kt`
- Test: `app/src/androidTest/java/com/petroff/transyncai/ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `SettingsStore` (Task 4).
- Produces: `class EncryptedPrefsSettingsStore(context: Context) : SettingsStore`; `@Composable fun SettingsScreen(currentApiKey: String?, currentBufferSize: Int, onSaveApiKey: (String) -> Unit, onSaveBufferSize: (Int) -> Unit, onBack: () -> Unit)`; `@Composable fun RequiresRecordAudioPermission(content: @Composable () -> Unit)`.

- [ ] **Step 1: Implement `EncryptedPrefsSettingsStore.kt`**

```kotlin
package com.petroff.transyncai.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedPrefsSettingsStore(context: Context) : SettingsStore {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "transyncai_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun contains(key: String): Boolean = prefs.contains(key)
}
```

- [ ] **Step 2: Write the failing UI test**

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun enteringAndSavingApiKeyInvokesCallback() {
        var savedKey: String? = null
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentBufferSize = 6,
                onSaveApiKey = { savedKey = it },
                onSaveBufferSize = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Kimi API key").performTextInput("sk-abc-123")
        composeRule.onNodeWithText("Save").performClick()

        assert(savedKey == "sk-abc-123")
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.petroff.transyncai.ui.SettingsScreenTest"`
Expected: FAIL — `SettingsScreen` unresolved reference.

- [ ] **Step 4: Implement `SettingsScreen.kt`**

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
```

- [ ] **Step 5: Implement `PermissionHandling.kt`**

```kotlin
package com.petroff.transyncai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun RequiresRecordAudioPermission(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        granted = isGranted
    }

    if (granted) {
        content()
    } else {
        Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
            Text("Grant microphone access")
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.petroff.transyncai.ui.SettingsScreenTest"`
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/config/EncryptedPrefsSettingsStore.kt app/src/main/java/com/petroff/transyncai/ui/SettingsScreen.kt app/src/main/java/com/petroff/transyncai/ui/PermissionHandling.kt app/src/androidTest/java/com/petroff/transyncai/ui/SettingsScreenTest.kt
git commit -m "Add Settings screen, Keystore-backed settings store, and mic permission flow"
```

---

## Task 11: Wire it together — AppContainer, navigation, MainActivity

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/AppContainer.kt`
- Create: `app/src/main/java/com/petroff/transyncai/ui/TransyncAiApp.kt`
- Modify: `app/src/main/java/com/petroff/transyncai/MainActivity.kt`

**Interfaces:**
- Consumes: everything produced in Tasks 3–10.
- Produces: `class AppContainer(context: Context) { val configRepository: ConfigRepository, val translator: Translator, fun buildSpeechRecognizer(): SpeechRecognizer }`; `@Composable fun TransyncAiApp(container: AppContainer)`.

- [ ] **Step 1: Implement `AppContainer.kt`**

```kotlin
package com.petroff.transyncai

import android.content.Context
import com.petroff.transyncai.asr.MicAudioSource
import com.petroff.transyncai.asr.ModelFileProvider
import com.petroff.transyncai.asr.OkHttpFileDownloader
import com.petroff.transyncai.asr.SherpaOnnxAsrEngine
import com.petroff.transyncai.asr.SpeechRecognizer
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.EncryptedPrefsSettingsStore
import com.petroff.transyncai.translate.KimiTranslationClient
import com.petroff.transyncai.translate.Translator
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import okhttp3.OkHttpClient

class AppContainer(private val context: Context) {

    private val httpClient = OkHttpClient()

    val configRepository: ConfigRepository = DefaultConfigRepository(
        store = EncryptedPrefsSettingsStore(context),
        defaultBufferSize = BuildConfig.DEFAULT_BUFFER_SIZE,
        defaultApiBaseUrl = BuildConfig.DEFAULT_API_BASE_URL,
        defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
    )

    val translator: Translator = KimiTranslationClient(
        httpClient = httpClient,
        baseUrl = configRepository.getConfig().apiBaseUrl,
        apiKeyProvider = { configRepository.getConfig().apiKey },
    )

    fun buildSpeechRecognizer(): SpeechRecognizer {
        val modelDir = File(context.filesDir, "model")
        val paths = ModelFileProvider(OkHttpFileDownloader(httpClient), modelDir).ensureModelFiles()
        val recognizerFactory = {
            OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = paths.encoder,
                            decoder = paths.decoder,
                            joiner = paths.joiner,
                        ),
                        tokens = paths.tokens,
                        modelType = "zipformer2",
                        numThreads = 2,
                    ),
                )
            )
        }
        return SherpaOnnxAsrEngine(MicAudioSource(), recognizerFactory)
    }
}
```

- [ ] **Step 2: Implement `TransyncAiApp.kt` (navigation host)**

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.petroff.transyncai.AppContainer
import com.petroff.transyncai.session.SessionViewModel
import com.petroff.transyncai.session.SessionViewModelFactory

@Composable
fun TransyncAiApp(container: AppContainer) {
    MaterialTheme {
        Surface {
            RequiresRecordAudioPermission {
                val navController = rememberNavController()
                val sessionViewModel: SessionViewModel = viewModel(
                    factory = SessionViewModelFactory(container)
                )
                val uiState by sessionViewModel.uiState.collectAsState()

                // Re-read config whenever the visible screen changes, so edits made in
                // Settings (API key, buffer size) are picked up as soon as the user
                // navigates back to Main, without needing ConfigRepository to be reactive.
                val backStackEntry by navController.currentBackStackEntryAsState()
                var config by remember { mutableStateOf(container.configRepository.getConfig()) }
                LaunchedEffect(backStackEntry) {
                    config = container.configRepository.getConfig()
                }

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            uiState = uiState,
                            languagePairs = config.languagePairs,
                            selectedIndex = config.selectedLanguagePairIndex,
                            onSelectLanguagePair = { container.configRepository.setSelectedLanguagePairIndex(it) },
                            onToggleListening = {
                                if (uiState.isListening) sessionViewModel.stop() else sessionViewModel.start()
                            },
                            onViewModeChange = sessionViewModel::setViewMode,
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            currentApiKey = config.apiKey,
                            currentBufferSize = config.bufferSize,
                            onSaveApiKey = { container.configRepository.setApiKey(it) },
                            onSaveBufferSize = { container.configRepository.setBufferSize(it) },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Add a `SessionViewModelFactory`**

```kotlin
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
        ) as T
    }
}
```

- [ ] **Step 4: Update `MainActivity.kt`**

```kotlin
package com.petroff.transyncai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.petroff.transyncai.ui.TransyncAiApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer(applicationContext)
        setContent {
            TransyncAiApp(container)
        }
    }
}
```

- [ ] **Step 5: Build and install on the emulator, verify manually**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL, app installs.

Then manually: launch TransyncAI on the emulator, grant the mic permission prompt, open Settings, paste a real Kimi API key, go back, tap Start, speak into the host mic (if the emulator is running with `-no-audio` from Task 1, restart it without that flag for this manual check: `emulator -avd transyncai_avd`), confirm source text and Russian translation both update live, tap the view mode switch and confirm panels show/hide correctly, tap Stop.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/AppContainer.kt app/src/main/java/com/petroff/transyncai/ui/TransyncAiApp.kt app/src/main/java/com/petroff/transyncai/session/SessionViewModelFactory.kt app/src/main/java/com/petroff/transyncai/MainActivity.kt
git commit -m "Wire AppContainer, navigation, and MainActivity into a runnable app"
```

---

## Task 12: End-to-end instrumented test on the emulator

**Files:**
- Create: `app/src/androidTest/java/com/petroff/transyncai/E2ETranslationFlowTest.kt`

**Interfaces:**
- Consumes: `TransyncAiApp`, `AppContainer` (Task 11), `WavFileAudioSource` (Task 7), `ConfigRepository` (Task 4).

Real device audio routing through the emulator is unreliable to script deterministically, so this e2e test reuses the `WavFileAudioSource` from Task 7 to drive the full pipeline (ASR → buffer → translation → UI) end-to-end without depending on emulator mic hardware, and points the Kimi client at a local `MockWebServer` (running on-device inside the instrumentation process, reachable at `127.0.0.1`) so the test doesn't depend on a real network call or a real API key.

- [ ] **Step 1: Write the e2e test**

```kotlin
package com.petroff.transyncai

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.petroff.transyncai.asr.ModelFileProvider
import com.petroff.transyncai.asr.OkHttpFileDownloader
import com.petroff.transyncai.asr.SherpaOnnxAsrEngine
import com.petroff.transyncai.asr.WavFileAudioSource
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.FakeSettingsStore
import com.petroff.transyncai.session.SessionViewModel
import com.petroff.transyncai.translate.KimiTranslationClient
import com.petroff.transyncai.ui.TransyncAiApp
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2ETranslationFlowTest {

    private lateinit var server: MockWebServer

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"После раннего наступления темноты"}}]}"""
            )
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fullPipelineProducesTranslatedTextFromBundledAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsStore = FakeSettingsStore()
        val configRepository = DefaultConfigRepository(
            store = settingsStore,
            defaultBufferSize = 6,
            defaultApiBaseUrl = server.url("/v1").toString().trimEnd('/'),
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
        )
        configRepository.setApiKey("sk-e2e-test")

        val translator = KimiTranslationClient(
            httpClient = OkHttpClient(),
            baseUrl = configRepository.getConfig().apiBaseUrl,
            apiKeyProvider = { configRepository.getConfig().apiKey },
        )

        val modelDir = File(context.filesDir, "model")
        val paths = ModelFileProvider(OkHttpFileDownloader(OkHttpClient()), modelDir).ensureModelFiles()
        val recognizerFactory = {
            OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = paths.encoder, decoder = paths.decoder, joiner = paths.joiner,
                        ),
                        tokens = paths.tokens,
                        modelType = "zipformer2",
                        numThreads = 2,
                    ),
                )
            )
        }
        val audioSource = WavFileAudioSource(
            InstrumentationRegistry.getInstrumentation().context.assets,
            "test_wavs/0.wav",
        )
        val engine = SherpaOnnxAsrEngine(audioSource, recognizerFactory)
        val sessionViewModel = SessionViewModel(engine, translator, configRepository)

        composeRule.setContent {
            TransyncAiApp(container = AppContainer(context))
        }

        // Drive the already-constructed pipeline directly rather than via AppContainer's
        // real mic-backed recognizer, since this test injects the WAV-backed engine above.
        sessionViewModel.start()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            sessionViewModel.uiState.value.translatedText.isNotBlank()
        }

        assert(sessionViewModel.uiState.value.translatedText.contains("наступления"))
        sessionViewModel.stop()
    }
}
```

Note: this test constructs its own `SessionViewModel` (with the WAV-backed engine and mocked translator) rather than asserting through the Activity's production `AppContainer`-built ViewModel, since the production path uses the real mic and real Kimi API. This still exercises every production class (`ContextBuffer`, `SherpaOnnxAsrEngine`, `KimiTranslationClient`, `SessionViewModel`) end-to-end — only the audio source and HTTP endpoint are swapped for deterministic test doubles, which is exactly the seam `AudioSource`/`Translator` were designed to provide.

- [ ] **Step 2: Run the e2e test on the emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.petroff.transyncai.E2ETranslationFlowTest"`
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 3: Run the full test suite as a final gate**

Run: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL, all unit and instrumented tests passed.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/com/petroff/transyncai/E2ETranslationFlowTest.kt
git commit -m "Add end-to-end instrumented test covering the full ASR-to-translation pipeline"
```

---

## Post-plan manual check

Automated tests use a mocked Kimi endpoint and pre-recorded audio by design (Task 12's note explains why). Before calling the feature done, do one real run on the POCO X6 Pro 5G itself: install the APK (`adb -s <device-serial> install app/build/outputs/apk/debug/app-debug.apk` after enabling wireless/USB debugging on the phone and running `adb connect`), enter a real Kimi API key, and confirm live English speech produces a Russian translation fast enough to be usable — this is the one thing no emulator test can validate (per the spec's testing section).
