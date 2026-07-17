plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ys.proxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ys.proxy"
        // Galaxy Watch7 国行 Wear OS 5 = API 34。早期 Wear OS 3 = API 30
        minSdk = 30
        targetSdk = 34
        // versionCode 用 GitHub Actions run number 自动递增,本地构建默认 1
        // versionName 也带 build 号,方便用户报问题时定位构建版本
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        // 关键: 只打 32 位 armv7,适配三星手表
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    // 显式定义签名配置,keystore 提交到仓库内(非敏感),100% 保证签名一致可覆盖安装
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
            // release 也用 debug 签名,方便用户直接覆盖安装
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

    // 关键: AGP 7+ 默认关闭 aidl/buildConfig/compose,必须显式启用
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    // Kotlin 1.9.24 ↔ Compose Compiler 1.5.14(必须严格匹配)
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // 关键: 二进制 assets 不能被 AAPT 压缩,否则 AssetManager.open() 读出来的是压缩流,
    // cp 到 /data/local/tmp 后文件损坏(mihomo 二进制无法执行,mmdb 数据库无效)
    androidResources {
        noCompress += listOf("mihomo", "metadb", "dat", "mmdb")
    }

    // lint 检查放宽:手表项目部分规则不适用
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Wear OS Compose UI (注意 artifact 名: compose-material / compose-foundation, 不是 compose)
    implementation("androidx.wear.compose:compose-material3:1.5.6")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.7.7")
    implementation("androidx.compose.material:material:1.7.7")
    implementation("androidx.compose.foundation:foundation:1.7.7")

    // Wear OS Ongoing Activity API - 表盘底部"运行中"指示器(三星手表底部圆圈)
    implementation("androidx.wear:wear-ongoing:1.1.0")
    // OngoingActivity 依赖较新版本的 androidx.core
    implementation("androidx.core:core-ktx:1.13.1")

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
