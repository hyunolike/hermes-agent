import { z } from 'zod'
import { GRADES } from './grades'

/**
 * 서버 응답을 해석하는 유일한 지점.
 *
 * 화면이 `any`를 받아 곧바로 그리면, 서버가 필드 이름을 바꿔도 화면은 빈 칸을
 * 그리며 멀쩡한 척한다. 여기서 파싱을 실패시키면 그 순간에 드러난다.
 *
 * 필드는 서버 `FactsProjection`이 확정한 것을 그대로 따른다 — 프론트가 목록을
 * 새로 정하면 두 곳이 갈라지고, 갈라진 것을 알려 주는 것이 아무것도 없다.
 */
export const gradeSchema = z.enum(GRADES)

export const courseItemSchema = z.object({
  attractionId: z.number(),
  name: z.string(),
  visitOrder: z.number(),
  timeLabel: z.string(),
  grade: gradeSchema,
  reason: z.string(),
  // 첫 방문지는 이전 장소가 없다.
  travelMinutesFromPrev: z.number().nullable(),
})

export const alternativeSchema = z.object({
  attractionId: z.number(),
  name: z.string(),
  grade: gradeSchema,
  concentration: z.number(),
  distanceKm: z.number(),
  relationScore: z.number(),
  score: z.number(),
  recommendReason: z.string(),
  travelMinutes: z.number(),
})

export const congestionSchema = z.object({
  concentration: z.number(),
  percentile: z.number(),
  grade: gradeSchema,
  message: z.string(),
  betterDates: z.array(
    z.object({ date: z.string(), concentration: z.number(), grade: gradeSchema }),
  ),
})

export const factsSchema = z.object({
  items: z.array(courseItemSchema),
  // 빈 배열은 "점수가 후보를 떨어뜨렸다"가 아니라 "조용한 실내 후보가 애초에
  // 없었다"는 뜻이다(concepts/alternative-scoring.md). 화면이 이 둘을 같은
  // 문구로 덮으면 위키가 막으려는 오해를 화면이 만든다.
  alternatives: z.array(alternativeSchema),
  targetDate: z.string(),
  title: z.string(),
  congestionReductionRate: z.number(),
  summary: z.string(),
  recommendedDate: z
    .object({ date: z.string(), congestionReductionRate: z.number() })
    .nullable(),
  congestion: congestionSchema,
})

export const factsResponseSchema = z.object({
  courseUuid: z.string(),
  facts: factsSchema,
})

export const explainResponseSchema = z.object({
  explanation: z.string(),
  citations: z.array(z.string()),
  facts: factsSchema,
  generatedAt: z.string(),
  model: z.string(),
})

export const contextEntrySchema = z.object({ path: z.string(), bytes: z.number() })
export const contextListSchema = z.array(contextEntrySchema)

export type Facts = z.infer<typeof factsSchema>
export type CourseItem = z.infer<typeof courseItemSchema>
export type ExplainResponse = z.infer<typeof explainResponseSchema>
export type ContextEntry = z.infer<typeof contextEntrySchema>
