package com.senioradguard

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.senioradguard.detector.BlacklistUpdateWorker
import com.senioradguard.detector.ScreenCaptureService
import com.senioradguard.notification.KakaoNotifier
import com.senioradguard.ui.SetupActivity
import com.senioradguard.ui.theme.SeniorAdGuardTheme

class MainActivity : ComponentActivity() {

    // 광고 감지 시 화면을 재검사할 수 있도록 앱 시작 시 1회 화면 캡처 권한을 요청한다.
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BlacklistUpdateWorker.schedule(applicationContext)

        // 최초 실행(보호자 미설정) 시 카카오 로그인/친구 선택 설정 화면으로 이동
        if (!KakaoNotifier(this).isGuardianSet()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        // 화면 재구성(회전 등)이 아닌 진짜 최초 실행일 때만 1회 요청
        if (savedInstanceState == null) {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        setContent {
            SeniorAdGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SeniorAdGuardTheme {
        Greeting("Android")
    }
}
