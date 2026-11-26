plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dddylan.slick"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dddylan.slick"
        minSdk = 29          // Android 10: needed for playback capture
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "16").toInt() + 1
        versionName = rootProject.file("release.txt").readText().trim()
    }

    // A fixed debug key checked into the repo, so every new build installs over the last one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// No libraries: the shell uses only the Android platform, so it stays small and builds fast.
