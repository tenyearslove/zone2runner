package com.zone2runner.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable

/**
 * 워치 -> 폰 심박 전달 (Data Layer). 폰의 WatchHrProvider가 `/hr` 경로로 수신한다.
 * sensor-poc의 HrService 프로토콜과 동일: payload = bpm 문자열 바이트.
 * 연결 노드는 캐시하고 비어 있으면 갱신(폰이 근처에 있을 때만 실제 전송됨).
 */
class HrForwarder(context: Context) {
    private val appCtx = context.applicationContext
    private val messageClient = Wearable.getMessageClient(appCtx)
    private val nodeClient = Wearable.getNodeClient(appCtx)
    @Volatile private var nodeIds: List<String> = emptyList()

    fun start() { refreshNodes() }

    private fun refreshNodes() {
        nodeClient.connectedNodes
            .addOnSuccessListener { list -> nodeIds = list.map { it.id } }
            .addOnFailureListener { nodeIds = emptyList() }
    }

    /** 최신 HR을 연결된 폰들로 전송(fire-and-forget). 노드 목록이 비면 갱신 시도. */
    fun send(bpm: Int) {
        if (bpm !in 30..240) return
        if (nodeIds.isEmpty()) { refreshNodes(); return }
        val data = bpm.toString().toByteArray()
        for (id in nodeIds) runCatching { messageClient.sendMessage(id, PATH_HR, data) }
    }

    private companion object { const val PATH_HR = "/hr" }
}
