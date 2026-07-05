plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zone2runner.voicepoc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zone2runner.voicepoc" // Data Layer 연결 위해 wear와 동일
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // tflite 모델은 압축하면 mmap 불가 → 무압축 유지
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // 온디바이스 오디오 분류(YAMNet, AudioSet 521클래스: Breathing/Gasp/Pant 등)
    implementation("com.google.mediapipe:tasks-audio:0.10.14")
    testImplementation("junit:junit:4.13.2")
}
