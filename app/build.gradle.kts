plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nova"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nova.zenfone"
        minSdk = 24          // Android 7.0 Nougat (ASUS ZenFone 3 Max ZC520TL)
        targetSdk = 35
        // Vosk (wake word) ships native code; the ZenFone (MT6737T) is ARM.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        versionCode = 3
        versionName = "1.0.0"
    }

    signingConfigs {
        // A fixed, committed DEBUG key so every build (CI, Android Studio, any PC) has the
        // same signature and each new version installs over the previous one.
        // Not secret, not for Play Store. A private release key comes in Stage 10.
        getByName("debug") {
            storeFile = file("nova-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric downloads its Android runtime at test time; use the same mirror.
            it.systemProperty("robolectric.dependency.repo.url",
                "https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Offline keyword spotting for the optional "Hey NØVA" wake word.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    // Renders the real views to PNGs on the JVM (no emulator needed) for visual checks.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
