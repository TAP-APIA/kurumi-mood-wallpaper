plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kurumi.moodwallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kurumi.moodwallpaper"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    // Same key as all previous releases (Android debug keystore), so updates install in place.
    // Path override: -PkurumiKeystore=... or env KURUMI_KEYSTORE. Never commit the keystore.
    val kurumiKeystore = file(
        (project.findProperty("kurumiKeystore") as String?)
            ?: System.getenv("KURUMI_KEYSTORE")
            ?: "/workspace/kurumi/signing/kurumi-debug.keystore"
    )
    signingConfigs {
        if (kurumiKeystore.exists()) {
            create("kurumi") {
                storeFile = kurumiKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("kurumi")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
