package com.hermes.shared.config

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.hermes.context.Bundle
import com.hermes.context.BundleLoader
import com.hermes.context.CitationValidator
import com.hermes.context.PromptAssembler
import com.hermes.explain.CourseExplainer
import com.hermes.explain.ExplanationCache
import com.hermes.explain.ExplanationService
import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokClient
import com.hermes.facts.RestHanjeokClient
import com.hermes.llm.AnthropicExplanationProvider
import com.hermes.llm.ExplanationProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 애플리케이션 전역 배선. CORS 는 여기 두지 않는다 — `WebMvcConfigurer`/
 * `CorsRegistry` 는 인바운드 웹 타입이고, `ModuleBoundaryTest` 가 presentation
 * 밖에서 그것을 금지한다(예외는 아웃바운드 클라이언트 두 접두사뿐). CORS 설정은
 * `com.hermes.explain.presentation.CorsConfig` 에 있다.
 */
@Configuration
class HermesConfig {

    /** 부팅 시 1회 적재하고 그 뒤로는 파일을 읽지 않는다(08-17 설계문 §5). */
    @Bean
    fun bundle(): Bundle = BundleLoader.load()

    /**
     * `ExplainController` 는 Jackson 2 `ObjectMapper`(`com.fasterxml.jackson.databind`)를
     * 직접 받는다 — 웹 계층 컨버터가 Jackson 3(tools.jackson)라서 이 코드베이스가
     * 자동 구성하는 `ObjectMapper` 빈은 Jackson 3 타입뿐이고, Jackson 2
     * `ObjectMapper` 빈은 자동으로 생기지 않는다. 이 빈이 없으면 컨텍스트가
     * `explainController` 생성에서 `NoSuchBeanDefinitionException`으로 뜨지
     * 않는다(ApplicationContextTest 로 실측 확인).
     */
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()

    @Bean
    fun promptAssembler(bundle: Bundle): PromptAssembler = PromptAssembler(bundle)

    @Bean
    fun citationValidator(bundle: Bundle): CitationValidator = CitationValidator(bundle)

    @Bean
    fun explanationProvider(): ExplanationProvider =
        AnthropicExplanationProvider(AnthropicOkHttpClient.fromEnv())

    @Bean
    fun hanjeokRestClient(
        @Value("\${hermes.hanjeok.base-url}") baseUrl: String,
        @Value("\${hermes.hanjeok.timeout-seconds}") timeoutSeconds: Long,
    ): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(timeoutSeconds))
                setReadTimeout(Duration.ofSeconds(timeoutSeconds))
            },
        )
        .build()

    @Bean
    fun hanjeokClient(hanjeokRestClient: RestClient): HanjeokClient = RestHanjeokClient(hanjeokRestClient)

    /** 병렬 호출은 둘뿐이다. 스레드를 넉넉히 잡을 이유가 없다. */
    @Bean(destroyMethod = "shutdown")
    fun factsExecutor(): ExecutorService = Executors.newFixedThreadPool(4)

    @Bean
    fun factsSource(
        hanjeokClient: HanjeokClient,
        @Value("\${hermes.hanjeok.radius-km}") radiusKm: Int,
        factsExecutor: ExecutorService,
    ): FactsSource = FactsSource(hanjeokClient, radiusKm, factsExecutor)

    @Bean
    fun explanationCache(): ExplanationCache = ExplanationCache()

    @Bean
    fun explanationService(
        promptAssembler: PromptAssembler,
        citationValidator: CitationValidator,
        explanationProvider: ExplanationProvider,
    ): ExplanationService = ExplanationService(promptAssembler, citationValidator, explanationProvider)

    @Bean
    fun courseExplainer(
        factsSource: FactsSource,
        explanationService: ExplanationService,
        explanationCache: ExplanationCache,
    ): CourseExplainer = CourseExplainer(factsSource, explanationService, explanationCache)

    @Bean
    fun demoCourses(@Value("\${hermes.demo.courses}") raw: String): DemoCourses = DemoCourses.parse(raw)
}
