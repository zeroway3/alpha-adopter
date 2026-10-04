package com.alphaadopter.core.domain.user

import org.springframework.data.jpa.repository.JpaRepository

// 조회는 컨트롤러에서 findAll(Sort.by(...))로 충분해 커스텀 쿼리 메서드를 두지 않는다
// (AdminStatsController의 users() 조회와 동일한 패턴).
interface AdminAuditLogRepository : JpaRepository<AdminAuditLog, Long>
