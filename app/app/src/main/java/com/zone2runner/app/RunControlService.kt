package com.zone2runner.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * 워치 → 폰 러닝 원격 제어 수신 (Data Layer /run/start, /run/stop).
 *
 * 폰의 러닝 파이프라인은 Activity(GPS + 포그라운드 전용 LLM 코칭)라 백그라운드에서 바로 띄울 수 없다
 * — 최신 안드로이드는 백그라운드 액티비티 시작을 막는다. 그래서 워치에서 시작하면 폰에서는
 * 풀스크린 인텐트 알림으로 "러닝 시작" 카드를 띄우고, 사용자가 탭하면 실센서 러닝이 열린다
 * ("포그라운드로 안 되면 알림으로라도" — 사용자 요청 취지의 플랫폼 한계 내 최선).
 * 워치 종료(/run/stop)는 폰 러닝 화면에 종료를 요청한다.
 */
class RunControlService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            RunLink.PATH_START -> if (!RunActivity.isRunning) postLaunchNotification()
            RunLink.PATH_STOP -> RunActivity.remoteStopRequested = true
        }
    }

    private fun postLaunchNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "러닝 시작", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val launch = Intent(this, RunActivity::class.java)
            .putExtra(RunActivity.EXTRA_MODE, RunActivity.MODE_LIVE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("워치에서 러닝 시작됨")
            .setContentText("탭하여 폰에서 실센서 러닝 열기")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIF_ID, n)
    }

    private companion object {
        const val CHANNEL = "run_control"
        const val NOTIF_ID = 3002
    }
}
