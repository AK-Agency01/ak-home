import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.akhome.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.akhome.launcher"
        minSdk = 21          // old Chinese boxes
        targetSdk = 35
        // 1.0 = the launch version (user, 2026-10-30: everything renamed 1.0). The code keeps rising (5.1.0 was 2),
        // or boxes refuse it — and the self-update compares it. `-PvcTest=N` only for update tests on a box.
        // 1.1 (code 5, 2026-10-31) = the 1.0 memory-leak fix + the new card
        versionCode = project.findProperty("vcTest")?.toString()?.toInt() ?: 8
        versionName = "1.3.1"
        // where the self-update looks (Updater). `-PupdateUrl=http://127.0.0.1:8099/update.json` for tests only
        // (an emulator + `adb reverse`); plain http is then allowed, never in a normal build.
        val upd = project.findProperty("updateUrl")?.toString()
        buildConfigField("String", "UPDATE_URL", "\"${upd ?: "https://akagency.app/akhome/update.json"}\"")
        manifestPlaceholders["cleartext"] = (upd != null).toString()
    }

    // region: content + language.  device: TV box (no HDMI inputs) or screen/TV set (Inputs button).
    // Same applicationId for every edition: one AK Home per device, any APK updates the other.
    flavorDimensions += listOf("region", "device")
    productFlavors {
        create("global") {
            dimension = "region"
            versionNameSuffix = "-global"
            buildConfigField("String", "REGION", "\"global\"")
        }
        create("box") {
            dimension = "device"
            versionNameSuffix = "-tvbox"
            buildConfigField("boolean", "INPUTS", "false")
            buildConfigField("String", "EDITION", "\"TV Box\"")
        }
        create("screen") {
            dimension = "device"
            versionNameSuffix = "-screen"
            buildConfigField("boolean", "INPUTS", "true")
            buildConfigField("String", "EDITION", "\"Screens\"")
        }
    }

    // Release key: keystore.properties next to this project (never in git) — storeFile, storePassword, keyAlias,
    // keyPassword. Without it, release builds fall back to the local debug key (fine for trying it out).
    val ks = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (ks != null) create("release") {
            storeFile = rootProject.file(ks.getProperty("storeFile")); storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias"); keyPassword = ks.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// AK-Home-Global-TVBox-5.0.0.apk / AK-Home-Global-Screens-5.0.0.apk (debug builds get -debug)
@Suppress("DEPRECATION")
android.applicationVariants.all {
    val region = productFlavors.first { it.dimension == "region" }.name.replaceFirstChar { it.uppercase() }
    val device = if (productFlavors.any { it.name == "screen" }) "Screens" else "TVBox"
    val tag = if (buildType.name == "release") "" else "-${buildType.name}"
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "AK-Home-$region-$device-${android.defaultConfig.versionName}$tag.apk"
    }
}
