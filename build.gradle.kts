import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1"
}

// Release signing key, kept outside the repository. Without it the release build is unsigned.
val signingFile = rootProject.file("../signing/keystore.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "eu.aibusinesspro.btvolume"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.aibusinesspro.btvolume"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signingFile.exists()) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
}
