package com.zone2runner.app.data

import android.content.Context
import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.domain.Sample
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 필드 테스트 원시 로그(spec-012). 세션당 JSONL 한 파일:
 * meta 1줄 → 1Hz s(샘플: 파이프라인 입력 원시값 + 출력) → e(이벤트: 코칭/종료).
 * 외부 앱 전용 디렉터리에 써서 권한 없이 `adb pull`로 회수한다.
 * 모든 IO는 전용 스레드에서(샘플 루프/메인 비차단), 실패는 러닝을 방해하지 않는다(AC4).
 */
class RunLogger(ctx: Context) {

    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "runlogger").apply { isDaemon = true }
    }
    private var writer: BufferedWriter? = null
    private var lineCount = 0

    val file: File? = runCatching {
        val dir = File(ctx.getExternalFilesDir(null), "runlogs").apply { mkdirs() }
        val name = "run-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
        File(dir, name)
    }.getOrNull()

    fun meta(mode: String, fill: JSONObject.() -> Unit) {
        runCatching { // JSON 조립 실패도 러닝을 방해하지 않는다(AC4)
            write(JSONObject().apply {
                put("type", "meta"); put("wall", System.currentTimeMillis()); put("mode", mode); fill()
            })
        }
    }

    /** 1Hz 샘플: 입력 원시값(Sample) + 파이프라인 출력(LiveState). watchAgeMs는 라이브 모드 외 -1. */
    fun sample(s: Sample, state: LiveState, watchAgeMs: Long) {
        runCatching { // NaN 좌표(GPS 미확보) 등 어떤 값도 러닝 루프를 죽이면 안 된다(AC4)
            write(JSONObject().apply {
                put("type", "s"); put("t", s.tSec); put("wall", System.currentTimeMillis())
                put("hrRaw", s.hr); put("hrClean", state.hr); put("watchAgeMs", watchAgeMs)
                put("pace", round2(s.paceMinKm)); put("spm", s.spm); put("slope", round2(s.slopePct))
                // GPS 미확보 시 lat/lon = NaN — JSONObject는 NaN을 거부하므로 유효할 때만 기록
                if (s.lat.isFinite() && s.lon.isFinite()) { put("lat", s.lat); put("lon", s.lon) }
                put("judg", state.judgment?.index ?: -1); put("uEst", round4(state.uEstFrac))
            })
        }
    }

    fun event(kind: String, fill: JSONObject.() -> Unit = {}) {
        runCatching {
            write(JSONObject().apply {
                put("type", "e"); put("wall", System.currentTimeMillis()); put("kind", kind); fill()
            })
        }
    }

    fun close() {
        io.execute { runCatching { writer?.flush(); writer?.close(); writer = null } }
        io.shutdown()
        runCatching { io.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun write(o: JSONObject) {
        val f = file ?: return
        if (io.isShutdown) return
        runCatching {
            io.execute {
                runCatching {
                    val w = writer ?: BufferedWriter(FileWriter(f, true)).also { writer = it }
                    w.write(o.toString()); w.newLine()
                    if (++lineCount % 30 == 0) w.flush() // 중단(크래시/배터리) 대비 주기 flush
                }
            }
        }
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun round4(v: Double) = Math.round(v * 10000.0) / 10000.0
}
