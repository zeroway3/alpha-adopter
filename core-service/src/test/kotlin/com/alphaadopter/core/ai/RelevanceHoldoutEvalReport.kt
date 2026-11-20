package com.alphaadopter.core.ai

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.File
import kotlin.math.sqrt

// 검증용(hold-out) 평가셋으로 관련도 필터의 실제 성능을 잰다.
//
// relevance-eval-set.json(20건)은 오탐 패턴을 찾아 프롬프트를 고치는 데 썼기 때문에, 그 데이터로 잰
// 정밀도 100%는 실제 성능이 아니다. relevance-holdout-set.json은 실제 NAVER 검색 결과를 사람이 직접
// 라벨링한 별도 데이터로, 프롬프트 튜닝에는 쓰지 않고 성능 보고에만 쓴다.
//
// 리포트 항목: 운영 임계값(50점)에서의 혼동행렬·정밀도·재현율·F1(Wilson 95% 신뢰구간), AI 없이 문자열
// 매칭만 쓸 때와의 비교, 임계값별 결과, 키워드별 결과, 같은 기사를 다시 채점했을 때의 점수 흔들림,
// 호출 지연과 비용. 실제 API를 호출하므로 ANTHROPIC_API_KEY가 있을 때만 수동으로 돈다.
class RelevanceHoldoutEvalReport {

    private data class Scored(val case: EvalCase, val scores: List<Int>, val latenciesMs: List<Long>) {
        val score: Int get() = scores.first()
    }

