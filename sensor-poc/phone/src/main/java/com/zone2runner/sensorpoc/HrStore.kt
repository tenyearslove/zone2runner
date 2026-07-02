package com.zone2runner.sensorpoc

import android.os.Handler
import android.os.Looper

/**
 * HrReceiverService(백그라운드 수신)와 PhoneActivity(UI) 사이의 공유 상태.
 * WearableListenerService는 앱이 백그라운드/종료 상태여도 시스템이 깨워 호출하므로,
 * UI는 이 스토어의 최신 값만 렌더한다.
 */
object HrStore {
    @Volatile var hr = -1
    @Volatile var lastAt = 0L      // SystemClock.elapsedRealtime
    @Volatile var count = 0

    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun update(bpm: Int, atElapsed: Long) {
        hr = bpm; lastAt = atElapsed; count++
        notifyUi()
    }

    fun notifyUi() {
        val l = listener ?: return
        main.post(l)
    }
}
