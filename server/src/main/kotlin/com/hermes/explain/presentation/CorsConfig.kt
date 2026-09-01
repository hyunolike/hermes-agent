package com.hermes.explain.presentation

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * CORS 는 인바운드 웹 관심사다 — `WebMvcConfigurer`/`CorsRegistry` 는
 * presentation 밖에서 금지된다(`ModuleBoundaryTest`). `HermesConfig` 가 아니라
 * 여기 둔다.
 */
@Configuration
class CorsConfig(
    @param:Value("\${hermes.cors.allowed-origins}") private val allowedOrigins: String,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/agent/**")
            .allowedOrigins(*allowedOrigins.split(",").map { it.trim() }.toTypedArray())
            .allowedMethods("GET", "POST")
    }
}
