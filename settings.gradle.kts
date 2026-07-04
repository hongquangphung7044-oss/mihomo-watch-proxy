pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 阿里云镜像作为 fallback(本地构建/网络不佳时备用)
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
    }
}
rootProject.name = "mihomo-watch-proxy"
include(":app")
