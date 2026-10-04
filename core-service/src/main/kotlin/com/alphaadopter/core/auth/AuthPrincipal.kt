package com.alphaadopter.core.auth

import com.alphaadopter.core.domain.user.UserRole

// JWT에서 복원한 인증 주체. UserDetails 전체를 구현할 필요 없이, 컨트롤러에서
// @AuthenticationPrincipal로 바로 꺼내 쓰기 위한 최소 표현. role은 JWT의 role 클레임에서
// 복원되며(JwtService 참고), 이 값이 JwtAuthFilter가 SecurityContext에 채우는
// GrantedAuthority("ROLE_xxx")의 근거가 된다.
data class AuthPrincipal(
    val userId: Long,
    val email: String,
    val role: UserRole = UserRole.USER,
)
