package com.alphaadopter.core.pipeline

import com.alphaadopter.core.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 같은 기사가 다시 수집되거나 Kafka가 재전달해도 원본(MongoDB)에는 한 건만 남는지 확인한다.
class RawNewsStoreTest : IntegrationTestBase() {

    @Autowired
    lateinit var rawNewsStore: RawNewsStore

    @Autowired
    lateinit var rawNewsMongoRepository: RawNewsMongoRepository

    private fun document(link: String, title: String) =
        RawNewsDocument(keyword = "삼성전자", title = title, description = "요약", link = link, originalLink = null, pubDate = "")

    @Test
    fun `같은 링크는 몇 번을 저장해도 한 건만 남고 처음 저장한 내용이 유지된다`() {
        val link = "https://n.news.naver.com/${UUID.randomUUID()}"

        assertTrue(rawNewsStore.saveIfAbsent(document(link, "처음")))
        assertFalse(rawNewsStore.saveIfAbsent(document(link, "재수집")))
        assertFalse(rawNewsStore.saveIfAbsent(document(link, "재전달")))

        assertEquals(1L, rawNewsMongoRepository.countByLink(link))
        assertEquals("처음", rawNewsMongoRepository.findAll().single { it.link == link }.title)
    }
}
