plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ivideonApiKey = providers.gradleProperty("ivideonApiKey").orElse("").get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.bladeblack88.ivideonfullscreen"
    compileSdk = 35
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        applicationId = "com.bladeblack88.ivideonfullscreen"
        minSdk = 21
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        buildConfigField("String", "IVIDEON_API_KEY", "\"$ivideonApiKey\"")
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
