package com.senioradguard.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.senioradguard.remote.FamilyEvent
import com.senioradguard.remote.FamilyRepo
import com.senioradguard.remote.GoogleAuth
import com.senioradguard.ui.theme.SeniorAdGuardTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 보호자 모드 화면. 가족을 만들고 어르신의 차단 내역을 본다.
 *
 * 이 모드에서는 접근성 서비스를 쓰지 않는다. 보호자 폰의 광고를 잡는 게 아니라
 * 어르신 폰이 남긴 기록을 구독해 읽기만 한다.
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

    var familyId by remember { mutableStateOf(FamilyRepo.savedFamilyId(context)) }
    val inviteCode = remember { FamilyRepo.savedInviteCode(context) }
    var events by remember { mutableStateOf<List<FamilyEvent>>(emptyList()) }

    DisposableEffect(familyId) {
        val id = familyId ?: return@DisposableEffect onDispose {}
        val unsubscribe = FamilyRepo.observeEvents(id) { events = it }
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
                    FamilyRepo.clearLocal(context)
                    GoogleAuth.signOut()
                    context.startActivity(
                        Intent(context, SetupActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575))
            ) { Text("모드 바꾸기", fontSize = 14.sp) }
        }

        if (familyId == null) {
            Notice(
                "아직 가족을 만들지 않았습니다.\n" +
                    "'모드 바꾸기'를 눌러 보호자 모드를 다시 고르면 만들 수 있어요."
            )
            return@Column
        }

        inviteCode?.let { code ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("어르신에게 불러주세요", fontSize = 16.sp, color = Color(0xFF555555))
                    Text(
                        code,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        LabeledRow("기록된 내역", "${events.size}건")

        if (events.isEmpty()) {
            Notice("아직 기록이 없습니다.\n어르신 폰에서 광고가 감지되면 여기에 바로 나타납니다.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(events, key = { it.eventId }) { EventCard(it) }
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
private fun EventCard(event: FamilyEvent) {
    // 위험 등급이 색을, 유형이 문구를 정한다. 등급만 보여주면 "무엇 때문에"가
    // 빠지고, 유형만 보여주면 "얼마나 급한지"가 빠진다.
    val (riskLabel, color) = when (event.riskLevel) {
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
            Text(if (event.count > 1) "$what ${event.count}건" else what, fontSize = 17.sp)
            Text(
                text = "${event.packageName.ifBlank { "알 수 없는 앱" }} · ${formatTime(event.timestamp)}",
                fontSize = 14.sp,
                color = Color(0xFF757575)
            )
        }
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "-"
    else SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(millis))
