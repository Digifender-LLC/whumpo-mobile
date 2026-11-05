// Kotlin Multiplatform module shared by the Android app (and later the desktop app).
// Targets: Android (library) and JVM (desktop). No iOS target until iOS is scoped.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidLibrary {
        namespace = "com.whumpo.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        // Robolectric needs merged resources and manifest (Roborazzi screenshots, WP3).
        withHostTest { isIncludeAndroidResources = true }
        // Packages Compose resources (the bundled fonts) for Android.
        androidResources { enable = true }
    }
    jvm()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        // shortcut: java.io/java.security code is shared by the two JVM-based targets through a
        // source directory instead of expect/actual; switch to expect/actual when a non-JVM target arrives.
        val jvmCommonMain = "src/jvmCommonMain/kotlin"
        val jvmCommonTest = "src/jvmCommonTest/kotlin"
        androidMain { kotlin.srcDir(jvmCommonMain) }
        jvmMain { kotlin.srcDir(jvmCommonMain) }
        getByName("androidHostTest") { kotlin.srcDir(jvmCommonTest) }
        jvmTest { kotlin.srcDir(jvmCommonTest) }

        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.animation.core)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.navigationevent.compose)
            api(libs.kotlinx.coroutines.core)
            api(libs.jetbrains.lifecycle.viewmodel)
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)
        }
        // One engine for both targets: OkHttp runs on Android and on the JVM desktop target.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // Photo preparation reads the EXIF orientation before the metadata is dropped.
            implementation(libs.androidx.exifinterface)
        }
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit4)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit4)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.ui.test.junit4)
            implementation(libs.robolectric)
            implementation(libs.roborazzi)
            implementation(libs.roborazzi.compose)
        }
    }
}

// Roborazzi without its Gradle plugin (the plugin does not target the KMP Android library host test):
// the library reads these system properties. Default is verify; -Pscreenshots.record rewrites the goldens in
// src/androidHostTest/screenshots (CI: the "Record screenshots" workflow input).
tasks.withType<Test>().configureEach {
    val record = providers.gradleProperty("screenshots.record").isPresent
    systemProperty(if (record) "roborazzi.test.record" else "roborazzi.test.verify", "true")
}

compose.resources {
    packageOfResClass = "com.whumpo.shared.resources"
    publicResClass = false
}
