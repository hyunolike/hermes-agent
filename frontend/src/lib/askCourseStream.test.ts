import { describe, expect, it } from 'vitest'
import { askCourseStream, type AskStreamEvent } from './agent'

/** 주어진 바이트 조각을 차례로 흘리는 fetch. */
function fetchStreaming(chunks: Uint8Array[], status = 200): typeof fetch {
  return async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          for (const chunk of chunks) controller.enqueue(chunk)
          controller.close()
        },
      }),
      { status, headers: { 'Content-Type': 'text/event-stream' } },
    )
}

const encode = (text: string) => new TextEncoder().encode(text)

async function collect(fetchImpl: typeof fetch): Promise<AskStreamEvent[]> {
  const events: AskStreamEvent[] = []
  await askCourseStream('c', 'q', [], (e) => events.push(e), fetchImpl)
  return events
}

const frames =
  'event:citations\ndata:{"citations":["a.md"]}\n\n' +
  'event:delta\ndata:{"text":"가나"}\n\n' +
  'event:delta\ndata:{"text":"다"}\n\n' +
  'event:done\ndata:{"generatedAt":"t","model":"gpt-4o"}\n\n'

describe('askCourseStream', () => {
  it('이벤트를 순서대로 낸다', async () => {
    const events = await collect(fetchStreaming([encode(frames)]))

    expect(events).toEqual([
      { kind: 'citations', citations: ['a.md'] },
      { kind: 'delta', text: '가나' },
      { kind: 'delta', text: '다' },
      { kind: 'done', generatedAt: 't', model: 'gpt-4o' },
    ])
  })

  it('프레임이 read 경계에 걸려도 같다', async () => {
    const bytes = encode(frames)
    for (let cut = 1; cut < bytes.length; cut++) {
      const events = await collect(fetchStreaming([bytes.slice(0, cut), bytes.slice(cut)]))
      expect(events.map((e) => e.kind), `cut=${cut}`).toEqual(['citations', 'delta', 'delta', 'done'])
      expect(
        events.flatMap((e) => (e.kind === 'delta' ? [e.text] : [])).join(''),
        `cut=${cut}`,
      ).toBe('가나다')
    }
  })

  it('done 없이 끝나면 본문을 받았으면 aborted', async () => {
    const events = await collect(
      fetchStreaming([encode('event:citations\ndata:{"citations":["a.md"]}\n\nevent:delta\ndata:{"text":"미"}\n\n')]),
    )

    expect(events.at(-1)).toEqual({ kind: 'aborted' })
  })

  it('done 없이 끝나면 본문을 못 받았으면 unavailable', async () => {
    const events = await collect(fetchStreaming([encode('event:citations\ndata:{"citations":["a.md"]}\n\n')]))

    expect(events.at(-1)).toEqual({ kind: 'unavailable' })
  })

  it('서버가 unavailable 을 보내면 그것으로 끝나고 덧붙이지 않는다', async () => {
    const events = await collect(
      fetchStreaming([encode('event:unavailable\ndata:{"code":"EXPLANATION_UNAVAILABLE"}\n\n')]),
    )

    expect(events).toEqual([{ kind: 'unavailable' }])
  })

  it('연결이 아예 안 되면 던지지 않고 unavailable', async () => {
    const events = await collect(async () => {
      throw new TypeError('fetch failed')
    })

    expect(events).toEqual([{ kind: 'unavailable' }])
  })

  it('4xx/5xx 면 unavailable', async () => {
    const events = await collect(fetchStreaming([], 503))

    expect(events).toEqual([{ kind: 'unavailable' }])
  })

  it('event: 없는 프레임(프레이밍 없이 붙은 에러 바디)은 무시하고 종결 이벤트로 끝난다', async () => {
    const events = await collect(
      fetchStreaming([
        encode(
          'event:citations\ndata:{"citations":["a.md"]}\n\n' +
            'event:delta\ndata:{"text":"본문"}\n\n' +
            '{"code":"EXPLANATION_UNAVAILABLE"}\n\n',
        ),
      ]),
    )

    expect(events).toEqual([
      { kind: 'citations', citations: ['a.md'] },
      { kind: 'delta', text: '본문' },
      { kind: 'aborted' },
    ])
  })
})
