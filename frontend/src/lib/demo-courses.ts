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
 * 셋 다 2026-09-12 기준으로 한적에 실제로 만들어 둔 코스다. 코스가 삭제되면 데모가
 * 깨지는데, 그건 결함이 아니라 "사실은 백엔드에서만 온다"를 지킨 대가다 —
 * `./gradlew demoReachability` 가 조용히 깨지지 않게 감시한다.
 */
export type DemoKind =
  /** 예보 커버리지가 없어 목적지 진단 자체가 없는 코스. */
  | 'no-forecast'
  /** 대안 3개가 다 붙어 네 정거장이 되는 코스. */
  | 'four-stops'
  /** 목적지가 매우혼잡이고 대안이 붙은 코스. */
  | 'crowded-with-alternatives'
  /** 대안이 비어 있는 코스 — 점수가 떨어뜨린 게 아니라 후보가 애초에 없었다. */
  | 'no-alternatives'
  /** recommendedDate 가 다른 날을 가리키는 코스. */
  | 'better-date'

export type DemoCourse = { uuid: string; label: string; kind: DemoKind }

export const DEMO_COURSES: DemoCourse[] = [
  {
    uuid: '129efdef-41ec-4044-ac87-303abe4ccdde',
    label: '매우 붐비는 덕수궁을 피하는 하루',
    kind: 'crowded-with-alternatives',
  },
  {
    // 세 장소가 전부 매우혼잡이라 혼잡도 감소율이 0% 다. 설명이 줄지 않은 혼잡도를
    // 줄었다고 말하면 안 되는 코스 — 이 데모가 실제로 무엇을 검증하는지 보여 준다.
    uuid: 'e66302c6-b413-4a07-88a5-3bd55e61fc2a',
    label: '대안이 없는 경복궁 코스',
    kind: 'no-alternatives',
  },
  {
    uuid: '5a2eb601-1f2b-499b-83eb-8a0093f4817e',
    label: '다른 날을 권하는 국립중앙박물관 코스',
    kind: 'better-date',
  },
  {
    // 목적지에 예보가 아예 없다. 등급도 백분위도 없는데 코스는 여전히 코스다 —
    // 없는 진단을 지어내지 않는지가 여기서 드러난다. 서버가 이 분기를 다루기
    // 전에는 이 코스가 503 이었다.
    uuid: 'b0719488-c2c7-4256-bc83-e8b2cf5734cd',
    label: '예보가 없는 경복궁 코스',
    kind: 'no-forecast',
  },
  {
    uuid: 'dfdb93f6-bfc3-478d-8a14-f9fde88e9c2a',
    label: '네 곳을 도는 덕수궁 코스',
    kind: 'four-stops',
  },
]

/** 세 종류가 다 있는가. 개수보다 이쪽이 데모의 조건이다. */
export function missingKinds(courses: DemoCourse[] = DEMO_COURSES): DemoKind[] {
  const present = new Set(courses.map((course) => course.kind))
  return (
    ['crowded-with-alternatives', 'no-alternatives', 'better-date', 'no-forecast', 'four-stops'] as const
  ).filter((kind) => !present.has(kind))
}
