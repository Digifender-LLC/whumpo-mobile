import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Version scheme (docs/android/release-checklist.md): versionName comes from version.properties; versionCode is
// versionCodeBase + the CI run number (-Pwhumpo.runNumber=N, passed by the release job), so every CI upload is
// strictly higher than the last. A local build without the property gets versionCodeBase + 1.
// Read through a provider so the configuration cache notices edits to the file.
val versionProps = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("version.properties")).asText.get().reader())
}
val runNumber = providers.gradleProperty("whumpo.runNumber").map(String::toInt).getOrElse(1)

// Upload signing comes from the environment, never from the repo. Without all four values the release build
// is signed with the debug key and labelled "-debugsigned": Play rejects it, so it cannot be uploaded by mistake.
fun uploadEnv(name: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val uploadStore = uploadEnv("WHUMPO_UPLOAD_STORE_FILE")
val uploadSigned = listOf("WHUMPO_UPLOAD_STORE_PASSWORD", "WHUMPO_UPLOAD_KEY_ALIAS", "WHUMPO_UPLOAD_KEY_PASSWORD")
    .all { uploadEnv(it) != null } && uploadStore != null

android {
    namespace = "com.whumpo.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Final package identity, confirmed by the owner on 2026-10-29 (Q-005). It is permanent
        // once uploaded. This is not release authority: signing and Play actions stay with the owner.
        applicationId = "com.whumpo.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = versionProps.getProperty("versionCodeBase").toInt() + runNumber
        versionName = versionProps.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two environments only. `local` talks to the developer's own `pnpm dev` server over
    // device loopback (adb reverse); `deployed` targets the private test platform.
    // WhumpoEnvironment validates both values at startup.
    flavorDimensions += "environment"
    productFlavors {
        create("local") {
            dimension = "environment"
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            buildConfigField("String", "WHUMPO_ENVIRONMENT", "\"local\"")
            buildConfigField("String", "WHUMPO_ORIGIN", "\"http://127.0.0.1:8787\"")
        }
        create("deployed") {
            dimension = "environment"
            buildConfigField("String", "WHUMPO_ENVIRONMENT", "\"test\"")
            buildConfigField("String", "WHUMPO_ORIGIN", "\"https://whumpo.com\"")
        }
    }

    signingConfigs {
        if (uploadSigned) {
            create("upload") {
                storeFile = file(uploadStore!!)
                storePassword = uploadEnv("WHUMPO_UPLOAD_STORE_PASSWORD")
                keyAlias = uploadEnv("WHUMPO_UPLOAD_KEY_ALIAS")
                keyPassword = uploadEnv("WHUMPO_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadSigned) {
                signingConfig = signingConfigs.getByName("upload")
            } else {
                signingConfig = signingConfigs.getByName("debug")
                versionNameSuffix = "-debugsigned"
            }
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

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // Version-upgrade nags are not actionable for a pinned toolchain; upgrades are
        // deliberate (see docs/android-build.md) and these checks need network metadata.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

// AGP 9 compiles Kotlin itself (built-in Kotlin, no kotlin-android plugin):
// https://developer.android.com/build/migrate-to-built-in-kotlin
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.credentials)
    // Passkeys through Google Password Manager on Android 13 and older (API 28 to 33).
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
