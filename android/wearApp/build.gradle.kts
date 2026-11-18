plugins {
    alias(libs.plugins.android.application)
}

val fixture = providers.gradleProperty("whumpo.wearTileFixture").orNull.orEmpty()
require(fixture in setOf("", "fresh", "empty", "expired")) { "Unknown Wear Tile fixture" }

android {
    namespace = "com.whumpo.wear"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.whumpo.android"
        minSdk = 30
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "TILE_FIXTURE", "\"\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            applicationIdSuffix = if (fixture.isEmpty()) ".local" else ".wearfixture"
            buildConfigField("String", "TILE_FIXTURE", "\"$fixture\"")
        }
    }
}

dependencies {
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    testImplementation(kotlin("test-junit", libs.versions.kotlin.get()))
}
