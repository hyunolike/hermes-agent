'use client'

import { useState } from 'react'
import { askCourse, type AskTurn } from '@/lib/agent'
import { CitationSheet } from './CitationSheet'

type Exchange = AskTurn & { citations: string[] } | { question: string; failed: true }

const SUGGESTIONS = ['왜 이 순서예요?', '왜 이 장소들이에요?', '다른 날이 더 나은가요?']

/**
 * 코스에 대해 이어 묻는다.
 *
 * **대화는 여기에만 있다.** 서버는 저장하지 않으므로 이전 turn 을 매 요청 함께
 * 보낸다 — 탭을 닫으면 사라지는 것이 "DB 없음"을 지킨 대가다.
 *
 * 실패한 질문도 목록에 남긴다. 사라지면 사용자는 자기가 뭘 물었는지 잃고, 답이
 * 없었다는 사실도 잃는다.
 */
export function AskBox({ courseUuid }: { courseUuid: string }) {
  const [exchanges, setExchanges] = useState<Exchange[]>([])
  const [question, setQuestion] = useState('')
  const [asking, setAsking] = useState(false)
  const [openPath, setOpenPath] = useState<string | null>(null)

  async function ask(text: string) {
    const trimmed = text.trim()
    if (!trimmed || asking) return

    setAsking(true)
    setQuestion('')
    // 이전 대화만 보낸다. 지금 질문은 따로 실린다.
    const history: AskTurn[] = exchanges
      .filter((exchange): exchange is AskTurn & { citations: string[] } => !('failed' in exchange))
      .map(({ question: q, answer }) => ({ question: q, answer }))

    const result = await askCourse(courseUuid, trimmed, history).catch(() => null)

    setExchanges((prev) => [
      ...prev,
      result && result.kind === 'loaded'
        ? { question: trimmed, answer: result.value.answer, citations: result.value.citations }
        : { question: trimmed, failed: true },
    ])
    setAsking(false)
  }

  return (
    <section className="space-y-4">
      <h2 className="text-sm font-semibold opacity-70">이 코스에 대해 더 묻기</h2>

      {exchanges.map((exchange, index) => (
        <div key={index} className="space-y-2 rounded-lg border border-black/10 p-4 dark:border-white/10">
          <p className="text-sm font-medium">{exchange.question}</p>
          {'failed' in exchange ? (
            <p className="text-sm opacity-70">답을 만들지 못했어요. 잠시 후 다시 물어봐 주세요.</p>
          ) : (
            <>
              <p className="whitespace-pre-wrap text-sm leading-relaxed opacity-90">{exchange.answer}</p>
              <div className="flex flex-wrap gap-2">
                {exchange.citations.map((path) => (
                  <button
                    key={path}
                    type="button"
                    onClick={() => setOpenPath(path)}
                    className="rounded-full border border-black/15 px-2.5 py-0.5 text-xs hover:bg-black/5 dark:border-white/20 dark:hover:bg-white/10"
                  >
                    {path}
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      ))}

      {exchanges.length === 0 && (
        <div className="flex flex-wrap gap-2">
          {SUGGESTIONS.map((suggestion) => (
            <button
              key={suggestion}
              type="button"
              onClick={() => ask(suggestion)}
              className="rounded-full border border-black/15 px-3 py-1 text-xs hover:bg-black/5 dark:border-white/20 dark:hover:bg-white/10"
            >
              {suggestion}
            </button>
          ))}
        </div>
      )}

      <form
        onSubmit={(event) => {
          event.preventDefault()
          ask(question)
        }}
        className="flex gap-2"
      >
        <label htmlFor="question" className="sr-only">
          이 코스에 대해 더 묻기
        </label>
        <input
          id="question"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="이 코스에 대해 물어보세요"
          className="min-w-0 flex-1 rounded border border-black/15 bg-transparent px-3 py-1.5 text-sm dark:border-white/20"
        />
        <button
          type="submit"
          disabled={asking || question.trim().length === 0}
          className="rounded border border-black/15 px-3 py-1.5 text-sm disabled:opacity-40 dark:border-white/20"
        >
          {asking ? '묻는 중…' : '묻기'}
        </button>
      </form>

      {openPath && <CitationSheet path={openPath} onClose={() => setOpenPath(null)} />}
    </section>
  )
}
