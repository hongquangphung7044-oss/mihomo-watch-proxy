plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mihomo.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mihomo.watch"
        // Galaxy Watch7 国行 Wear OS 5 = API 34。早期 Wear OS 3 = API 30
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // 关键: 只打 32 位 armv7,适配三星手表
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 签名方便直接装,用户自己装正式签名可改
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // 内嵌 mihomo 二进制到 assets
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Wear OS Compose UI (注意 artifact 名: compose-material / compose-foundation, 不是 compose)
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.6.6")
    implementation("androidx.compose.material:material:1.6.6")
    implementation("androidx.compose.foundation:foundation:1.6.6")

    // 生命周期
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Shizuku - 通过 shell 权限执行命令,绕过 VpnService 限制
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // OkHttp 下载订阅
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // JSON 解析(mihomo API 返回)
    implementation("org.json:json:20240303")
}
