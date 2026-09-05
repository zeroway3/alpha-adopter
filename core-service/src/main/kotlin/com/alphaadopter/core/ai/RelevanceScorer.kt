package com.alphaadopter.core.ai

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

data class RelevanceResult(
    // AI가 실제로 판단한 경우에만 채워짐 — 비활성화(키 없음)/오류 시 null이며 이 경우 통과(relevant=true) 처리
    val score: Int?,
    val relevant: Boolean,
)

@Component
class RelevanceScorer(
    private val claudeRelevanceClient: ClaudeRelevanceClient,
    @Value("\${app.ai.relevance-threshold:50}") private val threshold: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun evaluate(keyword: String, title: String, description: String): RelevanceResult {
        if (!claudeRelevanceClient.isConfigured) {
            return RelevanceResult(score = null, relevant = true)
        }

        return runCatching {
            val score = claudeRelevanceClient.scoreRelevance(keyword, title, description)
            RelevanceResult(score = score, relevant = score >= threshold)
        }.getOrElse { e ->
            // AI 판단 실패는 인프라 문제(레이트리밋/네트워크 등)일 수 있으므로, 실제 알림을
            // 놓치지 않도록 안전하게 통과시킨다(fail-open) — 매칭 자체를 막는 장애점으로 만들지 않음
            log.warn("AI 관련도 판단 실패, 안전하게 통과 처리합니다: {}", e.message)
            RelevanceResult(score = null, relevant = true)
        }
    }
}
