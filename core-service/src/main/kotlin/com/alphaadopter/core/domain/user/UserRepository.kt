package com.alphaadopter.core.domain.user

import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?

    // AdminBootstrapRunner가 app.admin.emails 목록과 대조할 때 사용 — 예전 AdminEmailChecker가
    // 양쪽을 모두 소문자로 바꿔 비교하던 대소문자 무관 매칭을 그대로 유지하기 위함
    fun findByEmailIgnoreCase(email: String): User?
}
