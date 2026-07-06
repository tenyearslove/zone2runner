package com.zone2runner.wear

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

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

    // B(adr-022): 폰이 매초 보내는 판정 상태. 워치는 자체 판정 없이 이 값으로만 표시(폰 연결 상시 전제).
    @Volatile var instHr = -1    // 폰이 표시하는 순간 심박(정제됨) = 큰 숫자(폰과 동일 값)
    @Volatile var susHr = -1     // 폰 지속 심박(60초 평균) = 존/판정 기준
    @Volatile var boundLo = -1   // 개인 Zone2 하한(bpm)
    @Volatile var boundHi = -1   // 개인 Zone2 상한(bpm)
    @Volatile var boundMax = -1  // 최대 심박(bpm)
    @Volatile var liveMs = 0L    // 마지막 /run/live 수신 시각(elapsedRealtime); 신선도 판단용

    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun notifyUi() {
        val l = listener ?: return
        main.post(l)
    }

    /** 폰 판정 상태 수신 반영(RunControlService). */
    fun setLive(inst: Int, sus: Int, lo: Int, hi: Int, max: Int) {
        instHr = inst; susHr = sus; boundLo = lo; boundHi = hi; boundMax = max
        liveMs = SystemClock.elapsedRealtime()
    }

    fun reset() {
        hr = -1; distanceM = 0.0; speedKmh = 0.0
        accumulatedMs = 0L; runStart = 0L
        sentCount = 0; availability = "-"; error = null
        instHr = -1; susHr = -1; boundLo = -1; boundHi = -1; boundMax = -1; liveMs = 0L
    }
}
