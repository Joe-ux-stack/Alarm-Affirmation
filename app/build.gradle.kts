plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase (push messages that wake the phone when the AI changes something). Only when the config file exists.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.whynotyou.alarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whynotyou.alarm"
        minSdk = 26
        targetSdk = 35
        versionCode = 63
        versionName = "0.63"
        ndk { abiFilters += listOf("arm64-v8a") }
        // Google sign-in: the Web OAuth client ID (public). CI passes it from the GOOGLE_CLIENT_ID repo variable.
        buildConfigField("String", "WEB_CLIENT_ID", "\"${System.getenv("GOOGLE_CLIENT_ID") ?: ""}\"")
    }

    // Stable dev key: CI decodes it from a repo secret into keystore/whynotyou-dev.jks.
    // If it's absent (local builds without the secret) we fall back to the throwaway debug key.
    val devKeystore = rootProject.file("keystore/whynotyou-dev.jks")
    val devPass = System.getenv("DEV_KEYSTORE_PASS") ?: "whynotyou"
    signingConfigs {
        create("dev") {
            storeFile = if (devKeystore.exists()) devKeystore else null
            storePassword = devPass
            keyAlias = "dev"
            keyPassword = devPass
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (devKeystore.exists()) signingConfigs.getByName("dev")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures { buildConfig = true }
    androidResources {
        noCompress += listOf("onnx", "bin")
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.firebase:firebase-messaging:24.1.0")
}
