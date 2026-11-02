plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.takahirom.roborazzi")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.blake.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blake.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            // A fixed keystore (committed below) instead of each machine/CI run's own
            // ~/.android/debug.keystore — otherwise every CI-built APK gets a different
            // signature and can't install over the last one without an uninstall first.
            storeFile = file("ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    // Provides ComponentActivity.lifecycleScope, used for the foreground health ping (P2.4)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Navigation Compose: pre-approved in ROADMAP-NEXTGEN.md for the bottom-nav shell (P1.3)
    implementation("androidx.navigation:navigation-compose:2.7.7")
    // Coil: standard, lightweight image loader — needed to preview a picked photo (from a
    // content:// Uri) before upload, without hand-rolling Bitmap decode/EXIF/memory handling.
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Room + WorkManager: pre-approved in ROADMAP-NEXTGEN.md for the offline-first data
    // layer (P2) — local cache the UI reads from, plus a durable outbox for queued writes.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Home-screen widget (P7). Glance is the Compose-style widget API named in ROADMAP-NEXTGEN.md.
    implementation("androidx.glance:glance-appwidget:1.1.0")

    // Screenshot testing (pre-approved in ROADMAP-NEXTGEN.md): JVM-based via Robolectric, no
    // emulator needed in CI.
    testImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
