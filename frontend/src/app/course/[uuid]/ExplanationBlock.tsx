'use client'

import { useEffect, useState } from 'react'
import { fetchExplanation, type Result } from '@/lib/agent'
import type { ExplainResponse } from '@/lib/schema'
import { CitationSheet } from './CitationSheet'

type State = { status: 'loading' } | { status: 'done'; result: Result<ExplainResponse> }

/**
 * 설명 블록. 코스가 그려진 **뒤에** 도착한다.
 *
 * 실패하면 이 블록만 사라진다 — 위의 코스는 이미 화면에 있고 한적의 규칙 기반
 * 문구가 각 항목에 붙어 있으므로, 설명이 없어도 읽을 것이 남는다.
 */
export function ExplanationBlock({ courseUuid }: { courseUuid: string }) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [openPath, setOpenPath] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    fetchExplanation(courseUuid)
      .then((result) => alive && setState({ status: 'done', result }))
      // 파싱 실패도 화면을 무너뜨리지 않는다. 다만 "설명 없음"과 구별되도록
      // 상태 0 으로 남겨 둔다 — 서버가 모양을 바꾼 것을 조용한 성공으로 읽지 않는다.
      .catch(() => alive && setState({ status: 'done', result: { kind: 'unavailable', status: 0 } }))
    return () => {
      alive = false
    }
  }, [courseUuid])

  if (state.status === 'loading') {
    return (
      <section aria-busy="true" className="space-y-2">
        <div className="h-4 w-40 animate-pulse rounded bg-black/10 dark:bg-white/10" />
        <div className="h-4 w-full animate-pulse rounded bg-black/10 dark:bg-white/10" />
      </section>
    )
  }

  // 설명이 없으면 이 블록 자체가 없다. 자리를 비워 두거나 사과문을 띄우지 않는다 —
  // 코스는 이미 완전하고, 없는 것을 굳이 알릴 이유가 없다.
  if (state.result.kind === 'unavailable') return null

  const { explanation, citations, model } = state.result.value

  return (
    <section className="space-y-4 rounded-lg border border-black/10 p-5 dark:border-white/10">
      <p className="whitespace-pre-wrap leading-relaxed">{explanation}</p>

      <div className="flex flex-wrap items-center gap-2">
        {/* 칩은 citations 배열에서만 나온다 — 모델이 실제로 인용한 것만 뜬다. */}
        {citations.map((path) => (
          <button
            key={path}
            type="button"
            onClick={() => setOpenPath(path)}
            className="rounded-full border border-black/15 px-3 py-1 text-xs hover:bg-black/5 dark:border-white/20 dark:hover:bg-white/10"
          >
            {path}
          </button>
        ))}
        <span className="ml-auto text-xs opacity-50">{model}</span>
      </div>

      {openPath && <CitationSheet path={openPath} onClose={() => setOpenPath(null)} />}
    </section>
  )
}
