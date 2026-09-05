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
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // 쿠키/세션 없이 Authorization 헤더(JWT)로만 인증하는 stateless API라 CSRF 보호 불필요
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/", "/index.html", "/app.js", "/style.css", "/favicon.ico",
                        // 컨트롤러에서 던진 ResponseStatusException은 서블릿 sendError()를 거쳐
                        // 내부적으로 /error 로 포워딩되는데, 이 경로를 permitAll 해두지 않으면
                        // 인증 없는 요청에서는 실제 상태코드(409/401 등)가 전부 403으로 가려진다.
                        "/error",
                        "/api/auth/**",
                        "/actuator/**", // Prometheus 스크랩 + 헬스체크는 인증 없이 접근 가능해야 함
                        // 다이제스트 이메일 안의 링크(로그인 세션이 없는 이메일 클라이언트에서 클릭)라 인증 불가
                        "/api/notifications/*/read", "/api/notifications/*/click",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}
