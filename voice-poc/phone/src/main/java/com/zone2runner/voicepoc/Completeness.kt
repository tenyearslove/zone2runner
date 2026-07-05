package com.zone2runner.voicepoc

/** 목표 문장 대비 낭독 완성도(0~1): 전사문에 목표 문자열이 순서대로 얼마나 담겼나(LCS 비율). */
object Completeness {
    fun ratio(target: String, transcript: String): Double {
        val t = normalize(target)
        val s = normalize(transcript)
        if (t.isEmpty()) return 1.0
        return lcs(t, s).toDouble() / t.length
    }

    private fun normalize(s: String) = s.replace(Regex("[\\s.,!?]"), "")

    private fun lcs(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val dp = IntArray(b.length + 1)
        for (i in 1..a.length) {
            var prev = 0
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

/** onRmsChanged dB 엔벨로프 → 호흡 끊김/발화비율 근사(샘플 ≈ 100ms 간격). */
object RmsPauses {
    fun analyze(rms: List<Float>): Pair<Int, Double> {
        if (rms.size < 4) return 0 to 1.0
        val lo = rms.min(); val hi = rms.max()
        if (hi - lo < 1.5) return 0 to 1.0 // 변동 거의 없음(전부 무음/전부 발화)
        val thr = lo + (hi - lo) * 0.35
        val voiced = rms.map { it > thr }
        val first = voiced.indexOfFirst { it }; val last = voiced.indexOfLast { it }
        if (first < 0) return 0 to 0.0
        var pauses = 0; var run = 0
        for (i in first..last) {
            if (!voiced[i]) run++ else { if (run >= 2) pauses++; run = 0 } // ≈200ms+ 침묵
        }
        val voicedCount = (first..last).count { voiced[it] }
        return pauses to voicedCount.toDouble() / (last - first + 1)
    }
}
