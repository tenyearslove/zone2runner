package com.zone2runner.app

import android.content.Context
import com.google.android.gms.wearable.Wearable

/**
 * 러닝 시작/종료를 워치로 알리는 Data Layer 송신 헬퍼 (워치 RunLink와 대칭).
 * 매 호출마다 연결 노드를 새로 조회 — 시작 신호를 캐시 미스로 놓치지 않게.
 * 경로: /run/start, /run/stop (payload 없음).
 */
object RunLink {
    const val PATH_START = "/run/start"
    const val PATH_STOP = "/run/stop"

    fun send(ctx: Context, path: String) {
        val app = ctx.applicationContext
        Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
            val mc = Wearable.getMessageClient(app)
            for (n in nodes) runCatching { mc.sendMessage(n.id, path, ByteArray(0)) }
        }
    }
}
