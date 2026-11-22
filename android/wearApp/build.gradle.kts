plugins {
    alias(libs.plugins.android.application)
}

val fixture = providers.gradleProperty("whumpo.wearTileFixture").orNull.orEmpty()
require(fixture in setOf("", "fresh", "empty", "expired")) { "Unknown Wear Tile fixture" }
fun uploadEnv(name: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val uploadStore = uploadEnv("WHUMPO_UPLOAD_STORE_FILE")
val uploadSigned = listOf("WHUMPO_UPLOAD_STORE_PASSWORD", "WHUMPO_UPLOAD_KEY_ALIAS", "WHUMPO_UPLOAD_KEY_PASSWORD")
    .all { uploadEnv(it) != null } && uploadStore != null

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("local") {
            dimension = "environment"
            applicationIdSuffix = ".local"
        }
        create("deployed") {
            dimension = "environment"
        }
    }

    buildFeatures { buildConfig = true }

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
        debug {
            applicationIdSuffix = if (fixture.isEmpty()) "" else ".wearfixture"
            buildConfigField("String", "TILE_FIXTURE", "\"$fixture\"")
        }
        release {
            if (uploadSigned) {
                signingConfig = signingConfigs.getByName("upload")
            } else {
                signingConfig = signingConfigs.getByName("debug")
                versionNameSuffix = "-debugsigned"
            }
        }
    }

}

dependencies {
    implementation(project(":shared"))
    implementation(libs.play.services.wearable)
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    testImplementation(kotlin("test-junit", libs.versions.kotlin.get()))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
