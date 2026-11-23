plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.devingo.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Baseline Profiles only apply on API 28+; below that ART fully
        // AOT-compiles and a profile changes nothing.
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Anchors the benchmarkProfile + nonMinifiedProfile producer pair the
        // androidx.baselineprofile plugin creates per declared non-debug build
        // type, each testing the :app variant of the same name (the release-
        // shaped smoothness build). The plugin debug-signs the producer APKs.
        create("profile")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The app module this test APK generates profiles for / benchmarks.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
}
