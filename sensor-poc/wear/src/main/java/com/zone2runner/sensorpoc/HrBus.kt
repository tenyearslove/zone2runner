package com.zone2runner.sensorpoc

import android.os.Handler
import android.os.Looper

/**
 * HrService(포그라운드 서비스)와 WearActivity(UI) 사이의 경량 공유 상태.
 * 서비스가 백그라운드에서 돌기 때문에, UI는 이 버스를 구독해 현재 값만 렌더한다.
 */
object HrBus {
    @Volatile var running = false
    @Volatile var hr = -1
    @Volatile var availability = "-"
    @Volatile var sentCount = 0
    @Volatile var lastSentAt = 0L      // SystemClock.elapsedRealtime
    @Volatile var phoneConnected: Boolean? = null
    @Volatile var error: String? = null

    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun notifyUi() {
        val l = listener ?: return
        main.post(l)
    }

    fun reset() {
        hr = -1; availability = "-"; sentCount = 0; lastSentAt = 0L; error = null
    }
}
