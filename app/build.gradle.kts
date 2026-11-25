plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.droidweb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.droidweb"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // FIXED debug keystore (app/debug.keystore, standard debug credentials).
    // Without this, Gradle generates a NEW debug keystore on every CI run,
    // so each APK has a different signature and Android refuses an in-place
    // update (INSTALL_FAILED_UPDATE_INCOMPATIBLE) — forcing an uninstall
    // that WIPES SharedPreferences (password + tunnel token). With one
    // keystore, `adb install -r` updates in place and settings survive.
    // This is a DEBUG key only (not a release key); a release build must
    // use a proper secret keystore, never this one.
    signingConfigs {
        // Configure the built-in debug config (it already exists — do
        // NOT create("debug")). Point it at the fixed keystore so every
        // CI build signs with the same key.
        getByName("debug") {
            storeFile = file("debug.keystore")
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Embedded HTTP server (NanoHTTPD core only — no websocket module needed;
    // frames go over MJPEG, input over POST).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
