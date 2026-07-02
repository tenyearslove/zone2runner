plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zone2runner.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zone2runner.wear"
        minSdk = 30 // Wear OS 4/5 (Galaxy Watch 8)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    implementation("com.google.guava:guava:33.3.1-android") // ListenableFuture
    implementation("com.google.android.gms:play-services-location:21.3.0") // GPS pace/speed/distance
    implementation("com.google.android.gms:play-services-wearable:19.0.0") // Data Layer로 폰에 HR 송신
    implementation("androidx.wear:wear:1.3.0") // BoxInsetLayout
}
