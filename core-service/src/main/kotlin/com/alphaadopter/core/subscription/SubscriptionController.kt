package com.alphaadopter.core.subscription

import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.domain.subscription.Subscription
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.user.UserRepository
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/subscriptions")
@Validated
class SubscriptionController(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionCache: SubscriptionCache,
    @Value("\${app.subscription.max-distinct-keywords}") private val maxDistinctKeywords: Long,
) {

    @PostMapping
    @Transactional
    fun create(
        @Valid @RequestBody request: SubscriptionCreateRequest,
        @AuthenticationPrincipal principal: AuthPrincipal,
    ): ResponseEntity<SubscriptionResponse> {
        val user = userRepository.findById(principal.userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다.") }

        if (subscriptionRepository.existsByUserIdAndKeywordAndType(user.id!!, request.keyword, request.type)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 구독입니다: ${request.keyword}")
        }

        // 새 키워드일 때만 수집 호출량이 늘어나므로, 이미 누군가 구독 중인 키워드에 합류하는 건 한도와 무관하게 허용
        val isNewKeyword = !subscriptionRepository.existsByKeyword(request.keyword)
        if (isNewKeyword && subscriptionRepository.countDistinctKeywords() >= maxDistinctKeywords) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "신규 키워드 구독 한도(${maxDistinctKeywords}개)에 도달했습니다. 이미 등록된 키워드만 구독할 수 있습니다.",
            )
        }

        val subscription = subscriptionRepository.save(
            Subscription(user = user, keyword = request.keyword, type = request.type),
        )
        // SubscriptionCache는 기본 30초 주기로만 갱신되는데, 방금 구독한 키워드 뉴스가 그 사이에
        // 들어오면 놓칠 수 있어 여기서 즉시 갱신해준다.
        subscriptionCache.refresh()
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionResponse.from(subscription))
    }

    @GetMapping
    fun list(@AuthenticationPrincipal principal: AuthPrincipal): List<SubscriptionResponse> =
        subscriptionRepository.findAllByUserId(principal.userId).map(SubscriptionResponse::from)

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(@PathVariable id: Long, @AuthenticationPrincipal principal: AuthPrincipal) {
        val subscription = subscriptionRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 구독입니다: $id") }

        if (subscription.user.id != principal.userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 구독만 삭제할 수 있습니다.")
        }

        subscriptionRepository.delete(subscription)
        subscriptionCache.refresh()
    }
}
