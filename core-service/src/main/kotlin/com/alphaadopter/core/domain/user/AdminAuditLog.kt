package com.alphaadopter.core.domain.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// 관리자 권한 변경 이력. RBAC 전환(User.role) 이후 "누가 언제 관리자가 됐는지"를 애플리케이션
// 로그(재시작하면 흩어지고 검색이 번거로움)가 아니라 조회 가능한 테이블로 남겨둔다.
@Entity
@Table(name = "admin_audit_logs")
class AdminAuditLog(
    @Column(nullable = false) val targetUserId: Long,
    @Column(nullable = false) val targetEmail: String,
    @Column(nullable = false) val previousRole: String,
    @Column(nullable = false) val newRole: String,
    // 누가/무엇이 이 변경을 일으켰는지 — 지금은 AdminBootstrapRunner뿐이지만, 나중에 관리자가
    // 직접 승격/강등하는 API가 생기면 그 호출자의 이메일이 여기 들어가도록 확장한다.
    @Column(nullable = false) val changedBy: String,
    @Column(nullable = false) val reason: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
