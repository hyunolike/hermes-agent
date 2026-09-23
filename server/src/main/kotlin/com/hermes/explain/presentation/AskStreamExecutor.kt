package com.hermes.explain.presentation

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/**
 * 스트림 전용 실행기.
 *
 * `factsExecutor`(스레드 4개)와 나눈다. 스트림 하나는 모델이 답하는 몇 초 동안 스레드를
 * 붙잡는데, 같은 풀을 쓰면 동시 스트림 몇 개가 한적 병렬 호출을 굶긴다 — 그리고 스트림
 * 자체가 한적 호출을 기다리므로 서로를 기다리는 교착이 된다.
 *
 * `ExecutorService` 빈을 하나 더 두면 타입으로 주입하던 곳이 모호해진다. 타입을 따로
 * 두어 그 모호함을 만들지 않는다. Spring 이 종료 시 [close] 를 부른다.
 */
class AskStreamExecutor(private val executor: ExecutorService) : Executor by executor, AutoCloseable {
    override fun close() = executor.shutdown()
}
