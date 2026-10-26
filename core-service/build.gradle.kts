plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.4.21"
}

group = "com.alphaadopter"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// Spring Boot 4.1.1이 io.spring.dependency-management로 관리하는 Tomcat 버전(11.0.24)에
// critical CVE 3건(인증 우회·인가 우회, GHSA-gcx9-497g-6cp6/9xv2-5v5q-p794/h3x4-894j-xpx5)이
// 있고 11.0.25부터 패치됨. 일반 Gradle `constraints {}`는 이 플러그인의 BOM에 덮어써져 효과가
// 없었다(실제로 겪음, 11.0.27 -> 11.0.24로 도로 깎임) — Spring Boot BOM이 문서화한 대로
// `tomcat.version` 프로퍼티를 직접 지정해야 Boot의 다음 패치 릴리스를 기다리지 않고 올라간다.
ext["tomcat.version"] = "11.0.27"

// jackson-core 3.1.5(Spring Boot 4.1.1이 관리하는 jackson-bom.version)에 high CVE 2건 —
// ReDoS(GHSA-p6pp-m3f8-5c89), 에러 토큰 길이 미제한으로 인한 DoS(GHSA-7hhh-6rmp-j9qf).
// Tomcat과 같은 이유로 일반 constraints는 안 먹혀서(BOM에 덮어써짐) jackson-bom.version
// 프로퍼티를 직접 지정한다. 3.1.7부터 패치돼 같은 마이너 라인의 최신 패치로 올림.
ext["jackson-bom.version"] = "3.1.8"

dependencies {
	// kafka-clients 4.2.1이 끌어오는 at.yawk.lz4:lz4-java 1.10.1에 high CVE(공유 임시
	// 디렉터리에 추출한 네이티브 라이브러리를 다른 로컬 사용자가 교체 가능, GHSA-mcr4-qmvw-px4g)가
	// 있고 1.11.4부터 패치됨. 이건 Spring Boot BOM이 아니라 Kafka 클라이언트의 전이 의존성이라
	// 일반 constraints로 오버라이드가 먹힌다(Tomcat 케이스와 달리 BOM에 안 덮어써짐).
	constraints {
		implementation("at.yawk.lz4:lz4-java:1.12.1")
	}

	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	// 관리자 통계(/api/admin/stats) 집계 쿼리 결과를 Redis에 짧게 캐싱하는 데 사용 (CacheConfig).
	implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("io.jsonwebtoken:jjwt-api:0.13.0")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
	// Jackson3(tools.jackson) 기반 프로젝트라 jjwt의 기본 jjwt-jackson(Jackson2)과의 불필요한
	// 버전 충돌을 피하려고 gson 기반 구현체를 사용
	runtimeOnly("io.jsonwebtoken:jjwt-gson:0.13.0")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	// 로컬에 이미 떠 있는 docker-compose 인프라에 암묵적으로 의존하던 기존 테스트 방식 대신,
	// Testcontainers로 매 실행마다 격리된 인프라를 띄워 CI에서도 그대로 재현 가능하게 한다
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	// testcontainers 2.x부터 모듈 아티팩트명이 testcontainers-<module>로 바뀌었다
	// (예: postgresql -> testcontainers-postgresql). Boot 4.1.1이 관리하는 버전은 2.0.5
	testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
	testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
	testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")
	testImplementation("org.testcontainers:testcontainers-mongodb:2.0.5")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
