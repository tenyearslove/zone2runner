plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zone2runner.sensorpoc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zone2runner.sensorpoc" // Data Layer 연결 위해 phone과 동일
        minSdk = 30 // Wear OS 최신
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation("com.google.guava:guava:33.3.1-android") // ListenableFuture (health-services async 반환형)
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.wear:wear:1.3.0") // BoxInsetLayout: 원형 화면 안전영역 배치
}
