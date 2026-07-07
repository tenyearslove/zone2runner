package com.zone2runner.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * 폰 → 워치 러닝 원격 제어 수신 (Data Layer /run/start, /run/stop).
 * 폰에서 러닝을 시작하면 워치 RunService(포그라운드)를 자동 기동한다 — 사용자가 워치를 만지지
 * 않아도 측정/전송이 시작된다(사용자 요청). 앱 미실행이어도 GMS가 이 서비스를 깨워 전달.
 *
 * 백그라운드 포그라운드서비스 시작이 OS(Android 12+)에 막히면(ForegroundServiceStartNotAllowed)
 * 풀스크린 알림으로 폴백 — 워치를 들면 러닝 화면이 바로 뜬다.
 */
class RunControlService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        // 표시 판정(/run/live)은 1Hz라 로그 제외. 나머지 제어 메시지만 로깅.
        if (event.path == RunLink.PATH_LIVE) {
            val p = String(event.data).split(",").mapNotNull { it.trim().toIntOrNull() }
            if (p.size == 3) { RunBus.setLive(p[0], p[1], p[2]); RunBus.notifyUi() } // 순간심박,존인덱스,존내위치(adr-023)
            return
        }
        // 토크테스트 설문 표시 명령(adr-023: 타이밍 판단은 폰 — 워치는 표시만)
        if (event.path == RunLink.PATH_TALK) {
            if (!TalkTestActivity.active) {
                try {
                    startActivity(Intent(this, TalkTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (t: Throwable) { /* BG 액티비티 제한 시 이번 설문은 건너뜀(놓쳐도 무해, 폰이 다시 판단) */ }
            }
            return
        }
        // 폰에서 답함 → 워치에 열린 설문 닫기(중복 응답 방지)
        if (event.path == RunLink.PATH_TALK_DONE) { TalkTestActivity.closeCurrent(); return }
        android.util.Log.i("RunControl", "recv ${event.path} state=${RunBus.state}")
        when (event.path) {
            RunLink.PATH_START -> {
                if (RunBus.state != RunState.IDLE) return // 이미 진행 중이면 무시(루프/중복 방지)
                // 심박 권한이 없으면 서비스가 측정을 못 함 → 앱을 열어 권한 받도록 알림 유도
                val hasBody = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.BODY_SENSORS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasBody) { postLaunchNotification(); return }
                val i = Intent(this, RunService::class.java)
                    .setAction(RunService.ACTION_START)
                    .putExtra(RunService.EXTRA_FROM_REMOTE, true)
                try {
                    ContextCompat.startForegroundService(this, i)
                } catch (t: Throwable) {
                    postLaunchNotification() // BG 시작 제한 → 사용자가 화면 켜면 뜨는 알림
                    return
                }
                // 서비스만 뜨면 화면이 안 올라온다 → 러닝 화면도 전면에. BG 액티비티 시작이 막히면 알림 폴백.
                try {
                    startActivity(
                        Intent(this, WearRunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) {
                    postLaunchNotification()
                }
            }
            RunLink.PATH_MIRROR -> {
                // 시뮬 미러: 워치는 자기 센서를 안 쓰고 폰 심박을 받아 표시+토크테스트만. RunService 시작 안 함.
                try {
                    startActivity(
                        Intent(this, WearRunActivity::class.java)
                            .putExtra(WearRunActivity.EXTRA_MIRROR, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) { postLaunchNotification() }
            }
            RunLink.PATH_STOP -> {
                // 실센서면 RunService 종료, 미러면 RunBus만 IDLE로(서비스 없음)
                if (RunBus.state != RunState.IDLE) { RunBus.state = RunState.IDLE; RunBus.notifyUi() }
                startService(
                    Intent(this, RunService::class.java)
                        .setAction(RunService.ACTION_STOP)
                        .putExtra(RunService.EXTRA_FROM_REMOTE, true)
                )
            }
        }
    }

    private fun postLaunchNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "러닝 시작", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, WearRunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("폰에서 러닝 시작됨")
            .setContentText("탭하여 워치 러닝 시작")
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
        const val NOTIF_ID = 2002
    }
}
