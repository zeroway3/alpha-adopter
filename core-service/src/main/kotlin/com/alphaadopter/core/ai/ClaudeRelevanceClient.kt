package com.alphaadopter.core.ai

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

data class ClaudeMessage(val role: String, val content: String)

data class ClaudeMessagesRequest(
    val model: String,
    val max_tokens: Int,
    val messages: List<ClaudeMessage>,
)

data class ClaudeContentBlock(val type: String = "", val text: String = "")

data class ClaudeMessagesResponse(val content: List<ClaudeContentBlock> = emptyList())

data class RelevanceScoreJson(val score: Int)

// 뉴스 기사와 구독 키워드 사이의 실제 관련도를 Claude Haiku로 판단한다.
// 키워드 문자열 포함 여부만으로는 "스치듯 언급된" 무관한 기사도 매칭되는 노이즈 문제가 있어서,
// 그 위에 얹는 2차 필터로 사용한다 (docs/phase6-ai-relevance-filtering.md 참고).
@Component
class ClaudeRelevanceClient(
    @Value("\${app.ai.anthropic-api-key:}") private val apiKey: String,
    @Value("\${app.ai.model:claude-haiku-4-5-20251001}") private val model: String,
    private val objectMapper: ObjectMapper,
) {
    val isConfigured: Boolean = apiKey.isNotBlank()

    private val restClient = RestClient.builder()
        .baseUrl("https://api.anthropic.com")
        .build()

    fun scoreRelevance(keyword: String, title: String, description: String): Int {
        check(isConfigured) { "ANTHROPIC_API_KEY가 설정되지 않았습니다." }

        val prompt = buildPrompt(keyword, title, description)
        val response = restClient.post()
            .uri("/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .body(ClaudeMessagesRequest(model = model, max_tokens = 50, messages = listOf(ClaudeMessage("user", prompt))))
            .retrieve()
            .body(ClaudeMessagesResponse::class.java)

        val text = response?.content?.firstOrNull { it.type == "text" }?.text
            ?: error("Claude 응답에서 텍스트를 찾을 수 없습니다.")
        // 모델이 코드블록(```json ... ```)으로 감싸는 경우가 있어 순수 JSON만 추출
        val json = Regex("\\{[^{}]*}").find(text)?.value ?: error("Claude 응답이 JSON 형식이 아닙니다: $text")
        return objectMapper.readValue(json, RelevanceScoreJson::class.java).score.coerceIn(0, 100)
    }

    private fun buildPrompt(keyword: String, title: String, description: String) = """
        다음은 사용자가 등록한 키워드와 수집된 뉴스 기사입니다.
        이 기사가 키워드와 실질적으로 관련 있고 중요한 내용인지 0~100 사이의 정수로 평가하세요.
        키워드가 기사 핵심 주제와 무관하게 스치듯 언급되거나 우연히 문자열만 겹치는 경우 낮은 점수를,
        키워드가 실제로 기사의 핵심 주제이면 높은 점수를 주세요.

        키워드: $keyword
        제목: $title
        본문 요약: $description

        반드시 아래 JSON 형식으로만 답하세요. 다른 설명은 절대 하지 마세요.
        {"score": <0에서 100 사이 정수>}
    """.trimIndent()
}
