plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = providers.gradleProperty("versionName").orElse("1.1.0").get()
val appVersionParts = appVersionName.split(".").map { it.toIntOrNull() ?: -1 }
require(appVersionParts.size == 3 && appVersionParts.all { it in 0..99 }) {
    "versionName must use MAJOR.MINOR.PATCH with numeric values from 0 to 99."
}
val appVersionCode = appVersionParts.fold(0) { code, part -> code * 100 + part }

android {
    namespace = "com.elfaris.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.elfaris.watch"
        minSdk = 21
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }

        buildConfigField("String", "APP_CHANNEL", "\"github\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(System.getenv("ANDROID_KEYSTORE_PATH") ?: "app/release-key.jks")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.08")
}
