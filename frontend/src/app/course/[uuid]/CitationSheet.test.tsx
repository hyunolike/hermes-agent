import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as agent from '@/lib/agent'
import { CitationSheet } from './CitationSheet'

afterEach(() => vi.restoreAllMocks())

describe('인용 시트', () => {
  it('번들에 없는 문서면 그렇게 말한다', async () => {
    // 빈 시트는 "문서에 내용이 없다"로 읽힌다. 번들에 없다는 것은 인용이
    // 잘못되었다는 뜻이라 그 자체가 알려야 할 정보다.
    vi.spyOn(agent, 'fetchContextDocument').mockResolvedValue({ kind: 'unavailable', status: 404 })

    render(<CitationSheet path="nope.md" onClose={() => {}} />)

    expect(await screen.findByText(/번들에 없습니다/)).toBeInTheDocument()
  })

  it('Esc 로 닫힌다', async () => {
    vi.spyOn(agent, 'fetchContextDocument').mockResolvedValue({ kind: 'loaded', value: '본문' })
    const onClose = vi.fn()

    render(<CitationSheet path="a.md" onClose={onClose} />)
    await userEvent.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalled()
  })

  it('바깥을 누르면 닫히고 본문을 누르면 닫히지 않는다', async () => {
    vi.spyOn(agent, 'fetchContextDocument').mockResolvedValue({ kind: 'loaded', value: '본문' })
    const onClose = vi.fn()

    render(<CitationSheet path="a.md" onClose={onClose} />)
    await userEvent.click(await screen.findByRole('dialog'))
    expect(onClose).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('dialog').parentElement!)
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })
})
