package com.zone2runner.app.pipeline

import com.zone2runner.app.domain.ZoneJudgment

/**
 * 규칙 Zone 판정 (adr-013 FR1) — 지속 심박(60초 평균) vs 개인화 경계(bpm)의 결정적 비교.
 *
 * 판정과 밴드 표시가 같은 경계를 참조하므로 "밴드 밖인데 반대 판정" 같은 모순이 구조적으로 불가능.
 * 히스테리시스(Schmitt trigger): 상태 전환은 새 상태 방향으로 경계 ±marginBpm을 넘을 때만 —
 * 경계 위에서 판정이 깜빡이는 채터링을 억제한다.
 */
class ZoneJudge(private val marginBpm: Double = 2.0) {

    var state: ZoneJudgment? = null
        private set

    /** susHr = 지속 심박(bpm), loBpm/hiBpm = 개인화 Zone2 하한/상한(bpm). */
    fun judge(susHr: Double, loBpm: Double, hiBpm: Double): ZoneJudgment {
        val s = state
        // 현재 상태 쪽 경계는 margin만큼 "관대"하게(빠져나가려면 더 확실히 넘어야 함)
        val hiEff = if (s == ZoneJudgment.ABOVE) hiBpm - marginBpm else hiBpm + marginBpm
        val loEff = if (s == ZoneJudgment.BELOW) loBpm + marginBpm else loBpm - marginBpm
        val next = when {
            susHr > hiEff -> ZoneJudgment.ABOVE
            susHr < loEff -> ZoneJudgment.BELOW
            // hiEff/loEff가 이미 현재 상태의 히스테리시스를 반영하므로, 그 사이면 IN
            else -> ZoneJudgment.IN
        }
        state = next
        return next
    }
}
