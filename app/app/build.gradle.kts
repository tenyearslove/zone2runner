plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zone2runner.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zone2runner.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true } // android.graphics.Color 등 stub 기본값 반환
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.osmdroid:osmdroid-android:6.1.18") // 지도(OSM, API 키 불필요 — adr-010)
    implementation("com.google.android.gms:play-services-location:21.3.0") // 실기기 GPS
    implementation("com.google.android.gms:play-services-wearable:19.0.0") // 워치 DataLayer HR 수신
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2") // 온디바이스 LLM 코칭(Gemini Nano, adr-007)
    implementation("com.google.mlkit:genai-rewriting:1.0.0-beta1") // 코칭 문장 톤 재작성(Gemini Nano, adr-026)
    implementation("com.google.mlkit:genai-summarization:1.0.0-beta1") // 세션 리포트 요약(Gemini Nano, adr-026)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // org.json JVM 구현(단위 테스트에서 실제 파싱)
}
