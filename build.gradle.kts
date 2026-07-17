// 用 buildscript + classpath 方式,比 plugins{} DSL 更稳
buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
    }
    dependencies {
        // AGP 8.7.3:支持 compileSdk 36,且与 Gradle 8.9(wrapper 钉死)兼容。
        // 不升到 8.8.x —— 8.8 要求 Gradle 8.10.2+,会触发 wrapper 升级,改动面过大。
        // Lint 检查会提示 "Compose 1.9.0 需 AGP 8.8.2+",但 lint 已 abortOnError=false。
        classpath("com.android.tools.build:gradle:8.7.3")
        // Kotlin 2.1.20:与 Wear Compose 1.6.2 POM 声明的 kotlin-stdlib 2.1.20 对齐,
        // 避免 Compose Compiler 与 stdlib 版本漂移警告。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20")
    }
}
