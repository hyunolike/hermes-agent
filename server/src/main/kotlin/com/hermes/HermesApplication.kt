package com.hermes

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulith

/**
 * `@Modulith` 는 현재 아무것도 강제하지 않는다 — 선언된 모듈이 없어서(모듈은
 * 패키지 구조로 암묵 선언되는데, 이 코드베이스는 그 경계를 spring-modulith 의
 * 모듈 인식 방식으로 나누지 않았다) inert 하다. 실제 경계 강제는
 * `ModuleBoundaryTest`(소스를 직접 읽어 presentation 밖의 인바운드 웹 타입을
 * 금지하는 테스트)가 한다 — 이 애노테이션이 뭔가 검사해 준다고 가정하지 말 것.
 */
@Modulith
@SpringBootApplication
class HermesApplication

fun main(args: Array<String>) {
    runApplication<HermesApplication>(*args)
}
