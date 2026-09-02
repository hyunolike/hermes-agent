import { describe, expect, it } from 'vitest'
import { DEMO_COURSES, missingKinds } from './demo-courses'

describe('데모 코스', () => {
  it('아직 비어 있다 — 한적에 코스를 만들어야 정해진다', () => {
    // 가짜 uuid 를 채워 두면 화면은 뜨는데 전부 503 이 나고, 서버 문제인지
    // uuid 문제인지 구분되지 않는다.
    expect(DEMO_COURSES).toEqual([])
  })

  it('빠진 종류를 센다', () => {
    expect(missingKinds([])).toHaveLength(3)
    expect(
      missingKinds([
        { uuid: 'a', label: 'a', kind: 'crowded-with-alternatives' },
        { uuid: 'b', label: 'b', kind: 'no-alternatives' },
        { uuid: 'c', label: 'c', kind: 'better-date' },
      ]),
    ).toEqual([])
  })

  it('같은 종류를 셋 넣어도 채워진 것으로 보지 않는다', () => {
    // 개수만 세면 종류가 겹친 데모가 통과한다. 그런 데모는 인용 검증이 실제로
    // 작동하는지 보여 주지 못한다.
    const sameKind = ['a', 'b', 'c'].map((uuid) => ({
      uuid,
      label: uuid,
      kind: 'better-date' as const,
    }))

    expect(missingKinds(sameKind)).toHaveLength(2)
  })
})
