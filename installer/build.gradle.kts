import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// AK Home Installer: phone app that finds a TV / box on the same Wi-Fi, connects over wireless ADB
// (port 5555, or Android 11+ pairing), installs the chosen AK Home edition and makes it the home screen.
android {
    namespace = "com.akhome.installer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.akhome.installer"
        minSdk = 24          // phones; TLS pairing (Android 11+ TVs) works through the bundled Conscrypt
        targetSdk = 35
        versionCode = 17      // raise on every build sent to a phone, or it may refuse the "update"
        versionName = "1.1"   // carries AK Home 1.1; 17 = new TV icon + name "AK Home", 16 = launcher look, 15 = site look
        // phones only: no x86 parts (emulators / PCs) — saves 4.6 MB
        // (`-PallAbis` keeps x86 for emulator tests)
        ndk { abiFilters += if (project.hasProperty("allAbis")) listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") else listOf("arm64-v8a", "armeabi-v7a") }
    }

    // the AK Home editions travel inside the installer (assets/editions/*.apk)
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("editions-assets"))

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
    packaging { resources.excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module") }
    // R8 must not compress the APKs inside (they are streamed to the TV as they are)
    androidResources { noCompress += "apk" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // self-signed certificate for ADB TLS; same version as the bcprov libadb brings (mixed versions miss classes)
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}

// Copy the two release editions from :app into the installer's assets.
val bundleEditions by tasks.registering(Sync::class) {
    dependsOn(":app:assembleGlobalBoxRelease", ":app:assembleGlobalScreenRelease")
    from(rootProject.file("app/build/outputs/apk/globalBox/release")) { include("*.apk"); rename { "global-tvbox.apk" } }
    from(rootProject.file("app/build/outputs/apk/globalScreen/release")) { include("*.apk"); rename { "global-screens.apk" } }
    into(layout.buildDirectory.dir("editions-assets/editions"))
}

tasks.named("preBuild") { dependsOn(bundleEditions) }
