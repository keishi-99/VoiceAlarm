pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // Daemon JVM criteria (gradle/gradle-daemon-jvm.properties) に合う JDK が
    // ローカルで見つからない場合に、自動で取得できるようにするためのプラグイン。
    // バージョン 0.8.0 は Gradle 8.x 系向け（1.0.0 以降は Gradle 9 が必要）。
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VoiceAlarm"
include(":app")
