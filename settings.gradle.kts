pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Web (WebAssembly) のビルドが Node.js などを取りに行くリポジトリを足すので、プロジェクト側の追加を許す
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android
        maven("https://jitpack.io")
    }
}

rootProject.name = "OBDMeter"
include(":app")
