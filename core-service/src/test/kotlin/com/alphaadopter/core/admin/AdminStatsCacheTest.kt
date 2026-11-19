package com.alphaadopter.core.admin

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.config.CacheConfig
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import com.alphaadopter.core.domain.user.UserRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// /api/admin/stats가 호출마다 집계 쿼리 8개+를 새로 돌던 것을 Redis TTL 캐시로 덮은 부분 검증.
// Redis 캐시는 테스트 JVM 전역으로 공유되므로(다른 테스트가 stats()를 호출해 채울 수 있다)
// 매 테스트 전후로 우리가 쓰는 키를 evict한다.
class AdminStatsCacheTest : IntegrationTestBase() {

    private companion object {
        const val KEY = "snapshot"
    }

    @Autowired
    lateinit var adminStatsService: AdminStatsService

    @Autowired
    lateinit var adminStatsController: AdminStatsController

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var cacheManager: CacheManager

    private fun statsCache(): Cache = cacheManager.getCache(CacheConfig.ADMIN_STATS_CACHE)!!

    @BeforeEach
    fun evictBefore() {
        statsCache().evictIfPresent(KEY)
    }

    @AfterEach
    fun evictAfter() {
        statsCache().evictIfPresent(KEY)
    }

    @Test
    fun `snapshot은 캐시되어 DB가 바뀌어도 같은 값을 돌려주고 캐시를 비우면 재계산한다`() {
        // @Transactional을 쓰지 않는다 — Spring Boot가 자동구성하는 RedisCacheManager는
        // transaction-aware라, 활성 트랜잭션 안에서 부른 evict류 호출은 커밋 시점까지 미뤄진다.
        // 테스트용 @Transactional은 기본적으로 롤백하므로 커밋이 영영 안 와서 evict가 실제로는
        // 아무 일도 안 하는 것처럼 동작한다(실제로 CI에서 간헐적으로 겪음 — evict 직후 get()이
        // 여전히 이전 값을 돌려줌). 그래서 probe 유저는 트랜잭션 롤백 대신 직접 지운다.
        val before = adminStatsService.snapshot()

        val probe = userRepository.saveAndFlush(User(email = "cache-probe-${System.nanoTime()}@example.com"))
        try {
            val directCount = userRepository.count()

            val cached = adminStatsService.snapshot()
            assertEquals(before.totalUsers, cached.totalUsers, "캐시 히트라 새 유저가 반영되면 안 된다")

            statsCache().evictIfPresent(KEY)
            assertNull(statsCache().get(KEY), "evict 후 캐시에 값이 없어야 한다")
            val fresh = adminStatsService.snapshot()
            assertEquals(before.totalUsers + 1, directCount, "방금 저장한 유저가 보여야 한다")
            assertEquals(directCount, fresh.totalUsers, "캐시를 비우면 현재 DB 유저 수(=$directCount)가 반영돼야 한다")
        } finally {
            userRepository.delete(probe)
        }
    }

    @Test
    fun `캐시가 채워져 있어도 비관리자는 stats에 접근할 수 없다`() {
        // 컨트롤러가 캐시 조회보다 먼저 requireAdmin을 거는지 — 캐시를 워밍한 뒤에도 403이어야 한다.
        adminStatsService.snapshot()

        val ex = assertFailsWith<ResponseStatusException> {
            adminStatsController.stats(AuthPrincipal(userId = 999L, email = "intruder@example.com", role = UserRole.USER))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
