package com.alphaadopter.core.subscription

import com.alphaadopter.core.domain.subscription.Subscription
import com.alphaadopter.core.domain.subscription.SubscriptionRepository
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

// 인증 시스템 도입 전 단계라 email로 사용자를 식별한다 (docs/future-ideas.md 참고, 인증은 별도 범위)
@RestController
@RequestMapping("/api/subscriptions")
@Validated
class SubscriptionController(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
) {

    @PostMapping
    @Transactional
    fun create(@Valid @RequestBody request: SubscriptionCreateRequest): ResponseEntity<SubscriptionResponse> {
        val user = userRepository.findByEmail(request.email) ?: userRepository.save(User(email = request.email))

        if (subscriptionRepository.existsByUserIdAndKeywordAndType(user.id!!, request.keyword, request.type)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 구독입니다: ${request.keyword}")
        }

        val subscription = subscriptionRepository.save(
            Subscription(user = user, keyword = request.keyword, type = request.type),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionResponse.from(subscription))
    }

    @GetMapping
    fun list(@RequestParam @NotBlank @Email email: String): List<SubscriptionResponse> {
        val user = userRepository.findByEmail(email)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다: $email")
        return subscriptionRepository.findAllByUserId(user.id!!).map(SubscriptionResponse::from)
    }

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(@PathVariable id: Long, @RequestParam @NotBlank @Email email: String) {
        val subscription = subscriptionRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 구독입니다: $id") }

        if (subscription.user.email != email) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 구독만 삭제할 수 있습니다.")
        }

        subscriptionRepository.delete(subscription)
    }
}
