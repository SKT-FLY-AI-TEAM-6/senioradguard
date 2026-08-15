import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// local.properties에서 키를 읽어 BuildConfig에 주입 (버전 관리 제외)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * google-services 플러그인은 google-services.json이 없으면 빌드를 실패시킨다.
 * 팀원이 파일 없이도 빌드할 수 있어야 하므로 파일이 있을 때만 적용한다.
 * 파일이 없으면 Firebase가 초기화되지 않고, FirebaseRepo가 그 상태를 감지해
 * 모든 호출을 무시한다(앱은 정상 동작, 원격 기록만 안 됨).
 */
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("google-services.json이 없어 Firebase를 비활성화한 채 빌드합니다.")
}

// Layer 2 판별용 Gemini 키. 비어 있으면 앱이 StubClassifier로 물러나므로,
// 키가 없는 팀원도 빌드와 실행에는 문제가 없다.
//
// ⚠️ 이건 개발용 경로다. APK에 들어간 문자열은 누구나 추출할 수 있으므로
// 배포 전에는 반드시 우리 서버를 거치는 구현으로 바꿔야 한다 (AdClassifier가 교체점).
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY", "")

// 온디바이스 LLM vs Claude API 속도·품질 비교 실험용 키 (개발 전용 그림자 경로).
// 비어 있으면 비교 코드는 완전히 잠들고, 제품 판정은 항상 온디바이스가 한다 —
// 기획 원칙(외부 LLM API 미사용)은 유지된다. ClaudeApiJudge 참고.
val claudeApiKey: String = localProperties.getProperty("CLAUDE_API_KEY", "")

android {
    namespace = "com.senioradguard"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.senioradguard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "CLAUDE_API_KEY", "\"$claudeApiKey\"")
        buildConfigField("Boolean", "HAS_FIREBASE", "$hasGoogleServices")
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
    packaging {
        // anthropic-java의 전이 의존성(httpclient5 등)이 같은 경로의 라이선스
        // 메타파일을 여럿 갖고 있어 병합이 실패한다 — 실행에 불필요하므로 제외.
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.coroutines.test)
    // serp 단위 테스트 전용. android.jar의 org.json은 전부 예외를 던지는 껍데기라
    // 응답 파싱을 JVM에서 검증하려면 진짜 구현이 클래스패스에 있어야 한다.
    // APK에는 들어가지 않는다 — 기기에는 플랫폼 org.json이 이미 있다.
    testImplementation("org.json:json:20250107")

    // Firebase — 보호자 모드 실시간 동기화 (google-services.json이 있을 때만 실제 동작)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.database.ktx)
    implementation(libs.firebase.messaging.ktx)

    // 코루틴
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // 온디바이스 LLM (4c) — MediaPipe LLM Inference. 모델 파일은 APK에 넣지 않고
    // 개발 중에는 adb push로 /data/local/tmp/guardian_llm.task 에 둔다 (~1.6GB).
    // 모델이 없으면 규칙 기반 판정만 동작한다 (OnDeviceLlm.isAvailable 참고).
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // Claude API — 온디바이스 LLM과의 속도·품질 비교 실험 전용 (ClaudeApiJudge).
    // CLAUDE_API_KEY가 비어 있으면 어떤 요청도 나가지 않는다.
    implementation("com.anthropic:anthropic-java:2.34.0")

    // Room (블랙리스트 로컬 DB)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager (블랙리스트 주 1회 업데이트)
    implementation(libs.androidx.work.runtime.ktx)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}