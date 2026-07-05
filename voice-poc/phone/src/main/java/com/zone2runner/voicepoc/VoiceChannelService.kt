package com.zone2runner.voicepoc

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.DataInputStream

/**
 * 워치가 ChannelClient로 보낸 낭독 오디오(/voice/audio)를 수신 → VAD 분석 → 5단계 판정 →
 * 결과를 워치로 회신(/voice/result). 판단(무거운 부분)은 폰에서 한다(사용자 요청).
 *
 * 스트림 포맷: int(sampleRate) + PCM16 LE 바이트. onChannelOpened는 백그라운드 스레드라 블로킹 IO 허용.
 */
class VoiceChannelService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != PATH_AUDIO) return
        val client = Wearable.getChannelClient(this)
        runCatching {
            val input = Tasks.await(client.getInputStream(channel))
            val samples: ShortArray
            val sampleRate: Int
            DataInputStream(input.buffered()).use { din ->
                sampleRate = din.readInt()
                val bytes = din.readBytes()
                samples = Pcm.fromLeBytes(bytes, bytes.size)
            }
            val metrics = VoiceAnalyzer.analyze(samples, sampleRate)
            val verdict = TalkJudge.judge(metrics, VoiceStore.baseline)
            VoiceStore.lastWatchVerdict = verdict
            VoiceStore.onChange?.invoke()
            sendResult(channel.nodeId, verdict)
        }
        runCatching { Tasks.await(client.close(channel)) }
    }

    private fun sendResult(nodeId: String, v: TalkVerdict) {
        // detail은 '%','→'를 포함하므로 포맷 템플릿에 넣지 말고 그대로 이어붙인다(포맷 파싱 크래시 방지).
        val payload = "${v.level.label}|${"%.2f".format(v.difficulty)}|${v.detail}"
        Wearable.getMessageClient(this).sendMessage(nodeId, PATH_RESULT, payload.toByteArray())
    }

    private companion object {
        const val PATH_AUDIO = "/voice/audio"
        const val PATH_RESULT = "/voice/result"
    }
}
