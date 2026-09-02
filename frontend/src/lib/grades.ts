/**
 * 혼잡도 등급 4단계.
 *
 * 단계 수와 한국어 라벨은 이 화면의 것이 아니다 —
 * `records/congestion/grade-policy.json`(위키)이 정하고, 그 문서는 번들에 실려
 * 모델도 같은 것을 본다. 색만 화면의 몫이다. 다섯 번째 단계를 만들거나 "정상"
 * 같은 라벨을 새로 지어내면, 서버의 GRADE_MISLABEL 검사가 막고 있는 바로 그
 * 오류를 화면이 저지르는 셈이 된다.
 */
export const GRADES = ['RELAXED', 'NORMAL', 'CROWDED', 'VERY_CROWDED'] as const

export type Grade = (typeof GRADES)[number]

/** 백분위가 낮은 순. 코스 화면의 범례가 이 순서를 그대로 쓴다. */
export const GRADE_LABEL: Record<Grade, string> = {
  RELAXED: '여유',
  NORMAL: '보통',
  CROWDED: '혼잡',
  VERY_CROWDED: '매우혼잡',
}

/** 네 단계가 서로 구분되는 것이 요구사항이다 — 등급이 색으로도 읽혀야 한다. */
export const GRADE_STYLE: Record<Grade, string> = {
  RELAXED: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  NORMAL: 'bg-sky-50 text-sky-800 ring-sky-200',
  CROWDED: 'bg-amber-50 text-amber-900 ring-amber-200',
  VERY_CROWDED: 'bg-rose-50 text-rose-800 ring-rose-200',
}
