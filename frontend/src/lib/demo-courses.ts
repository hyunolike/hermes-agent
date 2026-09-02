/**
 * 고정 데모 코스(스펙 §7).
 *
 * 쇼케이스에는 코스를 만드는 화면이 없다. 한적에 미리 만들어 둔 코스를 상수로
 * 둔다 — 서버는 이 uuid 들을 특별 취급하지 않는다.
 *
 * **수가 아니라 종류가 기준이다.** 셋은 서로 다른 설명을 요구해야 하고, 그래서
 * 각각 번들의 다른 문서를 인용하게 된다. 인용 검증이 실제로 작동하는지가 거기서
 * 드러난다. `kind` 를 주석이 아니라 타입으로 둔 이유가 이것이다 — 같은 종류
 * 셋을 넣으면 개수는 채워지지만 데모는 아무것도 증명하지 못한다.
 *
 * **아직 비어 있다.** 한적에 코스를 만들어야 uuid 가 정해진다. 가짜 uuid 를 채워
 * 두면 화면은 뜨는데 전부 503 이 나고, 그게 서버 문제인지 uuid 문제인지 구분되지
 * 않는다. 비어 있는 것을 화면이 정직하게 말하는 편이 낫다.
 */
export type DemoKind =
  /** 목적지가 매우혼잡이고 대안이 붙은 코스. */
  | 'crowded-with-alternatives'
  /** 대안이 비어 있는 코스 — 점수가 떨어뜨린 게 아니라 후보가 애초에 없었다. */
  | 'no-alternatives'
  /** recommendedDate 가 다른 날을 가리키는 코스. */
  | 'better-date'

export type DemoCourse = { uuid: string; label: string; kind: DemoKind }

export const DEMO_COURSES: DemoCourse[] = []

/** 세 종류가 다 있는가. 개수보다 이쪽이 데모의 조건이다. */
export function missingKinds(courses: DemoCourse[] = DEMO_COURSES): DemoKind[] {
  const present = new Set(courses.map((course) => course.kind))
  return (['crowded-with-alternatives', 'no-alternatives', 'better-date'] as const).filter(
    (kind) => !present.has(kind),
  )
}
