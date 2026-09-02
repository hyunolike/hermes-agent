import Link from 'next/link'
import { DEMO_COURSES, missingKinds } from '@/lib/demo-courses'

export default function Home() {
  const missing = missingKinds()

  return (
    <div className="space-y-8">
      <section className="space-y-3">
        <h1 className="text-2xl font-semibold">코스 설명</h1>
        <p className="text-sm leading-relaxed opacity-80">
          한적이 만든 코스에, 위키의 근거 문서만 보고 쓴 설명을 붙입니다. 설명 아래 인용을
          누르면 모델이 실제로 본 문서가 그 자리에서 열립니다.
        </p>
      </section>

      {DEMO_COURSES.length === 0 ? (
        <section className="rounded-lg border border-dashed border-black/20 p-6 text-sm dark:border-white/20">
          <p className="font-medium">데모 코스가 아직 설정되지 않았습니다.</p>
          <p className="mt-2 opacity-70">
            한적에 코스를 만들고 uuid를 <code>src/lib/demo-courses.ts</code>에 넣으면 여기에
            뜹니다. 아직 채워지지 않은 종류: {missing.join(', ')}
          </p>
        </section>
      ) : (
        <ul className="space-y-3">
          {DEMO_COURSES.map((course) => (
            <li key={course.uuid}>
              <Link
                href={`/course/${course.uuid}`}
                className="block rounded-lg border border-black/10 p-4 hover:bg-black/5 dark:border-white/10 dark:hover:bg-white/5"
              >
                <span className="font-medium">{course.label}</span>
                <span className="ml-2 text-xs opacity-60">{course.kind}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
