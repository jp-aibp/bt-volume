pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // libadb-android and its SPAKE2 dependency
        maven("https://jitpack.io")
    }
}
rootProject.name = "BtVolume"
