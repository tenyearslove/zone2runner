package com.zone2runner.app

import com.zone2runner.app.coaching.LlmCallLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LLM 호출 기록 수집기(spec-027 FR1) — 기록/스냅샷/실시간 카운터/스레드 안전. */
class LlmCallLogTest {

    @Test fun record_snapshot_andCounters() {
        val log = LlmCallLog(pssKb = { 123_456 })
        log.record(30, "coach", "nano-rewrite", "llm(톤 재작성)", "좋아요 유지", "좋습니다 유지해요", 800L)
        log.record(120, "coach", "rule", "rule(LLM 미가용)", "프롬프트…", "규칙 문장", 5L)
        log.record(300, "story", "nano-summarize", "llm(사실 요약)", "article…", "- 불릿", 2000L)

        val snap = log.snapshot()
        assertEquals(3, snap.size)
        assertEquals("coach", snap[0].purpose)
        assertEquals(123_456, snap[0].appPssKb) // 주입한 PSS가 기록됨
        assertEquals("rule", snap[1].engine)

        assertEquals(3, log.total())
        assertEquals(2, log.llmCount())      // rule 제외
        assertEquals(1, log.fallbackCount())
        assertEquals((800L + 2000L) / 2, log.avgMs()) // LLM 채택 건만 평균
    }

    @Test fun snapshot_isCopy_notLiveView() {
        val log = LlmCallLog()
        log.record(0, "coach", "rule", "rule(특수 코칭)", "", "5분 유지!", 1L)
        val snap = log.snapshot()
        log.record(60, "coach", "rule", "rule(특수 코칭)", "", "10분 유지!", 1L)
        assertEquals(1, snap.size) // 이전 스냅샷은 이후 기록에 영향받지 않음
        assertEquals(2, log.snapshot().size)
    }

    @Test fun pssFailure_doesNotBlockRecording() {
        val log = LlmCallLog(pssKb = { throw IllegalStateException("측정 실패") })
        log.record(0, "coach", "nano-prompt", "llm", "p", "o", 10L)
        assertEquals(-1, log.snapshot()[0].appPssKb) // 기록은 관측이지 게이트가 아님(AC-6)
    }

    @Test fun concurrentRecording_losesNothing() {
        val log = LlmCallLog()
        val pool = Executors.newFixedThreadPool(4)
        val n = 200
        val done = CountDownLatch(n)
        repeat(n) { i ->
            pool.execute {
                log.record(i, "coach", if (i % 2 == 0) "nano-prompt" else "rule", "p$i", "in$i", "out$i", i.toLong())
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(n, log.total())
        assertEquals(n / 2, log.llmCount())
    }
}
