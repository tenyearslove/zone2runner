package com.zone2runner.app.sensor

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

/**
 * 워치 심박 공급자 — Galaxy Watch가 Data Layer로 보내는 HR을 수신(sensor-poc 프로토콜 재사용).
 * 워치 측(HrService)이 `sendMessage(node, "/hr", bpm.toString().toByteArray())`로 1Hz 전송.
 * 여기서는 MessageClient 리스너로 최신 HR을 붙잡아 둔다. staleMs 이상 미수신 시 -1(무효).
 */
class WatchHrProvider(context: Context) : HrProvider {

    private val client: MessageClient = Wearable.getMessageClient(context.applicationContext)
    @Volatile private var hr = -1
    @Volatile private var lastMs = 0L
    @Volatile private var spm = -1
    @Volatile private var spmMs = 0L
    private val staleMs = 8000L

    private val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        when (event.path) {
            PATH_HR -> runCatching { String(event.data).trim().toInt() }.getOrNull()?.let {
                if (it in 30..240) { hr = it; lastMs = SystemClock.elapsedRealtime() }
            }
            PATH_SPM -> runCatching { String(event.data).trim().toInt() }.getOrNull()?.let {
                if (it in 60..260) { spm = it; spmMs = SystemClock.elapsedRealtime() }
            }
        }
    }

    override fun start() { runCatching { client.addListener(listener) } }

    override fun latestHr(): Int =
        if (hr > 0 && SystemClock.elapsedRealtime() - lastMs <= staleMs) hr else -1

    /** 마지막 /hr 수신 후 경과(ms). 수신 이력 없으면 -1. 필드 로그(spec-012) 끊김 분석용. */
    fun lastAgeMs(): Long = if (lastMs == 0L) -1L else SystemClock.elapsedRealtime() - lastMs

    /** 워치 실측 케이던스(spm). 미수신/오래됨(-1)이면 호출부가 페이스 기반 추정으로 폴백. */
    fun latestSpm(): Int =
        if (spm > 0 && SystemClock.elapsedRealtime() - spmMs <= staleMs) spm else -1

    override fun stop() { runCatching { client.removeListener(listener) } }

    override val sourceLabel = "워치HR"

    private companion object {
        const val PATH_HR = "/hr"
        const val PATH_SPM = "/spm"
    }
}
