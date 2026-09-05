import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as agent from '@/lib/agent'
import { AskBox } from './AskBox'

const answered = (answer: string, citations: string[] = ['concepts/course-generation-policy.md']) =>
  ({
    kind: 'loaded' as const,
    value: { answer, citations, generatedAt: '2026-09-05T00:00:00Z', model: 'gpt-4o' },
  })

afterEach(() => vi.restoreAllMocks())

describe('코스 후속 질문', () => {
  it('질문을 보내면 답과 인용이 그려진다', async () => {
    vi.spyOn(agent, 'askCourse').mockResolvedValue(answered('이동 시간이 가장 짧아요.'))

    render(<AskBox courseUuid="abc" />)
    await userEvent.type(screen.getByLabelText('이 코스에 대해 더 묻기'), '왜 이 순서예요?')
    await userEvent.click(screen.getByRole('button', { name: '묻기' }))

    expect(await screen.findByText('이동 시간이 가장 짧아요.')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'concepts/course-generation-policy.md' }),
    ).toBeInTheDocument()
  })

  it('이전 대화를 함께 보낸다', async () => {
    // 서버가 대화를 저장하지 않으므로, 안 보내면 "거기"가 무엇인지 알 수 없다.
    const ask = vi
      .spyOn(agent, 'askCourse')
      .mockResolvedValueOnce(answered('이동 시간 때문이에요.'))
      .mockResolvedValueOnce(answered('네, 붐벼요.'))

    render(<AskBox courseUuid="abc" />)
    const input = screen.getByLabelText('이 코스에 대해 더 묻기')

    await userEvent.type(input, '왜 이 순서예요?')
    await userEvent.click(screen.getByRole('button', { name: '묻기' }))
    await screen.findByText('이동 시간 때문이에요.')

    await userEvent.type(input, '거기는 붐비나요?')
    await userEvent.click(screen.getByRole('button', { name: '묻기' }))
    await screen.findByText('네, 붐벼요.')

    expect(ask.mock.calls[1][2]).toEqual([
      { question: '왜 이 순서예요?', answer: '이동 시간 때문이에요.' },
    ])
  })

  it('실패한 질문도 목록에 남는다', async () => {
    // 사라지면 사용자는 자기가 뭘 물었는지도, 답이 없었다는 사실도 잃는다.
    vi.spyOn(agent, 'askCourse').mockResolvedValue({ kind: 'unavailable', status: 503 })

    render(<AskBox courseUuid="abc" />)
    await userEvent.type(screen.getByLabelText('이 코스에 대해 더 묻기'), '왜요?')
    await userEvent.click(screen.getByRole('button', { name: '묻기' }))

    expect(await screen.findByText(/답을 만들지 못했어요/)).toBeInTheDocument()
    expect(screen.getByText('왜요?')).toBeInTheDocument()
  })

  it('실패한 답은 다음 질문의 대화 기록에 넣지 않는다', async () => {
    // 없는 답을 맥락으로 보내면 모델이 그것을 이미 한 말로 읽는다.
    const ask = vi
      .spyOn(agent, 'askCourse')
      .mockResolvedValueOnce({ kind: 'unavailable', status: 503 })
      .mockResolvedValueOnce(answered('답'))

    render(<AskBox courseUuid="abc" />)
    const input = screen.getByLabelText('이 코스에 대해 더 묻기')

    await userEvent.type(input, '첫 질문')
    await userEvent.click(screen.getByRole('button', { name: '묻기' }))
    await screen.findByText(/답을 만들지 못했어요/)

    await userEvent.type(input, '둘째 질문')
    await userEvent.click(screen.getByRole('button', { name: '묻기' }))
    await screen.findByText('답')

    expect(ask.mock.calls[1][2]).toEqual([])
  })

  it('빈 질문은 보내지 않는다', async () => {
    const ask = vi.spyOn(agent, 'askCourse')

    render(<AskBox courseUuid="abc" />)
    await userEvent.type(screen.getByLabelText('이 코스에 대해 더 묻기'), '   ')

    expect(screen.getByRole('button', { name: '묻기' })).toBeDisabled()
    expect(ask).not.toHaveBeenCalled()
  })

  it('추천 질문을 누르면 그대로 묻는다', async () => {
    const ask = vi.spyOn(agent, 'askCourse').mockResolvedValue(answered('답'))

    render(<AskBox courseUuid="abc" />)
    await userEvent.click(screen.getByRole('button', { name: '왜 이 순서예요?' }))

    await waitFor(() => expect(ask.mock.calls[0][1]).toBe('왜 이 순서예요?'))
  })
})
