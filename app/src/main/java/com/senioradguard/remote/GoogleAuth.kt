package com.senioradguard.remote

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.senioradguard.BuildConfig
import kotlinx.coroutines.tasks.await

/**
 * Google 계정으로 로그인한다.
 *
 * `GoogleSignInClient`는 폐기됐다. 후속은 Credential Manager이고, 구글 계정을
 * 고르는 시트를 시스템이 띄운다 — 우리가 계정 목록을 다루지 않으므로 어르신이
 * 비밀번호를 입력할 일도 없다(기기에 이미 로그인된 계정을 고르기만 한다).
 *
 * ## 콘솔 설정이 없으면 실패한다
 * Google 로그인은 Firebase 콘솔에서 **Google 제공업체를 켜고**, 앱의 **SHA-1
 * 지문을 등록**하고, 그 뒤 **google-services.json을 다시 받아야** 동작한다.
 * 셋 중 하나라도 빠지면 토큰 발급이 실패한다. 실패해도 광고 감지는 그대로
 * 돌고 가족 연동만 안 되도록 두었다 — 로그인 못 했다고 보호를 멈추면 안 된다.
 */
object GoogleAuth {

    private const val TAG = "AdGuardAuth"

    val isSignedIn: Boolean
        get() = runCatching { FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false)

    /**
     * 계정 선택 시트를 띄우고 Firebase에 로그인한다.
     *
     * @param context **액티비티 컨텍스트여야 한다.** 시스템이 그 위에 시트를 올린다.
     * @return 로그인된 사용자의 uid, 실패하면 null.
     */
    suspend fun signIn(context: Context): String? {
        val serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (serverClientId.isBlank()) {
            Log.e(TAG, "웹 클라이언트 ID가 비어 있다 — google-services.json을 다시 받아야 한다")
            return null
        }

        return runCatching {
            // 먼저 이미 등록된 계정만 보여준다. 없으면 아래에서 전체 계정으로 다시 묻는다 —
            // 처음 쓰는 기기에서 "계정 없음"으로 끝나버리면 사용자가 할 수 있는 게 없다.
            val credential = requestCredential(context, serverClientId, filterByAuthorized = true)
                ?: requestCredential(context, serverClientId, filterByAuthorized = false)
                ?: return null

            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val authCredential = GoogleAuthProvider.getCredential(idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(authCredential).await().user?.uid
        }.getOrElse {
            Log.e(TAG, "구글 로그인 실패: ${it.message}")
            null
        }
    }

    private suspend fun requestCredential(
        context: Context,
        serverClientId: String,
        filterByAuthorized: Boolean
    ) = runCatching {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorized)
            .setServerClientId(serverClientId)
            .build()

        CredentialManager.create(context)
            .getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
            .credential
    }.getOrNull()

    fun signOut() {
        runCatching { FirebaseAuth.getInstance().signOut() }
    }
}
