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
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import com.senioradguard.remote.FamilyRepo
import com.senioradguard.remote.GoogleAuth
import com.senioradguard.remote.InviteCode
import com.senioradguard.risk.ProtectionLevel
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
        // TODO Phase 3 — 원격 갱신 워커는 계속 꺼둔다.
        // 14만 개를 받아 파싱하고 DB를 통째로 갈아끼우는 작업이라 배터리를 눈에 띄게
        // 쓰고, 그게 제조사 절전에 이 앱이 얼어붙는 원인이기도 했다. 초기 목록은
        // assets/blacklist.txt를 첫 실행에 넣는 것으로 대신한다 —
        // 삽입은 화면 수명에 묶이면 안 되므로 SeniorAdGuardApp에서 돈다.
        // BlacklistUpdateWorker.schedule(applicationContext)

        // 역할을 아직 안 골랐으면 선택 화면으로, 보호자면 대시보드로 넘긴다.
        // 이 화면(어르신 모드)은 접근성 서비스 상태를 다루므로 보호자에게는 의미가 없다.
        when (FamilyRepo.savedRole(this)) {
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

    // 스크롤이 없으면 카드가 늘어난 만큼 아래 항목이 화면 밖으로 밀려 아예
    // 누를 수 없게 된다. 실제로 보호 강도·가족 연결·모드 바꾸기가 그렇게 사라졌다.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                ProtectionLevelCard()
            }
        }

        // 어떤 상태든 아래 두 가지는 항상 보여준다. 이게 없으면 UI만으로는
        // 보호자를 연결할 수도, 역할을 바꿔볼 수도 없다.
        FamilyJoinCard()
        ChangeRoleButton()
    }
}

/**
 * 보호 강도 1/2/3. 숫자가 클수록 더 많이 개입한다.
 *
 * 지금은 이 기기에서 직접 고르지만, 원래 자리는 보호자 화면이다 — 어르신이
 * 스스로 낮추면 보호가 의미를 잃는다. 원격 동기화는 가족 계정 구조가 들어온
 * 뒤에 붙이고, 그전까지 동작을 확인할 수 있게 여기 둔다.
 */
@Composable
private fun ProtectionLevelCard() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    }
    var level by remember {
        mutableStateOf(
            ProtectionLevel.of(
                prefs.getInt(
                    AdGuardAccessibilityService.PREF_PROTECTION_LEVEL,
                    ProtectionLevel.DEFAULT.value
                )
            )
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F1F1))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("보호 강도", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            for (option in ProtectionLevel.entries) {
                val label = when (option) {
                    ProtectionLevel.LABELS_ONLY -> "1단계 — '광고'라고 적힌 것만"
                    ProtectionLevel.WITH_AI -> "2단계 — 광고 같은 것까지 (기본)"
                    ProtectionLevel.WITH_URL_BLOCK -> "3단계 — 위험 사이트도 차단"
                }
                Button(
                    onClick = {
                        prefs.edit()
                            .putInt(
                                AdGuardAccessibilityService.PREF_PROTECTION_LEVEL,
                                option.value
                            )
                            .apply()
                        level = option
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor =
                            if (option == level) Color(0xFF1976D2) else Color(0xFFBDBDBD)
                    )
                ) { Text(label, fontSize = 16.sp) }
            }
        }
    }
}

/**
 * 보호자가 불러준 6자리 코드를 넣어 가족에 들어간다.
 *
 * 이 화면에서 처음으로 로그인을 묻는다. 앱을 켜자마자 계정을 요구하면 무엇을
 * 하는 앱인지도 모르는 채 로그인부터 하게 된다. 연결을 실제로 원하는 시점에
 * 묻고, 연결하지 않아도 광고 감지는 그대로 돈다.
 */
@Composable
private fun FamilyJoinCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var familyId by remember { mutableStateOf(FamilyRepo.savedFamilyId(context)) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F1F1))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (familyId != null) {
                Text("보호자와 연결됨", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("광고를 발견하면 보호자에게 알려드립니다.", fontSize = 16.sp)
                return@Column
            }

            Text("보호자 연결", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("보호자가 불러준 6자리 숫자를 넣어주세요.", fontSize = 16.sp)

            OutlinedTextField(
                value = input,
                onValueChange = { input = InviteCode.normalize(it).take(InviteCode.LENGTH) },
                label = { Text("연결 숫자 6자리") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            message?.let { Text(it, fontSize = 15.sp, color = Color(0xFFC62828)) }

            Button(
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val uid = if (GoogleAuth.isSignedIn) FamilyRepo.uid()
                        else GoogleAuth.signIn(context)

                        when {
                            uid == null ->
                                message = "구글 로그인에 실패했습니다."
                            !FamilyRepo.joinFamily(context, input) ->
                                message = "그런 숫자를 가진 가족이 없습니다. 다시 확인해주세요."
                            else ->
                                familyId = FamilyRepo.savedFamilyId(context)
                        }
                        busy = false
                    }
                },
                enabled = !busy && InviteCode.isValid(input),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) { Text(if (busy) "연결 중…" else "연결하기", fontSize = 20.sp) }
        }
    }
}

@Composable
private fun ChangeRoleButton() {
    val context = LocalContext.current
    Button(
        onClick = {
            FamilyRepo.clearLocal(context); GoogleAuth.signOut()
            context.startActivity(
                Intent(context, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575))
    ) { Text("모드 바꾸기", fontSize = 17.sp) }
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
