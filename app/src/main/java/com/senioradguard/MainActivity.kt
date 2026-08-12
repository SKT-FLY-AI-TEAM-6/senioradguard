package com.senioradguard

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senioradguard.service.AdGuardAccessibilityService
import com.senioradguard.ui.BatteryOptimizationGuide
import com.senioradguard.ui.ServiceStatus
import com.senioradguard.remote.FirebaseRepo
import com.senioradguard.remote.Role
import com.senioradguard.ui.GuardianActivity
import com.senioradguard.ui.SetupActivity
import com.senioradguard.ui.theme.SeniorAdGuardTheme

class MainActivity : ComponentActivity() {

    /** 배터리 예외 상태. 설정 화면에서 허용하고 돌아오면 onResume이 갱신한다. */
    private var batteryExempt by mutableStateOf(true)

    /** 접근성 서비스 상태. 설정 화면에서 켜고 돌아와도 onResume이 갱신한다. */
    private var serviceState by mutableStateOf(ServiceStatus.State.RUNNING)

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // TODO Phase 2 — 블랙리스트 워커 비활성화.
        // 주 1회 14만 개 도메인을 받아 DB에 쓰지만 읽는 코드가 없다(AdDetector는
        // 생성 지점이 없는 dead code). 다운로드·파싱·DB 전체 교체가 무거운데
        // 얻는 게 없고, 배터리는 이 앱이 제조사 절전에 얼어붙는 원인이기도 하다.
        // 파일(BlacklistUpdateWorker/BlacklistRepository)은 확장용으로 남겨둔다.
        // BlacklistUpdateWorker.schedule(applicationContext)

        // 역할을 아직 안 골랐으면 선택 화면으로, 보호자면 대시보드로 넘긴다.
        // 이 화면(어르신 모드)은 접근성 서비스 상태를 다루므로 보호자에게는 의미가 없다.
        when (FirebaseRepo.savedRole(this)) {
            null -> {
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
                return
            }
            Role.GUARDIAN -> {
                startActivity(Intent(this, GuardianActivity::class.java))
                finish()
                return
            }
            Role.SENIOR -> Unit   // 이 화면 그대로 사용
        }

        setContent {
            SeniorAdGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(
                        serviceState = serviceState,
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
        refreshServiceState()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 앱을 막 켠 직후에는 접근성 서비스가 아직 붙는 중일 수 있다. 그 짧은 순간을
     * "죽었다"고 잘못 알리지 않도록, 죽은 것으로 보이면 잠시 뒤 다시 확인한다.
     */
    private fun refreshServiceState() {
        handler.removeCallbacksAndMessages(null)
        serviceState = ServiceStatus.current(this)
        if (serviceState != ServiceStatus.State.ENABLED_BUT_DEAD) return

        listOf(1_500L, 3_000L).forEach { delay ->
            handler.postDelayed({ serviceState = ServiceStatus.current(this) }, delay)
        }
    }
}

@Composable
private fun HomeScreen(
    serviceState: ServiceStatus.State,
    batteryExempt: Boolean,
    modifier: Modifier = Modifier
) {
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

        // 서비스가 꺼져 있으면 배터리 안내보다 이게 먼저다 — 아예 감시가 없는 상태다.
        when (serviceState) {
            ServiceStatus.State.DISABLED -> WarningCard(
                title = "⚠️ 광고 감시가 꺼져 있어요",
                body = "지금은 광고가 나와도 알려드리지 못합니다.\n\n" +
                    "아래 버튼을 누른 뒤 목록에서 '광고 지킴이'를 찾아 켜주세요.",
                buttonText = "광고 감시 켜기",
                onClick = { ServiceStatus.openAccessibilitySettings(context) }
            )

            ServiceStatus.State.ENABLED_BUT_DEAD -> WarningCard(
                title = "⚠️ 광고 감시가 멈췄어요",
                body = "켜져 있다고 되어 있지만 실제로는 동작하지 않고 있습니다.\n\n" +
                    "아래 버튼을 누른 뒤 '광고 지킴이'를 껐다가 다시 켜주세요.",
                buttonText = "광고 감시 다시 켜기",
                onClick = { ServiceStatus.openAccessibilitySettings(context) }
            )

            ServiceStatus.State.RUNNING -> {
                if (batteryExempt) {
                    Text(text = "광고를 지켜보고 있어요.", fontSize = 20.sp)
                } else {
                    WarningCard(
                        title = "⚠️ 광고 감시가 멈출 수 있어요",
                        body = "휴대폰이 배터리를 아끼려고 이 앱을 잠재웁니다.\n" +
                            "그러면 광고가 나와도 알려드리지 못합니다.\n\n" +
                            "아래 버튼을 누르고 '허용'을 선택해주세요.",
                        buttonText = "배터리 설정 허용하기",
                        onClick = { BatteryOptimizationGuide.requestExemption(context) }
                    )
                }
                AiClassifyToggle()
            }
        }
    }
}

/**
 * Layer 2(AI 광고 판별) 옵트인 토글. 기본 OFF.
 *
 * 켜면 화면에 보이는 카드의 텍스트가 외부 판별 서버로 나간다. 사용자가 그 사실을
 * 알고 스스로 켜야 하므로 기본값을 끔으로 두고, 무엇이 나가는지 문구로 밝힌다.
 * Layer 1(공식 라벨)과 Layer 3(설치 차단)은 이 토글과 무관하게 항상 동작한다.
 */
@Composable
private fun AiClassifyToggle() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(AdGuardAccessibilityService.PREF_AI_CLASSIFY, false))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI 광고 판별",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "'광고'라고 적혀 있지 않은 광고도 찾아냅니다.\n" +
                        "켜면 화면의 글자가 판별 서버로 전송됩니다.",
                    fontSize = 16.sp,
                    color = Color(0xFF5D4037)
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit()
                        .putBoolean(AdGuardAccessibilityService.PREF_AI_CLASSIFY, it)
                        .apply()
                }
            )
        }
    }
}

/**
 * 보호가 끊긴 상태를 알리는 경고 카드.
 *
 * 노인 사용자 기준: 큰 글씨, 버튼 하나, 빨간 경고색. 기술 용어("접근성 서비스",
 * "배터리 최적화") 대신 "못 알려드립니다"처럼 결과로 설명한다.
 */
@Composable
private fun WarningCard(
    title: String,
    body: String,
    buttonText: String,
    onClick: () -> Unit
) {
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
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828)
            )
            Text(
                text = body,
                fontSize = 19.sp,
                color = Color(0xFF3E2723)
            )
            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) {
                Text(
                    text = buttonText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
