package com.zone2runner.voicepoc

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * 페이스 낭독 샘플 저장(학습 데이터). filesDir/talktest/<id>.wav + <id>.json.
 * 나중에 `adb pull`로 통째 회수해 오프라인 학습에 쓴다.
 */
object SampleStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "talktest").apply { mkdirs() }

    fun count(ctx: Context): Int = dir(ctx).listFiles { f -> f.extension == "wav" }?.size ?: 0

    /** WAV(16kHz mono PCM16) + 라벨/특징 JSON을 저장하고 파일명(id) 반환. */
    fun save(ctx: Context, id: String, samples: ShortArray, sampleRate: Int, label: String, m: UtteranceMetrics, beatMs: Int) {
        val d = dir(ctx)
        Wav.write(File(d, "$id.wav"), samples, sampleRate)
        val json = """
            {"id":"$id","label":"$label","beatMs":$beatMs,"sampleRate":$sampleRate,
             "breaths":${m.breaths},"breathsPerMin":${"%.2f".format(m.breathsPerMin)},
             "meanUtteranceSec":${"%.3f".format(m.meanUtteranceSec)},"tokensPerBreath":${"%.2f".format(m.tokensPerBreath)},
             "speakingRatio":${"%.3f".format(m.speakingRatio)},"spanSec":${"%.2f".format(m.spanSec)}}
        """.trimIndent()
        File(d, "$id.json").writeText(json)
    }
}

/** 최소 WAV(PCM16 LE mono) 라이터. */
object Wav {
    fun write(file: File, samples: ShortArray, sampleRate: Int) {
        val dataLen = samples.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            fun s(str: String) = raf.writeBytes(str)
            fun le32(v: Int) { raf.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())) }
            fun le16(v: Int) { raf.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())) }
            s("RIFF"); le32(36 + dataLen); s("WAVE")
            s("fmt "); le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            s("data"); le32(dataLen)
            val bytes = ByteArray(dataLen)
            for (i in samples.indices) { val v = samples[i].toInt(); bytes[i * 2] = (v and 0xFF).toByte(); bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte() }
            raf.write(bytes)
        }
    }
}
