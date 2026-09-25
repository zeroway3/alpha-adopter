package com.alphaadopter.core.user

import com.alphaadopter.core.domain.user.UserRepository
import com.alphaadopter.core.domain.user.UserRole
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

// 관리자 권한 자체는 이제 User.role(DB)이 유일한 근거다(예전 AdminEmailChecker처럼 매 요청마다
// 환경변수 화이트리스트를 대조하지 않는다). 다만 "누구를 관리자로 만들지"는 여전히 재배포 없이
// 조정 가능해야 하므로, 기동 시 app.admin.emails 목록에 있는 계정을 ADMIN으로 승격(idempotent
// grant)하는 부트스트랩만 남겨뒀다. 이 목록에서 이메일을 빼도 기존 ADMIN 권한은 자동으로
// 회수되지 않는다 — 회수는 DB에서 직접 처리한다(관리자 관리 UI는 이 규모에선 아직 불필요).
@Component
class AdminBootstrapRunner(
    private val userRepository: UserRepository,
    @Value("\${app.admin.emails:}") adminEmailsRaw: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    private val adminEmails: Set<String> = adminEmailsRaw.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()

    @Transactional
    override fun run(args: ApplicationArguments) {
        adminEmails.forEach { email ->
            val user = userRepository.findByEmailIgnoreCase(email) ?: return@forEach
            if (user.role != UserRole.ADMIN) {
                user.role = UserRole.ADMIN
                userRepository.save(user)
                log.info("app.admin.emails 목록에 따라 {}을(를) 관리자로 승격했습니다", email)
            }
        }
    }
}
