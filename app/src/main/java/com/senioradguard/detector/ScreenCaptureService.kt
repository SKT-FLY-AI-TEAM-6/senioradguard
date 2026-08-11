package com.senioradguard.detector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * ScreenCaptureService
 *
 * MainActivity에서 사용자가 MediaProjection 권한을 허용하면 그 결과(resultCode + data)를
 * 받아 여기서 MediaProjection을 열고, VirtualDisplay를 대기 상태로 띄워둔다.
 * 실제 프레임 캡처는 ScreenCaptureHelper.getLatestFrame()이 필요할 때만 수행한다.
 *
 * Android 10+ 는 MediaProjection 사용에 포그라운드 서비스가 필수이고,
 * Android 14+ 는 foregroundServiceType="mediaProjection" 선언 + startForeground를
 * getMediaProjection() 호출보다 먼저 실행해야 한다.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtraCompat(EXTRA_RESULT_DATA) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                ScreenCaptureHelper.detach()
                stopSelf()
            }
        }, null)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = projection.createVirtualDisplay(
            "SeniorAdGuardCapture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        ) ?: run {
            imageReader.close()
            projection.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        ScreenCaptureHelper.attach(projection, imageReader, virtualDisplay)

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "screen_capture_channel"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "광고 감지 화면 분석",
                NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SeniorAdGuard 실행 중")
            .setContentText("광고 의심 화면을 분석하기 위해 대기 중입니다")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenCaptureHelper.detach()
        mediaProjection = null
    }

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 1001

        private fun Intent.getParcelableExtraCompat(key: String): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(key, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(key)
            }
    }
}
