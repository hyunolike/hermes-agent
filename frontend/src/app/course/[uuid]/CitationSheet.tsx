'use client'

import { useEffect, useState } from 'react'
import { fetchContextDocument, type Result } from '@/lib/agent'

/**
 * 인용 문서를 그 자리에서 연다. 화면을 떠나지 않는 것이 요점이다 — 근거를 보려고
 * 코스를 잃으면, 설명과 근거를 나란히 두고 볼 수 없다.
 */
export function CitationSheet({ path, onClose }: { path: string; onClose: () => void }) {
  const [state, setState] = useState<Result<string> | null>(null)

  useEffect(() => {
    let alive = true
    fetchContextDocument(path)
      .then((result) => alive && setState(result))
      .catch(() => alive && setState({ kind: 'unavailable', status: 0 }))
    return () => {
      alive = false
    }
  }, [path])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-10 flex items-end justify-center bg-black/40"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-label={path}
        className="max-h-[75vh] w-full max-w-3xl overflow-auto rounded-t-xl bg-[var(--background)] p-6"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="mb-4 flex items-baseline justify-between gap-4">
          <h2 className="font-mono text-sm">{path}</h2>
          <button type="button" onClick={onClose} className="text-sm opacity-60 hover:opacity-100">
            닫기
          </button>
        </div>

        {state === null && <p className="text-sm opacity-60">불러오는 중…</p>}
        {state?.kind === 'unavailable' && (
          // 빈 시트를 띄우면 "문서에 내용이 없다"로 읽힌다. 번들에 없다는 것은
          // 인용이 잘못되었다는 뜻이라 그 자체가 알려야 할 정보다.
          <p className="text-sm opacity-70">이 문서는 번들에 없습니다 ({state.status}).</p>
        )}
        {state?.kind === 'loaded' && (
          <pre className="overflow-x-auto whitespace-pre-wrap font-mono text-xs leading-relaxed">
            {state.value}
          </pre>
        )}
      </div>
    </div>
  )
}
