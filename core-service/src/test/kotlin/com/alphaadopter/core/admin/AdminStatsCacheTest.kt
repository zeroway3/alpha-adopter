package com.alphaadopter.core.admin

import com.alphaadopter.core.IntegrationTestBase
import com.alphaadopter.core.auth.AuthPrincipal
import com.alphaadopter.core.config.CacheConfig
import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
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
    fun evictBefore() = statsCache().evict(KEY)

    @AfterEach
    fun evictAfter() = statsCache().evict(KEY)

    @Test
    @Transactional
    fun `snapshot은 캐시되어 DB가 바뀌어도 같은 값을 돌려주고 캐시를 비우면 재계산한다`() {
        val before = adminStatsService.snapshot()

        userRepository.saveAndFlush(User(email = "cache-probe-${System.nanoTime()}@example.com"))
        val directCount = userRepository.count()

        val cached = adminStatsService.snapshot()
        assertEquals(before.totalUsers, cached.totalUsers, "캐시 히트라 새 유저가 반영되면 안 된다")

        statsCache().evict(KEY)
        assertNull(statsCache().get(KEY), "evict 후 캐시에 값이 없어야 한다")
        val fresh = adminStatsService.snapshot()
        assertEquals(before.totalUsers + 1, directCount, "테스트 트랜잭션에서 방금 저장한 유저가 보여야 한다")
        assertEquals(directCount, fresh.totalUsers, "캐시를 비우면 현재 DB 유저 수(=$directCount)가 반영돼야 한다")
    }

    @Test
    fun `캐시가 채워져 있어도 비관리자는 stats에 접근할 수 없다`() {
        // 컨트롤러가 캐시 조회보다 먼저 requireAdmin을 거는지 — 캐시를 워밍한 뒤에도 403이어야 한다.
        adminStatsService.snapshot()

        val ex = assertFailsWith<ResponseStatusException> {
            adminStatsController.stats(AuthPrincipal(userId = 999L, email = "intruder@example.com"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
