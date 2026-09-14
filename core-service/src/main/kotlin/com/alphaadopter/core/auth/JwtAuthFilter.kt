package com.alphaadopter.core.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// access_token httpOnly 쿠키가 기본 인증 경로다. 브라우저는 same-origin 요청에 쿠키를
// 자동으로 실어 보내므로 SSE(EventSource)도 별도 처리 없이 인증된다 — 예전엔 EventSource가
// 커스텀 헤더를 못 보내 ?token= 쿼리 파라미터로 우회했지만, 쿠키 기반으로 전환하며 그 우회가
// 필요 없어졌다. Authorization: Bearer 헤더는 curl/스크립트 등 브라우저 밖 클라이언트를 위한
// fallback으로만 남겨둔다.
@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {

    // SseEmitter(SSE 스트림)는 서블릿 Async로 처리되는데, OncePerRequestFilter는 기본적으로
    // 최초 REQUEST 디스패치에서만 필터를 실행하고 그 뒤 Async 재디스패치는 건너뛴다. 그런데
    // SecurityContext는 스레드 로컬이라 Async 재디스패치가 다른 워커 스레드에서 돌면 인증 정보가
    // 사라져 있고, 이 시점에 anyRequest().authenticated()가 다시 평가되면서
    // AuthorizationDeniedException이 터진다(이미 응답이 커밋된 뒤라 에러 응답도 못 보내고 그냥
    // 커넥션이 죽음 — 클라이언트는 영원히 "연결 중"). 그래서 Async 디스패치에도 이 필터가 다시
    // 돌아서 SecurityContext를 재구성하도록 명시적으로 켜준다.
    override fun shouldNotFilterAsyncDispatch() = false

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
        request.cookies?.firstOrNull { it.name == ACCESS_TOKEN_COOKIE }?.value?.let { return it }
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            return header.removePrefix("Bearer ")
        }
        return null
    }
}
