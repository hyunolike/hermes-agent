package com.hermes.explain

/**
 * 한적에서 온 사실. 계획 1 에서는 픽스처로 주입되고, 계획 2 에서 facts 패키지가
 * 실제 호출 3회(왕복 2회)로 채운다.
 *
 * json 을 문자열로 들고 다니는 이유: 이 값이 매 요청 달라지는 부분이고,
 * 캐시 접두사 뒤의 user 턴에 그대로 들어간다. 중간에서 재직렬화하면 키 순서가
 * 흔들려 비교가 어려워진다.
 */
data class BackendFacts(val courseUuid: String, val json: String)
