import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// local.properties에서 키를 읽어 BuildConfig에 주입 (버전 관리 제외)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Gemini 광고 판별 키. 비어 있으면 앱이 StubClassifier로 물러나므로 키 없이도 빌드된다.
// ⚠️ 개발용 경로다. APK는 누구나 뜯을 수 있어 이 방식으로는 키가 그대로 노출된다.
//    배포 전에는 우리 서버를 거치는 AdClassifier 구현체로 교체할 것.
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY", "")

android {
    namespace = "com.guradian"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.guradian"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// main(린 버전)에서 더 걷어낸 것: Room · WorkManager · 블랙리스트 다운로드.
// DB는 task 4의 몫이고, 이 브랜치는 store/ 인터페이스 뒤에 자리만 남긴다.
// Gemini는 HttpURLConnection으로 직접 부르므로 HTTP 클라이언트도 필요 없다.
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 단위 테스트 전용. android.jar의 org.json은 전부 예외를 던지는 껍데기라
    // 응답 파싱을 JVM에서 검증하려면 진짜 구현이 클래스패스에 있어야 한다.
    // APK에는 들어가지 않는다 — 기기에는 플랫폼 org.json이 이미 있다.
    testImplementation("org.json:json:20250107")
}
