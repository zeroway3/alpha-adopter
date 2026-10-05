package com.alphaadopter.core.ai.agent

import com.alphaadopter.core.ai.ClaudeTool
import com.alphaadopter.core.ai.ClaudeUsage
import tools.jackson.databind.JsonNode

// ClaudeRelevanceClient의 ClaudeMessage/ClaudeMessagesRequest는 tool_choice로 도구 호출 하나를
// 강제하는 단발성 호출 전용이라 재사용할 수 없다. 에이전트 루프는 모델이 스스로 "어떤 도구를
// 부를지, 아니면 끝낼지"를 고르는 멀티턴 대화가 필요해서, content를 블록 배열로 다루는 별도
// 프로토콜을 둔다(Anthropic Messages API의 tool_use/tool_result 블록 구조를 그대로 반영).

// type별로 쓰는 필드가 다르다: text→text, tool_use(모델→나)→id/name/input,
// tool_result(나→모델)→tool_use_id/content.
data class AgentContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonNode? = null,
    val tool_use_id: String? = null,
    val content: String? = null,
)

data class AgentMessage(val role: String, val content: List<AgentContentBlock>)

data class AgentRequest(
    val model: String,
    val max_tokens: Int,
    val system: String,
    val messages: List<AgentMessage>,
    val tools: List<ClaudeTool>,
)

data class AgentResponse(
    val content: List<AgentContentBlock> = emptyList(),
    val stop_reason: String? = null,
    val usage: ClaudeUsage = ClaudeUsage(),
)

fun textBlock(text: String) = AgentContentBlock(type = "text", text = text)

fun toolResultBlock(toolUseId: String?, content: String) =
    AgentContentBlock(type = "tool_result", tool_use_id = toolUseId, content = content)
