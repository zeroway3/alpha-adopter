package com.alphaadopter.core.pipeline

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant

// 원본 기사(landing zone) 저장소. 예전엔 news.raw 메시지가 올 때마다 무조건 insert해서, 같은 기사가
// 다시 수집되거나 Kafka가 메시지를 재전달하면(at-least-once) 원본이 계속 중복으로 쌓였다. link에
// 유니크 인덱스를 걸고 "없을 때만 넣기(upsert + setOnInsert)"로 바꿔 몇 번을 받아도 한 건만 남긴다.
@Component
class RawNewsStore(
    private val mongoTemplate: MongoTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 기존 환경에 이미 중복 원본이 쌓여 있으면 유니크 인덱스 생성이 실패한다. 그렇다고 기동을 막을
    // 일은 아니어서(upsert 자체는 인덱스 없이도 동작) 경고만 남기고 넘어간다.
    @EventListener(ApplicationReadyEvent::class)
    fun ensureIndexes() {
        runCatching {
            mongoTemplate.indexOps(RawNewsDocument::class.java)
                .createIndex(Index().on("link", Sort.Direction.ASC).unique().named("uk_raw_news_link"))
        }.onFailure { e ->
            log.warn("raw_news.link 유니크 인덱스를 만들지 못했습니다(기존 중복 데이터 정리 필요): {}", e.message)
        }
    }

    // 새로 저장했으면 true, 이미 있던 기사면 false
    fun saveIfAbsent(document: RawNewsDocument): Boolean {
        val query = Query(Criteria.where("link").`is`(document.link))
        val update = Update()
            .setOnInsert("keyword", document.keyword)
            .setOnInsert("title", document.title)
            .setOnInsert("description", document.description)
            .setOnInsert("link", document.link)
            .setOnInsert("originalLink", document.originalLink)
            .setOnInsert("pubDate", document.pubDate)
            .setOnInsert("collectedAt", Instant.now())
        val previous = mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(false),
            RawNewsDocument::class.java,
        )
        return previous == null
    }
}
