/**
 * 요청 시점에 그린다. 빌드 때 그리면 Next 가 백엔드를 부르고, 그러면 프론트 배포가
 * 백엔드가 떠 있어야만 성공한다 — 실제로 첫 빌드가 ECONNREFUSED 로 죽었다.
 * 게다가 사실은 매 요청 백엔드에서 와야 한다(스펙 §7).
 */
export const dynamic = 'force-dynamic'

import { fetchFacts } from '@/lib/agent'
import { CourseView } from './CourseView'
import { AskBox } from './AskBox'
import { ExplanationBlock } from './ExplanationBlock'

/**
 * 사실과 설명을 **따로** 받는다.
 *
 * 하나로 받으면 LLM 왕복(3~5초)이 끝나야 코스가 그려지고, LLM이 죽으면 코스도 함께
 * 사라진다. 스펙 §6.1이 약속한 "설명 블록만 사라진다"는 이 분리 없이는 지킬 수 없다.
 */
export default async function CoursePage({ params }: { params: Promise<{ uuid: string }> }) {
  const { uuid } = await params
  const facts = await fetchFacts(uuid)

  if (facts.kind === 'unavailable') {
    return (
      <div className="space-y-2">
        <h1 className="text-xl font-semibold">코스를 불러오지 못했습니다</h1>
        <p className="text-sm opacity-70">
          한적에서 사실을 받지 못했습니다({facts.status}). 코스가 삭제되었거나 백엔드가
          내려가 있습니다.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-8">
      <CourseView facts={facts.value} />
      <ExplanationBlock courseUuid={uuid} />
      <AskBox courseUuid={uuid} />
    </div>
  )
}
