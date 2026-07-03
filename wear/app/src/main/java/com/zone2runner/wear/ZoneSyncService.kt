package com.zone2runner.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * 폰 → 워치 존 경계 수신 (Data Layer `/zones`, payload = "rhr,maxHr,z2lo,z2hi" CSV).
 * WearableListenerService라 앱이 꺼져 있어도 수신/저장된다.
 * 폰 측 송신: app/ ZoneSync.push() — 프로필 저장/홈 진입 시 푸시.
 */
class ZoneSyncService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_ZONES) return
        val parts = String(event.data).split(",").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size == 4) {
            Zones.save(this, maxHrIn = parts[1], lo = parts[2], hi = parts[3])
        }
    }

    private companion object { const val PATH_ZONES = "/zones" }
}
