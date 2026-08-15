package com.senioradguard.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourceGuardTest {

    private val guard = InstallSourceGuard()

    @Test
    fun `시스템 인스톨러 패키지만 설치 화면으로 본다`() {
        assertTrue(guard.isInstallerScreen("com.google.android.packageinstaller"))
        assertTrue(guard.isInstallerScreen("com.android.packageinstaller"))
        assertTrue(guard.isInstallerScreen("com.samsung.android.packageinstaller"))

        assertFalse(guard.isInstallerScreen("com.android.chrome"))
        assertFalse(guard.isInstallerScreen("com.android.vending"))
        assertFalse(guard.isInstallerScreen(null))
    }

    @Test
    fun `브라우저에서 바로 뜬 설치 화면은 DBD다`() {
        assertTrue(guard.isDirectDownloadInstall("com.android.chrome"))
        assertTrue(guard.isDirectDownloadInstall("com.sec.android.app.sbrowser"))
        assertTrue(guard.isDirectDownloadInstall("com.samsung.android.messaging"))
    }

    @Test
    fun `스토어에서 넘어온 설치 화면은 정상 흐름이다`() {
        assertFalse(guard.isDirectDownloadInstall("com.android.vending"))
        assertFalse(guard.isDirectDownloadInstall("com.sec.android.app.samsungapps"))
    }

    @Test
    fun `직전 화면을 모르면 DBD로 본다 - 모르는 설치를 통과시키지 않는다`() {
        assertTrue(guard.isDirectDownloadInstall(null))
    }
}
