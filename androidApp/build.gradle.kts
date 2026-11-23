plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.bionicwookiee.g7timing"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bionicwookiee.g7timing"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    // Camera and Data Matrix reader for scanning applicator codes (both Apache 2.0, open source).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.cpp)
}
