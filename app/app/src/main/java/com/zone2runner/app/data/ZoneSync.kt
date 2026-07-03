package com.zone2runner.app.data

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.zone2runner.app.domain.Zone2Prior

/**
 * 폰 → 워치 존 경계 동기화 (Data Layer `/zones`).
 * 워치의 경량 존 표시가 폰의 프로필 기반 Zone 2(adr-012 prior)와 같은 기준을 쓰도록
 * "rhr,maxHr,z2lo,z2hi" CSV를 연결된 워치에 푸시한다(fire-and-forget, 실패 무해).
 * 호출 시점: 홈 진입/프로필 저장 후(HomeActivity.onResume) — 워치는 ZoneSyncService가 수신/저장.
 */
object ZoneSync {

    fun push(ctx: Context) {
        val app = ctx.applicationContext
        val p = ProfileStore.load(app)
        val prior = Zone2Prior.of(p)
        val hi = (p.restingHr + prior.uFrac0 * p.hrr).toInt()
        val lo = (p.restingHr + (prior.uFrac0 - Zone2Prior.BAND) * p.hrr).toInt()
        val payload = "${p.restingHr},${p.maxHr},$lo,$hi".toByteArray()
        runCatching {
            Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
                val mc = Wearable.getMessageClient(app)
                for (n in nodes) runCatching { mc.sendMessage(n.id, PATH_ZONES, payload) }
            }
        }
    }

    private const val PATH_ZONES = "/zones"
}
