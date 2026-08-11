package com.senioradguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.senioradguard.detector.BlacklistUpdateWorker
import com.senioradguard.notification.KakaoNotifier
import com.senioradguard.ui.SetupActivity
import com.senioradguard.ui.theme.SeniorAdGuardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BlacklistUpdateWorker.schedule(applicationContext)

        // 최초 실행(보호자 미설정) 시 카카오 로그인/친구 선택 설정 화면으로 이동
        if (!KakaoNotifier(this).isGuardianSet()) {
            startActivity(Intent(this, SetupActivity::class.java))
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
