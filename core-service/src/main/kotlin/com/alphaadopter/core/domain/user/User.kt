package com.alphaadopter.core.domain.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "users")
class User(
    @Column(nullable = false, unique = true)
    var email: String,

    // 회원/비회원 구분 - 향후 실시간(회원) vs 일일 다이제스트(비회원) 차등 발송에 사용 예정.
    // 이메일+비밀번호 가입이 도입된 이후로는 가입 시 기본 true (비회원 개념은 추후 결제 연동 시 재도입 예정)
    @Column(nullable = false)
    var isMember: Boolean = false,

    // 이메일+비밀번호 인증 도입 (BCrypt 해시). null이면 로그인 불가 — 인증 도입 이전 데이터 호환용
    @Column(name = "password_hash")
    var passwordHash: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
