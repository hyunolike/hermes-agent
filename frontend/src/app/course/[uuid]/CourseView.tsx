import { GRADE_LABEL, GRADE_STYLE } from '@/lib/grades'
import type { Facts } from '@/lib/schema'

/**
 * 코스 자체. **설명 없이도 완전하다** — 각 항목의 `reason`은 한적이 규칙으로 붙인
 * 문구이고, LLM이 죽어도 그 문구는 그대로 있다. 이 컴포넌트가 설명을 인자로 받지
 * 않는 것이 스펙 §6.1의 "설명 블록만 사라진다"를 구조로 지키는 방법이다.
 */
export function CourseView({ facts }: { facts: Facts }) {
  const items = [...facts.items].sort((a, b) => a.visitOrder - b.visitOrder)

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold">{facts.title}</h1>
        <p className="text-sm opacity-70">
          {facts.targetDate} · 혼잡도 {facts.congestionReductionRate}% 낮음
        </p>
        <p className="text-sm opacity-80">{facts.summary}</p>
      </header>

      <ol className="space-y-3">
        {items.map((item) => (
          <li
            key={item.attractionId}
            className="rounded-lg border border-black/10 p-4 dark:border-white/10"
          >
            <div className="flex flex-wrap items-baseline gap-2">
              <span className="text-xs opacity-50">{item.visitOrder}</span>
              <span className="font-medium">{item.name}</span>
              {/* 예보가 없는 장소는 등급이 없다. 자리를 비우면 "등급이 뭐였더라"가
                  되고, 아무 색이나 넣으면 없는 진단을 있는 것처럼 만든다. */}
              {item.grade === null ? (
                <span className="rounded-full px-2 py-0.5 text-xs opacity-60 ring-1 ring-current">
                  예보 없음
                </span>
              ) : (
                <span
                  className={`rounded-full px-2 py-0.5 text-xs ring-1 ${GRADE_STYLE[item.grade]}`}
                >
                  {GRADE_LABEL[item.grade]}
                </span>
              )}
              <span className="text-xs opacity-60">{item.timeLabel}</span>
              {item.travelMinutesFromPrev !== null && (
                <span className="text-xs opacity-60">이동 {item.travelMinutesFromPrev}분</span>
              )}
            </div>
            <p className="mt-2 text-sm opacity-80">{item.reason}</p>
          </li>
        ))}
      </ol>

      {facts.alternatives.length === 0 && (
        // "점수가 후보를 떨어뜨렸다"와 "후보가 애초에 없었다"는 다른 말이다
        // (concepts/alternative-scoring.md). 한 문구로 덮으면 화면이 그 오해를 만든다.
        <p className="text-sm opacity-70">
          이 근처에는 대안으로 삼을 만한 조용한 실내 장소가 없었습니다. 점수가 낮아 밀린 것이
          아니라 후보 자체가 없었습니다.
        </p>
      )}
    </div>
  )
}
