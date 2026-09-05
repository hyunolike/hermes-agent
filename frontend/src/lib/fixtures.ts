import type { Facts } from './schema'

/** 서버 `harness/fixtures/course-explanation-request.json` 이 만드는 facts 와 같은 모양. */
export const factsFixture: Facts = {
  items: [
    {
      attractionId: 1001,
      name: '경복궁',
      visitOrder: 1,
      timeLabel: '오전 10:00',
      grade: 'VERY_CROWDED',
      reason: '경복궁은 이 날 매우 붐비지만 첫 방문지로 두었어요.',
      travelMinutesFromPrev: null,
    },
    {
      attractionId: 1003,
      name: '북촌 한옥마을',
      visitOrder: 2,
      timeLabel: '오전 11:38',
      grade: 'NORMAL',
      reason: '이 날 붐비지 않아요.',
      travelMinutesFromPrev: 8,
    },
  ],
  alternatives: [
    {
      attractionId: 1003,
      name: '북촌 한옥마을',
      grade: 'NORMAL',
      concentration: 62,
      distanceKm: 1.2,
      relationScore: 0.8,
      score: 0.704,
      recommendReason: '가깝고 이 날 덜 붐벼요.',
      travelMinutes: 8,
    },
  ],
  targetDate: '2026-08-15',
  title: '경복궁 주변 코스',
  congestionReductionRate: 34,
  summary: '혼자 가는 것보다 혼잡도가 34% 낮아요.',
  recommendedDate: { date: '2026-08-19', congestionReductionRate: 41 },
  congestion: {
    hasCongestionData: true,
    concentration: 87.3,
    percentile: 92,
    grade: 'VERY_CROWDED',
    message: '경복궁은 이날 매우 붐빌 것으로 보여요.',
    betterDates: [{ date: '2026-08-19', concentration: 48.6, grade: 'RELAXED' }],
  },
}
