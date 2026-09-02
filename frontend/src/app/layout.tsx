import type { Metadata } from 'next'
import Link from 'next/link'
import './globals.css'

export const metadata: Metadata = {
  title: 'Hermes Agent',
  description: '설명이 어디에서 왔는지 눌러서 확인할 수 있는 여행 컨텍스트 에이전트',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body className="min-h-screen antialiased">
        <header className="border-b border-black/10 dark:border-white/10">
          <nav className="mx-auto flex max-w-3xl items-baseline gap-6 px-6 py-4 text-sm">
            <Link href="/" className="font-semibold">
              Hermes Agent
            </Link>
            <Link href="/evidence" className="opacity-70 hover:opacity-100">
              근거 문서
            </Link>
          </nav>
        </header>
        <main className="mx-auto max-w-3xl px-6 py-10">{children}</main>
      </body>
    </html>
  )
}
