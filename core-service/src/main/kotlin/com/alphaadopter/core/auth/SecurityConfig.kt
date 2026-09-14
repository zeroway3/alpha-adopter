package com.alphaadopter.core.auth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    private val rateLimitFilter: RateLimitFilter,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // access/refresh 쿠키가 SameSite=Strict라 크로스사이트 요청에는 애초에 실리지 않는다
            // (AuthCookies 참고) — 별도 CSRF 토큰 없이도 CSRF를 막을 수 있어 비활성화한다.
            // 세션 자체는 여전히 STATELESS(서버 세션 저장 없음, 쿠키는 JWT/refresh 토큰을
            // 담는 운반 수단일 뿐).
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        // "/app.js", "/style.css"는 순수 정적 페이지 시절 파일명 — React(Vite) 전환 후
                        // 빌드 산출물은 해시가 붙은 파일명으로 /assets/** 아래에 생성된다
                        "/", "/index.html", "/assets/**", "/favicon.ico",
                        // 컨트롤러에서 던진 ResponseStatusException은 서블릿 sendError()를 거쳐
                        // 내부적으로 /error 로 포워딩되는데, 이 경로를 permitAll 해두지 않으면
                        // 인증 없는 요청에서는 실제 상태코드(409/401 등)가 전부 403으로 가려진다.
                        "/error",
                        // "/api/auth/me"는 여기 포함하지 않는다 — access token(쿠키)이 유효한
                        // 사용자만 호출 가능해야 프론트가 "로그인돼 있는가"를 물어볼 수 있다.
                        "/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                        "/actuator/**", // Prometheus 스크랩 + 헬스체크는 인증 없이 접근 가능해야 함
                        // 다이제스트 이메일 안의 링크(로그인 세션이 없는 이메일 클라이언트에서 클릭)라 인증 불가
                        "/api/notifications/*/read", "/api/notifications/*/click",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            // 회원가입/로그인/구독 생성은 IP 기준 rate limit을 JWT 파싱보다 먼저 적용한다
            // (미인증 요청인 회원가입/로그인부터 보호해야 하므로)
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jwtAuthFilter, RateLimitFilter::class.java)

        return http.build()
    }
}
