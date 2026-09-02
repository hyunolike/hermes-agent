import { describe, expect, it } from 'vitest'
import { GRADES, GRADE_LABEL, GRADE_STYLE } from './grades'

describe('등급', () => {
  it('네 단계뿐이고 백분위 낮은 순이다', () => {
    // 위키의 grade-policy.json 이 정한 것이다. 다섯 번째를 만들면 화면이
    // 서버·모델과 다른 등급 체계를 갖게 된다.
    expect(GRADES).toEqual(['RELAXED', 'NORMAL', 'CROWDED', 'VERY_CROWDED'])
  })

  it('라벨을 새로 지어내지 않는다', () => {
    expect(Object.values(GRADE_LABEL)).toEqual(['여유', '보통', '혼잡', '매우혼잡'])
  })

  it('네 색이 서로 다르다', () => {
    // 같은 색이 둘이면 등급이 색으로 읽히지 않는데, 화면은 멀쩡해 보인다.
    expect(new Set(Object.values(GRADE_STYLE)).size).toBe(GRADES.length)
  })

  it('모든 등급에 라벨과 색이 있다', () => {
    for (const grade of GRADES) {
      expect(GRADE_LABEL[grade]).toBeTruthy()
      expect(GRADE_STYLE[grade]).toBeTruthy()
    }
  })
})
