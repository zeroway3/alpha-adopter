package com.alphaadopter.core.user

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

// 인증 시스템이 없는 단계라(future-ideas.md 참고) 관리자 여부를 DB 플래그가 아니라
// 배포 환경변수(ADMIN_EMAILS)로 판단한다. 값이 바뀌어도 재배포만 하면 되고,
// 누구나 호출 가능한 "나를 관리자로 만들기" 같은 공개 엔드포인트를 만들 필요가 없다.
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
