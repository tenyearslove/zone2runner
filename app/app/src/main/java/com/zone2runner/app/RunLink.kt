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
    const val PATH_LIVE = "/run/live"       // 폰 판정 상태(지속 심박+개인 경계)를 워치로 매초 푸시(adr-022)

    fun send(ctx: Context, path: String, payload: ByteArray = ByteArray(0)) {
        val app = ctx.applicationContext
        Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
            val mc = Wearable.getMessageClient(app)
            for (n in nodes) runCatching { mc.sendMessage(n.id, path, payload) }
        }
    }

    fun sendMirrorHr(ctx: Context, bpm: Int) = send(ctx, PATH_MIRROR_HR, bpm.toString().toByteArray())

    /**
     * 폰 판정 상태를 워치로 전송 — 워치가 폰과 '같은' 심박 숫자와 존을 표시하게 한다(adr-022).
     * payload = "순간심박,지속심박,하한,상한,최대심박" CSV(bpm).
     * 순간심박 = 폰이 정제(OutlierGuard)해 표시하는 값(워치가 이걸 그대로 표시 → 숫자 일치),
     * 지속심박+경계 = 존 계산 기준.
     */
    fun sendLive(ctx: Context, instHr: Int, susHr: Int, lo: Int, hi: Int, maxHr: Int) =
        send(ctx, PATH_LIVE, "$instHr,$susHr,$lo,$hi,$maxHr".toByteArray())
}
