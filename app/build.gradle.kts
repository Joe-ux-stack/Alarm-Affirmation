plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whynotyou.alarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whynotyou.alarm"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "0.27"
        ndk { abiFilters += listOf("arm64-v8a") }
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
    androidResources {
        noCompress += listOf("onnx", "bin", "txt")
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
}
