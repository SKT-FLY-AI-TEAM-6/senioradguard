package com.senioradguard.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senioradguard.remote.AdEvent
import com.senioradguard.remote.FirebaseRepo
import com.senioradguard.ui.theme.SeniorAdGuardTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 보호자 모드 화면. 차단 내역만 보여준다.
 *
 * 이 모드에서는 접근성 서비스를 쓰지 않는다. 보호자 폰의 광고를 잡는 게 아니라
 * 어르신 폰이 Firebase에 남긴 기록을 구독해 읽기만 한다.
 */
class GuardianActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SeniorAdGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    GuardianScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun GuardianScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var partnerId by remember { mutableStateOf(FirebaseRepo.linkedTo(context).orEmpty()) }
    var input by remember { mutableStateOf("") }
    var events by remember { mutableStateOf<List<AdEvent>>(emptyList()) }

    // 연결된 어르신이 바뀌면 이전 구독을 끊고 새로 건다
    DisposableEffect(partnerId) {
        if (partnerId.isBlank()) return@DisposableEffect onDispose {}
        val unsubscribe = FirebaseRepo.observeEvents(partnerId) { events = it }
        onDispose { unsubscribe() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("보호자 화면", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Button(
                onClick = {
                    FirebaseRepo.clearRole(context)
                    context.startActivity(
                        android.content.Intent(context, SetupActivity::class.java)
                            .addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                    )
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF757575)
                )
            ) { Text("모드 바꾸기", fontSize = 14.sp) }
        }

        if (!FirebaseRepo.isConfigured) {
            Notice(
                "Firebase가 설정되지 않았습니다.\n" +
                    "app/google-services.json을 넣고 다시 빌드해야 내역이 들어옵니다."
            )
        }

        if (partnerId.isBlank()) {
            Text("어르신 폰의 연결 코드를 입력하세요.", fontSize = 17.sp)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("연결 코드") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val code = input.trim()
                    if (code.isNotEmpty()) {
                        FirebaseRepo.link(context, code)
                        partnerId = code
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) { Text("연결하기", fontSize = 20.sp) }
        } else {
            // 코드를 줄이면 안 된다. 보호자가 제대로 입력했는지 눈으로 확인할
            // 유일한 자리인데, take(12)로 자르니 16자리 실제 코드가 잘려 나가
            // 오타를 냈는지조차 알 수 없었다.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F1F1))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("연결된 어르신", fontSize = 15.sp, color = Color(0xFF757575))
                    Text(
                        partnerId,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            LabeledRow("기록된 내역", "${events.size}건")

            Button(
                onClick = {
                    FirebaseRepo.link(context, "")
                    partnerId = ""
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF9E9E9E)
                )
            ) { Text("연결 해제", fontSize = 16.sp) }

            if (events.isEmpty()) {
                Notice("아직 기록이 없습니다.\n어르신 폰에서 광고가 감지되면 여기에 바로 나타납니다.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(events, key = { it.eventId }) { EventCard(it) }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F1F1))
    ) {
        Text(text, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun EventCard(event: AdEvent) {
    // 위험 등급이 색을, 유형이 문구를 정한다. 등급만 보여주면 "무엇 때문에"가
    // 빠지고, 유형만 보여주면 "얼마나 급한지"가 빠진다.
    val (riskLabel, color) = when (event.risk) {
        "high" -> "위험" to Color(0xFFC62828)
        "medium" -> "주의" to Color(0xFFEF6C00)
        else -> "알림" to Color(0xFF616161)
    }
    val what = when (event.type) {
        "ad_labeled" -> "광고 표시"
        "ad_guessed" -> "광고로 추정"
        "blocked_domain" -> "위험 사이트 접속"
        "store_redirect" -> "앱 설치 화면 이동"
        "install_blocked" -> "앱 설치 시도"
        "ignored" -> "경고를 그냥 봄"
        else -> event.type.ifBlank { "알 수 없음" }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(riskLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
                Text(
                    if (event.blocked) "차단함" else "알림만",
                    fontSize = 14.sp,
                    color = Color(0xFF9E9E9E)
                )
            }
            Text(
                if (event.count > 1) "$what ${event.count}건" else what,
                fontSize = 17.sp
            )
            Text(
                text = "${event.appPackage.ifBlank { "알 수 없는 앱" }} · ${formatTime(event.timestamp)}",
                fontSize = 14.sp,
                color = Color(0xFF757575)
            )
        }
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "-"
    else SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(millis))
