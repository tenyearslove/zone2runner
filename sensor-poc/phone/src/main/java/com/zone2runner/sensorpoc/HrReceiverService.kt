package com.zone2runner.sensorpoc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * 워치가 보낸 HR(/hr)을 백그라운드에서 수신한다.
 *
 * WearableListenerService는 Data Layer 메시지가 도착하면 앱이 꺼져 있어도 시스템이 깨워
 * onMessageReceived를 호출한다 → 폰 Activity가 포그라운드가 아니어도 수신 지속(adr-009).
 * 수신 사실을 알림으로 보여 백그라운드 동작을 증빙하고, HrStore로 UI에도 반영.
 */
class HrReceiverService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/hr") return
        val bpm = String(event.data).toIntOrNull() ?: return
        HrStore.update(bpm, SystemClock.elapsedRealtime())
        showNotification(bpm)
    }

    private fun showNotification(bpm: Int) {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "HR 수신", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("워치 HR 수신 (백그라운드)")
            .setContentText("$bpm bpm · 총 ${HrStore.count}회")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOnlyAlertOnce(true)
            .build()
        mgr.notify(NOTIF_ID, n)
    }

    private companion object {
        const val CHANNEL = "hr_receive"
        const val NOTIF_ID = 2001
    }
}
