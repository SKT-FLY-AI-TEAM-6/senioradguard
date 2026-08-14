package com.senioradguard.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senioradguard.MainActivity
import com.senioradguard.remote.Role
import com.senioradguard.ui.theme.SeniorAdGuardTheme

/**
 * 최초 실행 시 역할을 고르는 화면.
 *
 * 넷플릭스 프로필 선택처럼 큰 카드 두 장만 보여준다. 노인 사용자가 쓰는 앱이라
 * 설명을 길게 두지 않고, 카드 자체를 화면 절반 크기로 키워 잘못 누를 여지를 줄였다.
 *
 * 이 화면은 카카오 로그인 화면을 대체한 것이다. 카카오 연동은 비즈 앱 검수
 * 승인이 나지 않아 보류됐고, 보호자 연결은 Firebase로 옮겼다.
 */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SeniorAdGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    RolePicker(
                        modifier = Modifier.padding(padding),
                        onPick = ::choose
                    )
                }
            }
        }
    }

    /**
     * 역할을 고르면 곧바로 다음 화면으로 보낸다. **로그인은 여기서 하지 않는다.**
     *
     * 앱을 처음 여는 사람에게 계정 선택 시트부터 들이밀면, 무엇을 하는 앱인지도
     * 모르는 채 로그인을 요구받는다. 가족 연결이 실제로 필요한 지점(보호자의
     * "가족 만들기", 어르신의 "코드 입력")에서 그때 묻는다. 로그인을 안 해도
     * 광고 감지는 그대로 돈다.
     */
    private fun choose(role: Role) {
        getSharedPreferences("adguard_prefs", MODE_PRIVATE)
            .edit().putString("role", role.wire).apply()

        val next = if (role == Role.SENIOR) MainActivity::class.java else GuardianActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}

@Composable
private fun RolePicker(modifier: Modifier = Modifier, onPick: (Role) -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "누가 사용하나요?",
            fontSize = 30.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            text = "한 번만 고르면 됩니다.",
            fontSize = 18.sp,
            color = Color(0xFF5D4037),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        RoleCard(
            emoji = "👵",
            title = "어르신 모드",
            subtitle = "이 휴대폰의 광고를 지켜봅니다",
            color = Color(0xFFFF7043),
            onClick = { onPick(Role.SENIOR) }
        )
        RoleCard(
            emoji = "👨‍👩‍👦",
            title = "보호자 모드",
            subtitle = "부모님 폰의 광고 내역을 봅니다",
            color = Color(0xFF42A5F5),
            onClick = { onPick(Role.GUARDIAN) }
        )
    }
}

@Composable
private fun RoleCard(
    emoji: String,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = emoji, fontSize = 64.sp)
            Text(text = title, fontSize = 30.sp, color = Color.White)
            Text(
                text = subtitle,
                fontSize = 17.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 보호자 화면 상단에 쓰는 작은 행 컴포넌트 — GuardianActivity와 공유한다. */
@Composable
internal fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 16.sp, color = Color(0xFF5D4037))
        Text(text = value, fontSize = 16.sp)
    }
}
