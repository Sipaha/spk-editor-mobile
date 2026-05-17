plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.sipaha.spkremote.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.sipaha.spkremote.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Markdown rendering for assistant bubbles. The library pins a Compose
    // runtime version under the hood; keep this in step with the Compose
    // BoM above when bumping. 0.27.0 is the last release that ships an
    // Android target without forcing a Compose 1.7+ jump.
    //
    // We do NOT pull in Coil here. The lib's ImageTransformer hook lets us
    // hand it pre-decoded Painters from EntrySummary.images, so async
    // network/disk loaders are unnecessary — every image we render is
    // already a base64 blob carried inline on the wire.
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
}