    @Test
    fun `검증셋 관련도 평가 리포트를 출력한다`() {
        val apiKey = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        assumeTrue(apiKey.isNotBlank(), "ANTHROPIC_API_KEY가 없어 스킵합니다")
        val resource = javaClass.getResourceAsStream("/eval/relevance-holdout-set.json")
        assumeTrue(resource != null, "검증셋(relevance-holdout-set.json)이 아직 없어 스킵합니다")
        val repeats = System.getenv("RELEVANCE_EVAL_REPEATS")?.toIntOrNull() ?: 2

        // 매번 실제로 채점하도록 캐시는 항상 미스(RelevanceEvalReport와 같은 이유)
        val redisTemplate = Mockito.mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val redisOps = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(redisOps)
        Mockito.`when`(redisOps.get(any())).thenReturn(null)

        val meterRegistry = SimpleMeterRegistry()
        val model = System.getenv("ANTHROPIC_MODEL")?.takeIf { it.isNotBlank() } ?: "claude-haiku-4-5-20251001"
        val client = ClaudeRelevanceClient(
            RestClient.builder(),
            redisTemplate = redisTemplate,
            meterRegistry = meterRegistry,
            apiKey = apiKey,
            model = model,
            cacheTtlHours = 24,
            maxRetries = 5,
            retryInitialDelayMs = 1000,
        )

        val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val cases: List<EvalCase> = resource!!.use { stream ->
            mapper.readValue(stream, mapper.typeFactory.constructCollectionType(List::class.java, EvalCase::class.java))
        }

        val scored = cases.map { case ->
            val latencies = mutableListOf<Long>()
            val scores = (1..repeats).map {
                val started = System.nanoTime()
                client.scoreRelevance(case.keyword, case.title, case.description)
                    .also { latencies += (System.nanoTime() - started) / 1_000_000 }
            }
            Scored(case, scores, latencies)
        }

        val report = StringBuilder("## 관련도 필터 검증셋 평가 (모델 $model)\n\n")
        val positives = cases.count { it.relevant }
        report.append(
            "- 데이터: 실제 NAVER 뉴스 검색 결과 ${cases.size}건(키워드 ${cases.map { it.keyword }.distinct().size}개), " +
                "사람이 직접 라벨링, 관련 있음 ${positives}건(${pct(positives, cases.size)})\n" +
                "- 프롬프트 튜닝에 쓰지 않은 데이터. 판정은 1회차 점수 기준, 운영 임계값 50점\n\n",
        )

        // 1) 운영 임계값 결과와 기준선 비교
        val m = metrics(scored, 50)
        report.append("### 운영 임계값(50점) 결과\n\n")
        report.append("| | 실제 관련 | 실제 무관 |\n|---|---|---|\n")
        report.append("| AI 통과 | ${m.tp} | ${m.fp} |\n| AI 차단 | ${m.fn} | ${m.tn} |\n\n")
        report.append(
            "| 지표 | 문자열 매칭만 (AI 없음) | AI 필터 |\n|---|---|---|\n" +
                "| 정밀도 (알림 중 관련 기사 비율) | ${pct(positives, cases.size)} | ${pct(m.tp, m.tp + m.fp)} ${ci(m.tp, m.tp + m.fp)} |\n" +
                "| 재현율 (관련 기사 중 알림으로 간 비율) | 100% | ${pct(m.tp, m.tp + m.fn)} ${ci(m.tp, m.tp + m.fn)} |\n" +
                "| F1 | ${"%.2f".format(f1(positives.toDouble() / cases.size, 1.0))} | ${"%.2f".format(m.f1)} |\n" +
                "| 보낸 알림 수 | ${cases.size} | ${m.tp + m.fp} |\n\n" +
                "- 무관한 기사 중 걸러낸 비율: ${pct(m.tn, m.tn + m.fp)} (${m.tn + m.fp}건 중 ${m.tn}건)\n" +
                "- 관련 기사를 잘못 걸러낸 비율: ${pct(m.fn, m.tp + m.fn)} (${m.tp + m.fn}건 중 ${m.fn}건)\n\n",
        )

        // 2) 임계값별
        report.append("### 임계값별\n\n| 임계값 | 정밀도 | 재현율 | F1 | 알림 수 |\n|---|---|---|---|---|\n")
        for (threshold in listOf(30, 40, 50, 60, 70, 80)) {
            val t = metrics(scored, threshold)
            report.append(
                "| $threshold${if (threshold == 50) " (운영)" else ""} | ${pct(t.tp, t.tp + t.fp)} | ${pct(t.tp, t.tp + t.fn)} | " +
                    "${"%.2f".format(t.f1)} | ${t.tp + t.fp} |\n",
            )
        }

        // 3) 키워드별
        report.append("\n### 키워드별 (임계값 50)\n\n| 키워드 | 기사 | 관련 | 정밀도 | 재현율 |\n|---|---|---|---|---|\n")
        scored.groupBy { it.case.keyword }.forEach { (keyword, group) ->
            val k = metrics(group, 50)
            report.append(
                "| $keyword | ${group.size} | ${group.count { it.case.relevant }} | " +
                    "${if (k.tp + k.fp == 0) "-" else pct(k.tp, k.tp + k.fp)} | ${if (k.tp + k.fn == 0) "-" else pct(k.tp, k.tp + k.fn)} |\n",
            )
        }

        // 4) 반복 채점 안정성
        if (repeats > 1) {
            val deltas = scored.map { s -> s.scores.max() - s.scores.min() }
            val flips = scored.count { s -> s.scores.map { it >= 50 }.distinct().size > 1 }
            report.append(
                "\n### 같은 기사를 ${repeats}번 채점했을 때\n\n" +
                    "- 점수 차이 평균 ${"%.1f".format(deltas.average())}점, 최대 ${deltas.max()}점\n" +
                    "- 통과/차단 판정이 바뀐 기사: ${flips}건 / ${scored.size}건 (${pct(flips, scored.size)})\n",
            )
        }

        // 5) 지연과 비용
        val latencies = scored.flatMap { it.latenciesMs }.sorted()
        val calls = latencies.size
        val inputTokens = meterRegistry.find("claude.relevance.tokens").tag("type", "input").counter()?.count() ?: 0.0
        val outputTokens = meterRegistry.find("claude.relevance.tokens").tag("type", "output").counter()?.count() ?: 0.0
        // claude-haiku-4-5 단가: 입력 $1, 출력 $5 / 100만 토큰
        val costPerCall = (inputTokens * 1.0 + outputTokens * 5.0) / 1_000_000 / calls
        report.append(
            "\n### 지연과 비용 (기사 1건 판정)\n\n" +
                "- 지연 p50 ${latencies[calls / 2]}ms / p95 ${latencies[(calls * 95 / 100).coerceAtMost(calls - 1)]}ms / 최대 ${latencies.last()}ms\n" +
                "- 토큰 입력 ${(inputTokens / calls).toInt()}·출력 ${(outputTokens / calls).toInt()}, 비용 약 \$${"%.5f".format(costPerCall)}" +
                " (1,000건 약 \$${"%.2f".format(costPerCall * 1000)})\n",
        )

        // 6) 오판 사례 (분석용)
        report.append("\n### 오판 사례 (임계값 50)\n\n")
        scored.filter { (it.score >= 50) != it.case.relevant }.sortedBy { it.case.keyword }.forEach { s ->
            val kind = if (s.case.relevant) "관련인데 차단" else "무관한데 통과"
            report.append("- [$kind, ${s.score}점] ${s.case.keyword} / ${s.case.title}\n")
        }

        println(report)
        File("build/reports/relevance-eval-holdout.md").apply { parentFile.mkdirs() }.writeText(report.toString())
    }

    private data class Metrics(val tp: Int, val fp: Int, val tn: Int, val fn: Int) {
        val precision get() = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall get() = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
        val f1 get() = f1(precision, recall)
    }

    private fun metrics(scored: List<Scored>, threshold: Int): Metrics {
        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        scored.forEach { s ->
            val predicted = s.score >= threshold
            when {
                predicted && s.case.relevant -> tp++
                predicted && !s.case.relevant -> fp++
                !predicted && !s.case.relevant -> tn++
                else -> fn++
            }
        }
        return Metrics(tp, fp, tn, fn)
    }

    private companion object {
        fun f1(p: Double, r: Double) = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)

        fun pct(n: Int, d: Int) = if (d == 0) "-" else "${"%.0f".format(n * 100.0 / d)}%"

        // 표본이 작을 때 정규근사보다 안정적인 Wilson 점수 구간(95%)
        fun ci(successes: Int, n: Int): String {
            if (n == 0) return ""
            val z = 1.96
            val p = successes.toDouble() / n
            val denom = 1 + z * z / n
            val center = (p + z * z / (2 * n)) / denom
            val margin = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom
            val low = ((center - margin).coerceAtLeast(0.0) * 100)
            val high = ((center + margin).coerceAtMost(1.0) * 100)
            return "(95% CI ${"%.0f".format(low)}~${"%.0f".format(high)}%)"
        }
    }
}

