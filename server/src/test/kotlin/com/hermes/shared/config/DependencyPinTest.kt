package com.hermes.shared.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Type

/**
 * Spring AI 2.0.1 은 victools 5.0.0 을 끌어오지만, Anthropic SDK 의 스키마 유도는
 * 4.x 시그니처 `generateSchema(Type, Type[])` 를 부른다. 5.0.0 이 충돌에서 이기면
 * `outputConfig(Class)` 가 **런타임에** NoSuchMethodError 로 죽는다 — 컴파일은
 * 멀쩡하다. 이 저장소가 스키마를 손으로 쓰지 않고 SDK 유도에 맡긴 이유가
 * 드리프트 방지였으니, 핀이 풀리면 그 방어선이 조용히 무너진다.
 *
 * 반환 타입까지 확인하는 이유: victools 5.0.0 에도 파라미터 타입이
 * `(Type, Type[])` 인 `generateSchema` 메서드가 그대로 있다 — 달라지는 건
 * 반환 타입뿐이다. 4.x 는 Jackson 2 의 `com.fasterxml.jackson.databind.node.ObjectNode`
 * 를, 5.0.0 은 Jackson 3 의 `tools.jackson.databind.node.ObjectNode` 를 반환한다.
 * `Class.getMethod` 는 파라미터 타입만으로 메서드를 찾고 반환 타입은 비교하지
 * 않으므로, 반환 타입을 검증하지 않으면 이 테스트는 4.x/5.0.0 어느 쪽이
 * 해석되든 항상 통과한다 — 정확히 스파이크에서 실제로 터진
 * `NoSuchMethodError: 'com.fasterxml.jackson.databind.node.ObjectNode
 * SchemaGenerator.generateSchema(Type, Type[])'` 를 못 잡는다는 뜻이다. JVM 의
 * 메서드 링크는 반환 타입도 서술자의 일부로 보기 때문에, 반환 타입이 다르면
 * 호출부는 링크 시점에 NoSuchMethodError 로 죽는다 — `getMethod` 가 놓치는
 * 바로 그 지점이 실제 버그다.
 */
class DependencyPinTest {

    @Test
    fun `victools 는 4점대 시그니처를 유지한다`() {
        // 리플렉션으로 찾는다 — victools 는 anthropic-java 의 전이 의존이라 테스트
        // 컴파일 클래스패스에 노출된다는 보장이 없다. 런타임에는 반드시 있다.
        val generator = Class.forName("com.github.victools.jsonschema.generator.SchemaGenerator")

        val method = generator.getMethod("generateSchema", Type::class.java, Array<Type>::class.java)

        assertThat(method).isNotNull()
        // 파라미터 타입만으로는 4.x/5.x 를 구분할 수 없다 (둘 다 (Type, Type[]) 를
        // 받는다). 실제 차이는 반환 타입이고, 이것이 NoSuchMethodError 가 터지는
        // 지점이다 — 문자열로 비교해서 Jackson 클래스를 임포트하지 않는다.
        assertThat(method.returnType.name).isEqualTo("com.fasterxml.jackson.databind.node.ObjectNode")
    }
}
