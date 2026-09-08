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
import kotlin.math.roundToInt

data class EvalCase(val keyword: String, val title: String, val description: String, val relevant: Boolean)

// 1단계 "측정된 AI 필터": eval-set.json에 라벨링된 기사들을 실제 Claude Haiku로 채점하고,
// 여러 임계값에서의 precision/recall/F1과 혼동행렬을 리포트로 출력한다.
//
// 실제 API를 호출하므로(비용 발생) ANTHROPIC_API_KEY가 없는 환경(CI 등)에서는 자동 스킵되고,
// 로컬에서 키를 export한 뒤 수동으로 실행할 때만 돈다 — ClaudeRelevanceClientTest와 동일한 패턴.
//
// 이 eval-set은 20건짜리 시작용 시드 데이터다. 실제 배포 임계값을 정하려면 실 서비스에서
// 수집된 기사를 사람이 직접 라벨링해 계속 추가해야 한다 (목표는 50~100건).
class RelevanceEvalReport {

    @Test
    fun `임계값별 precision recall F1 리포트를 출력한다`() {
        val apiKey = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        assumeTrue(apiKey.isNotBlank(), "ANTHROPIC_API_KEY가 없어 스킵합니다")

        // Redis는 항상 캐시 미스로 동작하는 목으로 대체한다 — eval 리포트는 프롬프트를 바꿔가며
        // 반복 실행하는 도구라, 실제 캐시를 쓰면 이전 실행의 캐시된 점수가 재사용되어 프롬프트
        // 변경 효과를 측정할 수 없게 된다. 매번 20건 전부 새로 채점하는 게 의도한 동작이다.
        val redisTemplate = Mockito.mock(StringRedisTemplate::class.java)
        val redisOps = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(redisOps)
        Mockito.`when`(redisOps.get(any())).thenReturn(null)

        val client = ClaudeRelevanceClient(
            RestClient.builder(),
            redisTemplate = redisTemplate,
            meterRegistry = SimpleMeterRegistry(),
            apiKey = apiKey,
            model = System.getenv("ANTHROPIC_MODEL")?.takeIf { it.isNotBlank() } ?: "claude-haiku-4-5-20251001",
            cacheTtlHours = 24,
            maxRetries = 3,
            retryInitialDelayMs = 500,
        )

        val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val cases: List<EvalCase> = javaClass.getResourceAsStream("/eval/relevance-eval-set.json").use { stream ->
            mapper.readValue(stream, mapper.typeFactory.constructCollectionType(List::class.java, EvalCase::class.java))
        }

        // (실제 점수, 정답 레이블) 쌍을 한 번만 계산해두고 임계값별로는 재사용한다 —
        // 임계값을 바꿀 때마다 API를 다시 호출하면 20건에도 비용이 배로 든다.
        val scored = cases.map { case ->
            val score = client.scoreRelevance(case.keyword, case.title, case.description)
            Triple(case, score, case.relevant)
        }

        println("=== 개별 채점 결과 ===")
        scored.forEach { (case, score, label) ->
            println("[score=$score, label=$label] ${case.keyword} / ${case.title}")
        }

        println("\n=== 임계값별 precision / recall / F1 ===")
        for (threshold in listOf(30, 40, 50, 55, 60, 70, 80)) {
            var tp = 0; var fp = 0; var tn = 0; var fn = 0
            scored.forEach { (_, score, label) ->
                val predicted = score >= threshold
                when {
                    predicted && label -> tp++
                    predicted && !label -> fp++
                    !predicted && !label -> tn++
                    else -> fn++
                }
            }
            val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
            val recall = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
            val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)

            fun pct(v: Double) = "${(v * 100).roundToInt()}%"
            println(
                "threshold=$threshold  precision=${pct(precision)}  recall=${pct(recall)}  f1=${pct(f1)}  " +
                    "confusion(tp=$tp fp=$fp tn=$tn fn=$fn)",
            )
        }
    }
}
