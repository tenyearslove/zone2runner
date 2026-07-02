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
    private val staleMs = 8000L

    private val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path == PATH_HR) {
            runCatching { String(event.data).trim().toInt() }.getOrNull()?.let {
                if (it in 30..240) { hr = it; lastMs = SystemClock.elapsedRealtime() }
            }
        }
    }

    override fun start() { runCatching { client.addListener(listener) } }

    override fun latestHr(): Int =
        if (hr > 0 && SystemClock.elapsedRealtime() - lastMs <= staleMs) hr else -1

    override fun stop() { runCatching { client.removeListener(listener) } }

    override val sourceLabel = "워치HR"

    private companion object { const val PATH_HR = "/hr" }
}
