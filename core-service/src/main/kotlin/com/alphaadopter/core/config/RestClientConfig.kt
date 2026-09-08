package com.alphaadopter.core.config

import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.client.RestClient

// 이 프로젝트의 Spring Boot 버전은 RestClient.Builder를 자동으로 빈 등록해주지 않아 직접
// 제공한다. prototype 스코프로 등록해 주입받는 쪽마다 독립된 빌더 인스턴스를 받도록 한다 —
// RestClient.Builder는 baseUrl() 등을 호출할 때 내부 상태를 변경하는 가변 빌더라서, 싱글턴으로
// 공유하면 서로 다른 컴포넌트의 설정(baseUrl 등)이 뒤섞일 수 있다.
@Configuration
class RestClientConfig {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()
}
