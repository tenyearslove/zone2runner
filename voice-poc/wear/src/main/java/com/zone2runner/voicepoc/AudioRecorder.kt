package com.zone2runner.voicepoc

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.min

/**
 * 16kHz mono PCM16 마이크 녹음(블로킹). RECORD_AUDIO 권한 필요, 백그라운드 스레드에서 호출.
 * 완전 온디바이스, 외부 의존 없음.
 */
class AudioRecorder(val sampleRate: Int = 16_000) {

    fun record(durationMs: Int): ShortArray {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate) // 최소 1초 버퍼
        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
        )
        val total = sampleRate * durationMs / 1000
        val out = ShortArray(total)
        var read = 0
        ar.startRecording()
        val chunk = ShortArray(max(320, minBuf / 4))
        while (read < total) {
            val n = ar.read(chunk, 0, min(chunk.size, total - read))
            if (n <= 0) break
            System.arraycopy(chunk, 0, out, read, n)
            read += n
        }
        ar.stop(); ar.release()
        return if (read == total) out else out.copyOf(read)
    }
}

/** 워치<->폰 PCM 전송용 인코딩(리틀엔디안 PCM16). */
object Pcm {
    fun toLeBytes(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) { val v = s[i].toInt(); b[i * 2] = (v and 0xFF).toByte(); b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte() }
        return b
    }
    fun fromLeBytes(b: ByteArray, len: Int): ShortArray {
        val n = len / 2
        val s = ShortArray(n)
        for (i in 0 until n) { val lo = b[i * 2].toInt() and 0xFF; val hi = b[i * 2 + 1].toInt(); s[i] = ((hi shl 8) or lo).toShort() }
        return s
    }
}
