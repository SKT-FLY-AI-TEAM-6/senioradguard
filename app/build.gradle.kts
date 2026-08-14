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

/**
 * Google 로그인에 필요한 웹 클라이언트 ID (oauth_client의 client_type 3).
 *
 * google-services.json에서 뽑는다. 콘솔에서 Google 제공업체를 켜고 SHA-1을
 * 등록해야 이 항목이 생기므로, 없으면 빈 문자열이 되고 앱은 로그인 없이
 * (원격 기능만 꺼진 채) 돌아간다.
 */
val googleWebClientId: String = run {
    val json = file("google-services.json")
    if (!json.exists()) return@run ""
    val text = json.readText()
    // client_type 3(웹)인 oauth_client 항목의 client_id를 찾는다.
    // JSON 파서를 끌어오지 않으려고 문자열로 훑는다 — 이 파일 형식은 고정이다.
    val marker = "\"client_type\": 3"
    val at = text.indexOf(marker)
    if (at < 0) return@run ""
    val idKey = "\"client_id\": \""
    val start = text.lastIndexOf(idKey, at)
    if (start < 0) return@run ""
    val from = start + idKey.length
    text.substring(from, text.indexOf('"', from))
}
if (googleWebClientId.isEmpty()) {
    logger.warn("google-services.json에 웹 클라이언트 ID가 없습니다 — Google 로그인이 동작하지 않습니다.")
}

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
        buildConfigField("Boolean", "HAS_FIREBASE", "$hasGoogleServices")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
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

    // Firebase — 보호자 모드 실시간 동기화 (google-services.json이 있을 때만 실제 동작)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    // 온디바이스 한국어 OCR. 화면 픽셀이 기기 밖으로 나가지 않는다.
    implementation(libs.mlkit.text.korean)
    implementation(libs.firebase.database.ktx)
    implementation(libs.firebase.firestore.ktx)
    // Google 로그인 — GoogleSignInClient는 폐기됐고 Credential Manager가 후속이다
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)
    implementation(libs.firebase.messaging.ktx)

    // 코루틴
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Room (블랙리스트 로컬 DB)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager (블랙리스트 주 1회 업데이트)
    implementation(libs.androidx.work.runtime.ktx)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}