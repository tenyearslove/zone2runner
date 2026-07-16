package com.zone2runner.app.coaching

import com.zone2runner.app.domain.LlmCallRecord

/**
 * 세션 단위 LLM 호출 기록 수집기(spec-027, GRASP Pure Fabrication).
 * LLM 진입점(LlmCoach.say/freeform, NanoSummarizer 호출부)이 분산돼 있어,
 * 기록 책임만 여기로 단일화한다 — 모든 경로가 이 싱크에만 record 한다.
 *
 * - 스레드 안전: 코칭 코루틴과 스토리/개인화 코루틴이 동시에 기록할 수 있다.
 * - 기록은 관측이지 게이트가 아니다: 기록 실패가 생성 자체를 막지 않는다(AC-6).
 * - pssKb 주입: 앱 프로세스 PSS(KB) 측정 함수. 기본 -1(미측정) — JVM 단위테스트에 Android 의존 없음.
 *   Nano 추론은 AICore 별도 프로세스라 그쪽 CPU/메모리는 관측 불가 — 지어내지 않는다(spec-027 원칙).
 */
class LlmCallLog(private val pssKb: () -> Int = { -1 }) {

    private val records = ArrayList<LlmCallRecord>()

    @Synchronized
    fun record(
        tSec: Int, purpose: String, engine: String, path: String,
        prompt: String, output: String, tookMs: Long,
    ) {
        val pss = runCatching { pssKb() }.getOrDefault(-1)
        records.add(LlmCallRecord(tSec, purpose, engine, path, prompt, output, tookMs, pss))
    }

    /** 지금까지의 기록 사본(누적 스냅샷). 영속화(SessionStore)용. */
    @Synchronized
    fun snapshot(): List<LlmCallRecord> = records.toList()

    // ---- 실시간 카운터(시뮬 디버그 뷰용) ----

    @Synchronized fun total(): Int = records.size
    @Synchronized fun llmCount(): Int = records.count { it.engine != "rule" }
    @Synchronized fun fallbackCount(): Int = records.count { it.engine == "rule" }
    @Synchronized fun avgMs(): Long =
        records.filter { it.engine != "rule" }.let { if (it.isEmpty()) 0L else it.sumOf { r -> r.tookMs } / it.size }
}
