package com.hermes.context

/**
 * 번들을 LLM `system` 블록에 들어갈 하나의 문자열로 만든다.
 *
 * 지금은 원문을 그대로 쓴다 — build-bundle.sh 가 이미 선언된 순서로 결정론적으로
 * 조립했기 때문이다. 여기서 무언가를 덧붙이고 싶어지면, 그것이 요청마다 달라지지
 * 않는지 먼저 확인해야 한다. 한 바이트만 달라져도 캐시는 통째로 미스 난다.
 */
class PromptAssembler(bundle: Bundle) {
    val systemText: String = bundle.raw
}
