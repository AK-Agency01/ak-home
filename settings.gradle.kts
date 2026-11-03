pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android (the ADB client of the phone installer) is published on JitPack only
        maven { url = uri("https://jitpack.io"); content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") } }
    }
}

rootProject.name = "AK-Home"
include(":app")
include(":installer")
