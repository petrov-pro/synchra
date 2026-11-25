import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Firebase (crash reports) needs app/google-services.json from the Firebase console. Without it
// the app builds and runs as before, just without crash reports (FirebaseCrashReporter.createOrNoOp).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    // Uploads R8's mapping file, so release stack traces come back readable.
    apply(plugin = "com.google.firebase.crashlytics")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.petroff.synchra"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.petroff.synchra"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phones only: sherpa-onnx's native libraries are large, and Play ships each phone
        // just its own ABI from the bundle.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        buildConfigField(
            "String", "DEFAULT_API_BASE_URL",
            "\"${localProps.getProperty("synchra.apiBaseUrl", "https://api.anthropic.com")}\""
        )
    }

    // Upload key for Play, from local.properties (never committed):
    //   synchra.keystore=/path/to/upload.jks, synchra.keystorePassword, synchra.keyAlias, synchra.keyPassword
    // Without it a release build is signed with the debug key: fine for testing on a phone,
    // refused by Play.
    val uploadKeystore = localProps.getProperty("synchra.keystore")
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = localProps.getProperty("synchra.keystorePassword")
                keyAlias = localProps.getProperty("synchra.keyAlias")
                keyPassword = localProps.getProperty("synchra.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // The emulator for instrumented tests is x86_64.
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // android.util.Log (used for on-device debug logging) throws by default in plain
            // JVM unit tests since the Android framework isn't present there — make it a no-op
            // returning 0 instead, rather than avoiding Log calls in production code.
            isReturnDefaultValues = true
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

    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-crashlytics")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // On-device speech recognition (Free mode): https://github.com/k2-fsa/sherpa-onnx
    implementation(files("libs/sherpa-onnx-2.0.3.aar"))
    // The speech model comes as a .tar.bz2; WorkManager downloads it in the background.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // On-device translation (Free mode).
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.anthropic:anthropic-java:3.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")


    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.navigation:navigation-testing:2.8.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
