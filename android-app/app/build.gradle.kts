plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "nl.thuis.babyfoon"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.thuis.babyfoon"
        minSdk = 29          // Android 10 of nieuwer
        targetSdk = 35
        versionCode = 4
        versionName = "1.4.0"
    }

    // Vaste sleutel in de repo, zodat elke build over de vorige heen
    // geïnstalleerd kan worden (ook builds van GitHub Actions).
    // Let op: deze sleutel is openbaar; prima voor een hobby-app die je zelf installeert.
    signingConfigs {
        getByName("debug") {
            storeFile = file("babyfoon-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Camera's: RTSP-stream
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Wachtwoorden versleuteld opslaan
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
