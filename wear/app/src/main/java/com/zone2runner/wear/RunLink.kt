package com.zone2runner.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable

/**
 * 러닝 시작/종료를 상대 기기(폰)로 알리는 Data Layer 송신 헬퍼 (adr-009 연동).
 * 매 호출마다 연결 노드를 새로 조회해 전송 — 캐시 미스로 시작 신호를 놓치지 않게.
 * 경로: /run/start, /run/stop (payload 없음).
 */
object RunLink {
    const val PATH_START = "/run/start"
    const val PATH_STOP = "/run/stop"
    const val PATH_MIRROR = "/run/mirror"      // 폰 시뮬 → 워치 미러 모드
    const val PATH_MIRROR_HR = "/run/mirrorhr" // 폰 시뮬 심박 스트림(payload=bpm)
    const val PATH_LIVE = "/run/live"          // 폰 → 워치 표시 판정("순간심박,존인덱스,존내위치" CSV, adr-023)
    const val PATH_TALK = "/run/talk"          // 폰 → 워치 토크테스트 설문 표시 명령(adr-023)
    const val PATH_TALK_DONE = "/run/talkdone" // 폰에서 답함 → 열린 설문 닫기(중복 응답 방지)

    fun send(ctx: Context, path: String) {
        val app = ctx.applicationContext
        Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
            val mc = Wearable.getMessageClient(app)
            for (n in nodes) runCatching { mc.sendMessage(n.id, path, ByteArray(0)) }
        }
    }
}
