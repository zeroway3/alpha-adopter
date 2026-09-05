package com.alphaadopter.core.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// 일반 API 호출은 Authorization: Bearer 헤더로 인증하지만, SSE(EventSource)는 커스텀 헤더를
// 보낼 수 없어서 그 엔드포인트만 예외적으로 ?token= 쿼리 파라미터도 허용한다.
@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        extractToken(request)?.let { token ->
            jwtService.parse(token)?.let { principal ->
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(principal, null, emptyList())
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun extractToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            return header.removePrefix("Bearer ")
        }
        return request.getParameter("token")
    }
}
