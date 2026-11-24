import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: credentials live in signing.properties next to this file
// (gitignored); the release build falls back to unsigned if absent.
val signingProps = Properties().apply {
    val f = file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.devingo.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.devingo.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.2"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    if (!signingProps.getProperty("storeFile").isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Smoothness-check variant: release-shaped (minified) but signed with
        // the debug key so it installs over the debug build in place.
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    // Per-ABI APKs: the tailnet .so is ~23MB per ABI, so a single-ABI build
    // is ~23MB smaller. arm64-v8a for real phones, x86_64 for MuMu.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// One generated profile covers every variant (release and the release-shaped
// profile build): generation merges into src/main/generated/baselineProfiles
// and is invoked manually via :app:generateBaselineProfile.
baselineProfile {
    mergeIntoMain = true
}

// Stability allow-list in place of kotlinx-collections-immutable: every
// List/Map/Set handed to a composable here is a replaced snapshot, never
// mutated in place.
composeCompiler {
    stabilityConfigurationFiles.add(
        layout.projectDirectory.file("stability_config.conf"),
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.datastore.preferences)

    // RikkaHub-sourced rich text stack (AGPL — ported files keep their
    // package names under me.rerere.*).
    implementation(platform(libs.bettertext.bom))
    implementation(libs.bettertext.text)
    implementation(libs.bettertext.latex)
    implementation(libs.bettertext.markdown)
    implementation(libs.bettertext.highlight)
    implementation(libs.hugeicons.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // QR pairing (扫描电脑二维码连接 bridge). Bundled ML Kit model, no GMS.
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)

    // Installs the packaged baseline-prof.txt on first launch / update.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
}
