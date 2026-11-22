plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val apiBaseUrl = providers.gradleProperty("btmap.apiBaseUrl").getOrElse("http://127.0.0.1:3001")

// Release signing comes from ~/.gradle/gradle.properties (never from the repository):
// BTMAP_STORE_FILE, BTMAP_STORE_PASSWORD, BTMAP_KEY_ALIAS, BTMAP_KEY_PASSWORD.
val releaseStoreFile = providers.gradleProperty("BTMAP_STORE_FILE").orNull

android {
    namespace = "pl.btmap.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.btmap.mobile"
        minSdk = 31
        targetSdk = 35
        versionCode = 10
        versionName = "0.1.0-beta.9"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = providers.gradleProperty("BTMAP_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("BTMAP_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("BTMAP_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.osmdroid)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
