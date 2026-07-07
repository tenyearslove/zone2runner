package com.zone2runner.app

import android.content.Context
import com.google.android.gms.wearable.Wearable

/**
 * 러닝 시작/종료를 워치로 알리는 Data Layer 송신 헬퍼 (워치 RunLink와 대칭).
 * 매 호출마다 연결 노드를 새로 조회 — 시작 신호를 캐시 미스로 놓치지 않게.
 * 경로: /run/start, /run/stop (payload 없음).
 */
object RunLink {
    const val PATH_START = "/run/start"     // 실센서: 워치가 자기 ExerciseClient로 러닝
    const val PATH_STOP = "/run/stop"
    const val PATH_MIRROR = "/run/mirror"   // 시뮬: 워치를 미러 모드로(폰 심박 표시 + 토크테스트만)
    const val PATH_MIRROR_HR = "/run/mirrorhr" // 시뮬 심박 스트림(payload=bpm)
    const val PATH_LIVE = "/run/live"       // 폰이 확정한 표시 존을 워치로 매초 푸시(adr-023)
    const val PATH_TALK = "/run/talk"       // 폰 → 워치: 토크테스트 설문 표시 명령(adr-023, 타이밍 판단은 폰)
    const val PATH_TALK_DONE = "/run/talkdone" // 폰 → 워치: 폰에서 답함 → 열린 설문 닫기(중복 응답 방지)

    fun send(ctx: Context, path: String, payload: ByteArray = ByteArray(0)) {
        val app = ctx.applicationContext
        Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
            val mc = Wearable.getMessageClient(app)
            for (n in nodes) runCatching { mc.sendMessage(n.id, path, payload) }
        }
    }

    fun sendMirrorHr(ctx: Context, bpm: Int) = send(ctx, PATH_MIRROR_HR, bpm.toString().toByteArray())

    /**
     * 폰이 확정한 표시 판정을 워치로 전송(adr-023) — 워치는 계산 없이 이 값으로만 그린다.
     * payload = "순간심박,존인덱스(1~5),존내위치(0~1000)" CSV.
     * 순간심박 = 폰이 정제(OutlierGuard)해 표시하는 값(워치 큰 숫자와 일치),
     * 존 = 폰이 순간심박+히스테리시스로 확정(DisplayZoneJudge), 존내위치 = 등폭 게이지 마커용.
     */
    fun sendLive(ctx: Context, instHr: Int, zoneIdx: Int, zoneFracPerMille: Int) =
        send(ctx, PATH_LIVE, "$instHr,$zoneIdx,$zoneFracPerMille".toByteArray())
}
