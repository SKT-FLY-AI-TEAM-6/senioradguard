package com.senioradguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senioradguard.detector.BlacklistUpdateWorker
import com.senioradguard.notification.KakaoNotifier
import com.senioradguard.ui.BatteryOptimizationGuide
import com.senioradguard.ui.SetupActivity
import com.senioradguard.ui.theme.SeniorAdGuardTheme

class MainActivity : ComponentActivity() {

    /** 배터리 예외 상태. 설정 화면에서 허용하고 돌아오면 onResume이 갱신한다. */
    private var batteryExempt by mutableStateOf(true)

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
                    HomeScreen(
                        batteryExempt = batteryExempt,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        batteryExempt = BatteryOptimizationGuide.isExempt(this)
    }
}

@Composable
private fun HomeScreen(batteryExempt: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "광고 지킴이",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        if (batteryExempt) {
            Text(
                text = "광고를 지켜보고 있어요.",
                fontSize = 20.sp
            )
        } else {
            BatteryWarningCard(
                onFixClick = { BatteryOptimizationGuide.requestExemption(context) }
            )
        }
    }
}

/**
 * 배터리 최적화 예외가 없을 때만 표시하는 경고.
 *
 * 노인 사용자 기준: 큰 글씨, 버튼 하나, 빨간 경고색. 기술 용어 대신
 * "잠재웁니다 / 못 잡습니다"처럼 결과로 설명한다.
 */
@Composable
private fun BatteryWarningCard(onFixClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚠️ 광고 감시가 멈출 수 있어요",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828)
            )
            Text(
                text = "휴대폰이 배터리를 아끼려고 이 앱을 잠재웁니다.\n" +
                    "그러면 광고가 나와도 알려드리지 못합니다.\n\n" +
                    "아래 버튼을 누르고 '허용'을 선택해주세요.",
                fontSize = 19.sp,
                color = Color(0xFF3E2723)
            )
            Button(
                onClick = onFixClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) {
                Text(
                    text = "배터리 설정 허용하기",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
