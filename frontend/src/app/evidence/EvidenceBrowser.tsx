'use client'

import { useEffect, useState } from 'react'
import { fetchContextDocument, type Result } from '@/lib/agent'
import type { ContextEntry } from '@/lib/schema'

/**
 * 번들에 담긴 문서 전부. **위키 전체가 아니다.**
 *
 * 이 화면이 증명하려는 것은 "LLM이 볼 수 있었던 것이 이만큼"이라는 사실이고,
 * 총 바이트를 함께 내는 것이 "벡터 검색이 필요 없는 크기"라는 주장을 화면이
 * 스스로 보이게 하는 방법이다.
 */
export function EvidenceBrowser({ entries }: { entries: ContextEntry[] }) {
  const [selected, setSelected] = useState(entries[0]?.path ?? null)
  const [body, setBody] = useState<Result<string> | null>(null)

  useEffect(() => {
    if (selected === null) return
    let alive = true
    setBody(null)
    fetchContextDocument(selected)
      .then((result) => alive && setBody(result))
      .catch(() => alive && setBody({ kind: 'unavailable', status: 0 }))
    return () => {
      alive = false
    }
  }, [selected])

  const totalBytes = entries.reduce((sum, entry) => sum + entry.bytes, 0)

  return (
    <div className="space-y-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">근거 문서</h1>
        <p className="text-sm opacity-70">
          문서 {entries.length}개 · 전체 {totalBytes.toLocaleString()}바이트. 모델은 매 요청
          이 전부를 봅니다.
        </p>
      </header>

      <div className="grid gap-6 md:grid-cols-[minmax(0,16rem)_1fr]">
        <ul className="space-y-1 text-sm">
          {entries.map((entry) => (
            <li key={entry.path}>
              <button
                type="button"
                onClick={() => setSelected(entry.path)}
                aria-current={entry.path === selected}
                className={`w-full rounded px-2 py-1.5 text-left font-mono text-xs hover:bg-black/5 dark:hover:bg-white/10 ${
                  entry.path === selected ? 'bg-black/5 dark:bg-white/10' : ''
                }`}
              >
                <span className="block truncate">{entry.path}</span>
                <span className="opacity-50">{entry.bytes.toLocaleString()}B</span>
              </button>
            </li>
          ))}
        </ul>

        <div className="min-w-0">
          {body === null && <p className="text-sm opacity-60">불러오는 중…</p>}
          {body?.kind === 'unavailable' && (
            <p className="text-sm opacity-70">문서를 불러오지 못했습니다 ({body.status}).</p>
          )}
          {body?.kind === 'loaded' && (
            <pre className="overflow-x-auto whitespace-pre-wrap font-mono text-xs leading-relaxed">
              {body.value}
            </pre>
          )}
        </div>
      </div>
    </div>
  )
}
