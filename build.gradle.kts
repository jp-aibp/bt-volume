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
        versionCode = 3
        versionName = "1.2.0"

        // Conscrypt and SPAKE2 ship native code; almost all current phones are ARM.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    // One-time setup without a computer: pair with the phone's own Wireless debugging.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
