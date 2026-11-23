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
        // bettertext / hugeicons ship via JitPack (com.github.* coordinates).
        maven("https://jitpack.io")
    }
}
rootProject.name = "DevinGo"
include(":app")
include(":baselineprofile")
