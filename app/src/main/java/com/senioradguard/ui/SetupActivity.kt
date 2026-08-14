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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.senioradguard.remote.FamilyRepo
import com.senioradguard.remote.GoogleAuth
import com.senioradguard.remote.InviteCode
import com.senioradguard.remote.Role
import kotlinx.coroutines.launch
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
                    var picked by remember { mutableStateOf<Role?>(null) }

                    when (val role = picked) {
                        null -> RolePicker(
                            modifier = Modifier.padding(padding),
                            onPick = { picked = it; saveRole(it) }
                        )
                        else -> FamilySetup(
                            role = role,
                            modifier = Modifier.padding(padding),
                            onDone = { goNext(role) }
                        )
                    }
                }
            }
        }
    }

    private fun saveRole(role: Role) {
        getSharedPreferences("adguard_prefs", MODE_PRIVATE)
            .edit().putString("role", role.wire).apply()
    }

    private fun goNext(role: Role) {
        val next = if (role == Role.SENIOR) MainActivity::class.java else GuardianActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}

/**
 * 역할을 고른 직후의 가족 연결 단계.
 *
 * 보호자는 가족을 만들어 6자리 코드를 받고, 어르신은 그 코드를 넣는다. 여기서
 * 처음으로 구글 로그인을 묻는다 — 가족은 사람 단위라 계정이 있어야 기기를 바꿔도
 * 연결이 유지된다.
 *
 * **건너뛸 수 있다.** 연결하지 않아도 광고 감지는 그대로 돈다. 로그인이 안 된다고
 * 보호까지 멈추면, 계정이 없는 어르신은 앱을 아예 못 쓰게 된다.
 */
@Composable
private fun FamilySetup(role: Role, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf(if (role == Role.GUARDIAN) InviteCode.generate() else "") }
    var input by remember { mutableStateOf("") }
    var created by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            if (role == Role.GUARDIAN) "보호자 연결" else "보호자와 연결",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        if (role == Role.GUARDIAN) {
            if (created) {
                Text("어르신 폰에 이 숫자를 넣어주세요.", fontSize = 19.sp)
                Text(
                    code,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF1976D2)
                )
            } else {
                Text(
                    "가족을 만들면 6자리 숫자가 나옵니다.\n" +
                        "그 숫자를 어르신 폰에 넣으면 연결됩니다.",
                    fontSize = 19.sp
                )
                Button(
                    onClick = {
                        busy = true; message = null
                        scope.launch {
                            val uid = if (GoogleAuth.isSignedIn) FamilyRepo.uid()
                            else GoogleAuth.signIn(context)
                            when {
                                uid == null -> message = "구글 로그인에 실패했습니다."
                                FamilyRepo.createFamily(context, code) == null ->
                                    message = "가족을 만들지 못했습니다. 네트워크를 확인해주세요."
                                else -> created = true
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text(if (busy) "만드는 중…" else "가족 만들기", fontSize = 22.sp) }
            }
        } else {
            Text("보호자가 불러준 6자리 숫자를 넣어주세요.", fontSize = 19.sp)
            OutlinedTextField(
                value = input,
                onValueChange = { input = InviteCode.normalize(it).take(InviteCode.LENGTH) },
                label = { Text("연결 숫자 6자리") },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 28.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    busy = true; message = null
                    scope.launch {
                        val uid = if (GoogleAuth.isSignedIn) FamilyRepo.uid()
                        else GoogleAuth.signIn(context)
                        when {
                            uid == null -> message = "구글 로그인에 실패했습니다."
                            !FamilyRepo.joinFamily(context, input) ->
                                message = "그런 숫자를 가진 가족이 없습니다."
                            else -> onDone()
                        }
                        busy = false
                    }
                },
                enabled = !busy && InviteCode.isValid(input),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) { Text(if (busy) "연결 중…" else "연결하기", fontSize = 22.sp) }
        }

        message?.let { Text(it, fontSize = 17.sp, color = Color(0xFFC62828)) }

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9E9E9E))
        ) {
            Text(
                if (created) "다 됐어요" else "나중에 하기",
                fontSize = 19.sp
            )
        }
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
