package com.alphaadopter.core.user

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

// 로그인(이메일+비밀번호) 자체는 있지만, "누가 관리자인가"는 별도 역할(Role) 테이블
// 없이 배포 환경변수(ADMIN_EMAILS)로만 판단한다. 값이 바뀌어도 재배포만 하면 되고,
// 로그인한 사용자가 스스로 관리자 권한을 부여할 수 있는 엔드포인트를 만들 필요가 없다.
@Component
class AdminEmailChecker(
    @Value("\${app.admin.emails:}") adminEmailsRaw: String,
) {
    private val adminEmails: Set<String> = adminEmailsRaw.split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toSet()

    fun isAdmin(email: String): Boolean = email.trim().lowercase() in adminEmails
}
