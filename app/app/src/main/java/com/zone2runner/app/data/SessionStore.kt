package com.zone2runner.app.data

import android.content.Context
import com.zone2runner.app.domain.RunReport
import com.zone2runner.app.domain.SeriesPoint
import com.zone2runner.app.domain.TrackPoint
import com.zone2runner.app.domain.ZoneJudgment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 러닝 세션(RunReport) 영속화 — filesDir/sessions/<id>.json.
 * 경량 데모 규모라 DB 대신 파일 1개=세션 1개(JSON). 히스토리 화면이 목록/상세를 로드.
 */
object SessionStore {

    /** 히스토리 목록 카드용 요약(전체 로드 없이 헤더만). */
    data class Summary(
        val id: String,
        val startedAtEpochMs: Long,
        val durationSec: Int,
        val distanceM: Double,
        val avgHr: Int,
        val zone2Pct: Int,
        val usedModel: Boolean,
    )

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "sessions").apply { if (!exists()) mkdirs() }

    /** 저장 후 부여된 id를 담은 RunReport 반환. id 없으면 시작시각으로 생성. */
    fun save(ctx: Context, report: RunReport): RunReport {
        val id = report.id.ifBlank { "s${report.startedAtEpochMs.coerceAtLeast(1L)}" }
        val out = report.copy(id = id)
        File(dir(ctx), "$id.json").writeText(toJson(out).toString())
        return out
    }

    fun listSummaries(ctx: Context): List<Summary> =
        dir(ctx).listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
            runCatching { summaryOf(JSONObject(f.readText())) }.getOrNull()
        }?.sortedByDescending { it.startedAtEpochMs } ?: emptyList()

    fun load(ctx: Context, id: String): RunReport? =
        runCatching { fromJson(JSONObject(File(dir(ctx), "$id.json").readText())) }.getOrNull()

    /** 전체 세션을 시간순(오래된→최근)으로 로드 — 추세/개인기록(SessionTrends)용. 데모 규모라 전량 로드. */
    fun allReports(ctx: Context): List<RunReport> =
        dir(ctx).listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
            runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
        }?.sortedBy { it.startedAtEpochMs } ?: emptyList()

    fun delete(ctx: Context, id: String) {
        runCatching { File(dir(ctx), "$id.json").delete() }
    }

    /** 저장된 세션의 사후 스토리만 교체(LLM 풀이가 규칙 폴백을 덮어씀). spec-023 FR2. */
    fun updateStory(ctx: Context, id: String, story: String) {
        val r = load(ctx, id) ?: return
        save(ctx, r.copy(sessionStory = story))
    }

    /**
     * LLM 호출 기록을 최신 누적 스냅샷으로 교체(spec-027). 스토리/개인화 설명이 세션 저장 후
     * 비동기로 생성되므로, 각 생성 완료 시 전체 스냅샷을 다시 쓴다(스냅샷=누적이라 마지막 쓰기가 전체 포함).
     */
    fun updateLlmCalls(ctx: Context, id: String, calls: List<com.zone2runner.app.domain.LlmCallRecord>) {
        val r = load(ctx, id) ?: return
        save(ctx, r.copy(llmCalls = calls))
    }

    // ---- 직렬화 ----

    internal fun toJson(r: RunReport): JSONObject {
        val o = JSONObject()
        o.put("id", r.id)
        o.put("startedAtEpochMs", r.startedAtEpochMs)
        o.put("durationSec", r.durationSec)
        o.put("distanceM", r.distanceM)
        o.put("avgHr", r.avgHr)
        o.put("maxHr", r.maxHr)
        o.put("belowSec", r.belowSec)
        o.put("inSec", r.inSec)
        o.put("aboveSec", r.aboveSec)
        o.put("avgPaceMinKm", r.avgPaceMinKm)
        o.put("uEstStartFrac", r.uEstStartFrac)
        o.put("uEstEndFrac", r.uEstEndFrac)
        o.put("restingHr", r.restingHr)
        o.put("maxHrProfile", r.maxHrProfile)
        o.put("usedModel", r.usedModel)
        o.put("coachSource", r.coachSource)
        o.put("sourceMode", r.sourceMode)
        o.put("avgSpm", r.avgSpm)
        o.put("sessionStory", r.sessionStory)
        if (r.submaxHr != null) o.put("submaxHr", r.submaxHr)
        if (r.analysisLines.isNotEmpty()) o.put("analysisLines", JSONArray(r.analysisLines))
        o.put("coachingLines", JSONArray(r.coachingLines))
        if (r.llmCalls.isNotEmpty()) { // LLM 프로비넌스/텔레메트리(spec-027)
            val lc = JSONArray()
            for (c in r.llmCalls) {
                lc.put(JSONObject()
                    .put("t", c.tSec).put("purpose", c.purpose).put("engine", c.engine).put("path", c.path)
                    .put("prompt", c.prompt).put("output", c.output).put("ms", c.tookMs).put("pss", c.appPssKb))
            }
            o.put("llmCalls", lc)
        }
        val tr = JSONArray()
        for (t in r.track) {
            tr.put(JSONArray().put(t.lat).put(t.lon).put(t.judgment?.index ?: -1))
        }
        o.put("track", tr)
        val se = JSONArray()
        for (p in r.series) {
            se.put(JSONArray().put(p.tSec).put(p.hr).put(p.paceMinKm).put(p.judgmentIndex).put(p.slopePct))
        }
        o.put("series", se)
        return o
    }

    private fun summaryOf(o: JSONObject): Summary = Summary(
        id = o.optString("id"),
        startedAtEpochMs = o.optLong("startedAtEpochMs"),
        durationSec = o.optInt("durationSec"),
        distanceM = o.optDouble("distanceM"),
        avgHr = o.optInt("avgHr"),
        zone2Pct = if (o.optInt("durationSec") > 0) o.optInt("inSec") * 100 / o.optInt("durationSec") else 0,
        usedModel = o.optBoolean("usedModel", true),
    )

    internal fun fromJson(o: JSONObject): RunReport {
        val coaching = o.optJSONArray("coachingLines")?.let { a ->
            List(a.length()) { a.getString(it) }
        } ?: emptyList()
        val track = o.optJSONArray("track")?.let { a ->
            List(a.length()) {
                val t = a.getJSONArray(it)
                TrackPoint(t.getDouble(0), t.getDouble(1), ZoneJudgment.fromIndexOrNull(t.getInt(2)))
            }
        } ?: emptyList()
        val series = o.optJSONArray("series")?.let { a ->
            List(a.length()) {
                val p = a.getJSONArray(it)
                SeriesPoint(p.getInt(0), p.getInt(1), p.getDouble(2), p.getInt(3),
                    if (p.length() > 4) p.getDouble(4) else 0.0)
            }
        } ?: emptyList()
        return RunReport(
            durationSec = o.optInt("durationSec"),
            distanceM = o.optDouble("distanceM"),
            avgHr = o.optInt("avgHr"),
            maxHr = o.optInt("maxHr"),
            belowSec = o.optInt("belowSec"),
            inSec = o.optInt("inSec"),
            aboveSec = o.optInt("aboveSec"),
            avgPaceMinKm = o.optDouble("avgPaceMinKm"),
            coachingLines = coaching,
            track = track,
            uEstStartFrac = o.optDouble("uEstStartFrac", 0.70),
            uEstEndFrac = o.optDouble("uEstEndFrac", 0.70),
            restingHr = o.optInt("restingHr"),
            maxHrProfile = o.optInt("maxHrProfile"),
            series = series,
            id = o.optString("id"),
            startedAtEpochMs = o.optLong("startedAtEpochMs"),
            usedModel = o.optBoolean("usedModel", true),
            coachSource = o.optString("coachSource", "rule"),
            sourceMode = o.optString("sourceMode", "sim"),
            avgSpm = o.optInt("avgSpm", 0),
            sessionStory = o.optString("sessionStory", ""),
            submaxHr = if (o.has("submaxHr")) o.optDouble("submaxHr") else null,
            analysisLines = o.optJSONArray("analysisLines")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            llmCalls = o.optJSONArray("llmCalls")?.let { a ->
                List(a.length()) {
                    val c = a.getJSONObject(it)
                    com.zone2runner.app.domain.LlmCallRecord(
                        tSec = c.optInt("t"), purpose = c.optString("purpose"), engine = c.optString("engine"),
                        path = c.optString("path"), prompt = c.optString("prompt"), output = c.optString("output"),
                        tookMs = c.optLong("ms"), appPssKb = c.optInt("pss", -1),
                    )
                }
            } ?: emptyList(),
        )
    }
}
