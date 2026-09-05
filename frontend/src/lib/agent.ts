import {
  askResponseSchema,
  contextListSchema,
  explainResponseSchema,
  factsResponseSchema,
  type AskResponse,
  type ContextEntry,
  type ExplainResponse,
  type Facts,
} from './schema'

/**
 * 에이전트 서버 클라이언트.
 *
 * 프론트가 아는 백엔드는 이것 하나다. 한적 주소는 여기 없고, 앞으로도 없어야
 * 한다 — 사실의 출처가 두 곳이 되면 "설명이 근거로 삼은 것"과 "화면이 그린 것"이
 * 갈라질 수 있고, 그 순간 인용 화면은 아무것도 증명하지 못한다.
 */
export const AGENT_BASE_URL =
  process.env.NEXT_PUBLIC_AGENT_BASE_URL ?? 'http://localhost:8080'

/** 설명이 없는 것은 다뤄야 할 정상 상태다 — 예외로 던지지 않고 값으로 돌려준다. */
export type Unavailable = { kind: 'unavailable'; status: number }
export type Loaded<T> = { kind: 'loaded'; value: T }
export type Result<T> = Loaded<T> | Unavailable

type Fetch = typeof fetch

async function get<T>(
  path: string,
  parse: (raw: unknown) => T,
  init: RequestInit & { fetchImpl?: Fetch } = {},
): Promise<Result<T>> {
  const { fetchImpl = fetch, ...rest } = init

  // 서버가 아예 안 뜬 경우(ECONNREFUSED)는 예외로 온다. 503 과 같은 상태이므로
  // 같은 값으로 만든다 — 여기서 던지면 백엔드가 내려간 것이 화면 전체를 무너뜨린다.
  let response: Response
  try {
    response = await fetchImpl(`${AGENT_BASE_URL}${path}`, rest)
  } catch {
    return { kind: 'unavailable', status: 0 }
  }

  if (!response.ok) return { kind: 'unavailable', status: response.status }

  // 파싱 실패는 삼키지 않는다. 서버가 모양을 바꾼 것을 "설명 없음"으로 덮으면,
  // 고장난 배포가 조용한 화면으로 보인다.
  return { kind: 'loaded', value: parse(await response.json()) }
}

export function fetchFacts(courseUuid: string, fetchImpl?: Fetch): Promise<Result<Facts>> {
  return get(
    `/agent/facts/${encodeURIComponent(courseUuid)}`,
    (raw) => factsResponseSchema.parse(raw).facts,
    { cache: 'no-store', fetchImpl },
  )
}

export function fetchExplanation(
  courseUuid: string,
  fetchImpl?: Fetch,
): Promise<Result<ExplainResponse>> {
  return get('/agent/explain', (raw) => explainResponseSchema.parse(raw), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ courseUuid }),
    cache: 'no-store',
    fetchImpl,
  })
}

/** 서버는 대화를 저장하지 않는다 — 이전 turn 을 매 요청 함께 보낸다. */
export type AskTurn = { question: string; answer: string }

export function askCourse(
  courseUuid: string,
  question: string,
  history: AskTurn[],
  fetchImpl?: Fetch,
): Promise<Result<AskResponse>> {
  return get('/agent/ask', (raw) => askResponseSchema.parse(raw), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ courseUuid, question, history }),
    cache: 'no-store',
    fetchImpl,
  })
}

export function fetchContextList(fetchImpl?: Fetch): Promise<Result<ContextEntry[]>> {
  return get('/agent/context', (raw) => contextListSchema.parse(raw), { fetchImpl })
}

/** 근거 문서 본문. 모델이 본 바로 그 바이트라 JSON 이 아니라 text 다. */
export async function fetchContextDocument(
  path: string,
  fetchImpl: Fetch = fetch,
): Promise<Result<string>> {
  let response: Response
  try {
    response = await fetchImpl(`${AGENT_BASE_URL}/agent/context/${path}`)
  } catch {
    return { kind: 'unavailable', status: 0 }
  }
  if (!response.ok) return { kind: 'unavailable', status: response.status }
  return { kind: 'loaded', value: await response.text() }
}
