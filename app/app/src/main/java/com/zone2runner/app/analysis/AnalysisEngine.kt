package com.zone2runner.app.analysis

/**
 * 관측 데이터 분석 엔진(FR3, spec-025) — 등록된 지표를 실행하는 조립기(GRASP Controller).
 *
 * 지표 추가 = 생성 시 리스트에 한 줄 등록(OCP — 이 클래스 수정 없음). 낮은 결합: 엔진은 AnalysisMetric
 * 인터페이스와 SignalWindow에만 의존하고 개별 지표 내부를 모른다.
 */
class AnalysisEngine(private val metrics: List<AnalysisMetric>) {

    private val latest = HashMap<String, MetricSample>()

    /** 매 틱(1Hz): REALTIME/BOTH 지표 실행 → non-null 결과. 최근값은 latest()로 조회(코칭용). */
    fun onTick(input: AnalysisInput): List<MetricSample> {
        val out = ArrayList<MetricSample>()
        for (m in metrics) {
            if (m.mode == MetricMode.REALTIME || m.mode == MetricMode.BOTH) {
                val s = m.onTick(input) ?: continue
                latest[s.id] = s
                out += s
            }
        }
        return out
    }

    /** 세션 종료: SESSION_END/BOTH 지표 실행(input.window = 세션 전체) → non-null 결과. */
    fun onSessionEnd(input: AnalysisInput): List<MetricSample> {
        val out = ArrayList<MetricSample>()
        for (m in metrics) {
            if (m.mode == MetricMode.SESSION_END || m.mode == MetricMode.BOTH) {
                val s = m.onSessionEnd(input) ?: continue
                out += s
            }
        }
        return out
    }

    /** 최근 실시간 지표값(코칭 트리거/대시보드용). 없으면 null. */
    fun latest(id: String): MetricSample? = latest[id]

    fun latestAll(): Map<String, MetricSample> = latest.toMap()
}
