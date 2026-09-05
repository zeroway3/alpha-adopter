package com.alphaadopter.core.auth

// JWT에서 복원한 인증 주체. UserDetails 전체를 구현할 필요 없이, 컨트롤러에서
// @AuthenticationPrincipal로 바로 꺼내 쓰기 위한 최소 표현.
data class AuthPrincipal(
    val userId: Long,
    val email: String,
)
