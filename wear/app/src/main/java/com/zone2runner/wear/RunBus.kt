package com.zone2runner.wear

import android.os.Handler
import android.os.Looper

enum class RunState { IDLE, RUNNING, PAUSED }

/**
 * RunService(포그라운드 서비스)와 WearRunActivity(UI) 사이의 경량 공유 상태
 * (sensor-poc HrBus 패턴, adr-009). 측정/누적은 서비스가 소유하고 UI는 렌더만 한다 —
 * 화면이 꺼지거나 액티비티가 죽어도 세션이 유지되는 것이 목적.
 */
object RunBus {
    @Volatile var state = RunState.IDLE
    @Volatile var hr = -1
    @Volatile var distanceM = 0.0
    @Volatile var speedKmh = 0.0
    @Volatile var accumulatedMs = 0L   // PAUSED까지의 누적 경과
    @Volatile var runStart = 0L        // RUNNING 구간 시작(elapsedRealtime)
    @Volatile var sentCount = 0        // 폰으로 보낸 /hr 메시지 수(연결 진단용)
    @Volatile var availability = "-"
    @Volatile var error: String? = null

    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun notifyUi() {
        val l = listener ?: return
        main.post(l)
    }

    fun reset() {
        hr = -1; distanceM = 0.0; speedKmh = 0.0
        accumulatedMs = 0L; runStart = 0L
        sentCount = 0; availability = "-"; error = null
    }
}
