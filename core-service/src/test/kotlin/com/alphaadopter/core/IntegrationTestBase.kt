package com.alphaadopter.core

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

// "싱글턴 컨테이너" 패턴: @Testcontainers/@Container 어노테이션 기반 라이프사이클(클래스당
// 시작/종료)을 쓰지 않는다. 여러 통합 테스트 클래스가 이 베이스를 상속하면, 클래스 A가 끝나면서
// 공유 static 컨테이너를 꺼버려 클래스 B가 연결 실패하는 문제가 실제로 발생했다 — 그래서 companion
// object 초기화 시점에 딱 한 번만 띄우고, 이후 멈추지 않는다(JVM 종료 시 Testcontainers의
// Ryuk 리소스 리퍼가 정리).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationTestBase {

    companion object {
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16")).apply { start() }

        // apache/kafka 이미지는 testcontainers KafkaContainer의 로그 기반 wait strategy와
        // 맞지 않아(시작 로그 포맷 차이) 공식 지원 이미지 사용
        val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.11")).apply { start() }

        val mongo: MongoDBContainer = MongoDBContainer(DockerImageName.parse("mongo:7")).apply { start() }

        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379).apply { start() }

        // DailyDigestScheduler가 실제로 JavaMailSender.send()를 호출하므로, SMTP 서버가
        // 없으면 발송 실패로 처리되어(재시도 로직 때문에 SENT로 안 바뀜) 테스트가 깨진다.
        // docker-compose에서 쓰는 것과 동일한 Mailpit을 띄운다.
        val mailpit: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("axllent/mailpit:latest")).withExposedPorts(1025, 8025).apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            // replicaSetUrl은 쿼리파라미터(?replicaSet=...)가 붙은 완성된 URI라 뒤에 DB 이름을
            // 이어붙이면 깨진다. 트랜잭션을 쓰지 않으므로 레플리카셋 없이 단순 URI로 직접 구성
            registry.add("spring.mongodb.uri") { "mongodb://${mongo.host}:${mongo.getMappedPort(27017)}/alpha_adopter" }
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("spring.mail.host", mailpit::getHost)
            registry.add("spring.mail.port") { mailpit.getMappedPort(1025) }
        }
    }
}
