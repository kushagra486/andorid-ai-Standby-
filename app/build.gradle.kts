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
        versionCode = 1
        versionName = "0.1.0"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
