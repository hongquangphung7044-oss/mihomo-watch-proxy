plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ys.proxy"
    // compileSdk = 36: AndroidX Compose 1.9.0 系列(Wear Compose 1.6.2 传递依赖)
    // 要求编译期 SDK = 36(Android 16)。targetSdk 仍保持 34,运行时行为不变。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ys.proxy"
        minSdk = 30
        // Keep runtime behavior unchanged for the Watch7 release baseline.
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk { abiFilters += "armeabi-v7a" }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    sourceSets { getByName("main") { assets.srcDirs("src/main/assets") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    androidResources { noCompress += listOf("mihomo", "metadb", "dat", "mmdb") }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Wear Compose Material 3 1.6.2 —— 彻底重构后的主题系统基线。
    // 1.6.2 传递依赖 Compose UI/Foundation/Runtime 1.9.0 + kotlin-stdlib 2.1.20,
    // 故下方显式钉 1.9.0,与 Wear Compose 1.6.2 POM 对齐。
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-navigation:1.6.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.foundation:foundation:1.9.0")
    // 已删除 androidx.compose.material:material(Compose Material 2):
    // 全量迁移到 Wear Compose Material 3 后,源码内无任何 androidx.compose.material.* 引用。

    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.json:json:20240303")
}
