# 배포

서버는 Cloud Run, 화면은 Vercel. 상태도 DB도 없어서 0으로 스케일다운된다.

**이 문서는 사람이 실행한다.** GCP 프로젝트, Vercel 팀, Secret Manager에 키를 넣는 일은
승인이 필요한 행위다. 저장소에 있는 것은 그 승인이 떨어지면 그대로 도는 산출물이다 —
`Dockerfile`, `.github/workflows/build.yml`, 그리고 이 문서.

## 환경 변수

서버가 실제로 읽는 전부다. 이 목록이 `application.yml`과 어긋나면 배포가 조용히
기본값으로 돈다.

| 변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `HANJEOK_BASE_URL` | **예** | 없음 | 한적 백엔드 주소. 기본값을 두지 않는다 — 빠뜨리면 기동이 이름을 지목하며 실패한다 |
| `HERMES_LLM_PROVIDER` | 아니오 | `anthropic` | `anthropic` · `openai` · `openrouter`. 하네스 `./gradlew eval <이름>`과 같은 이름이다 |
| `HERMES_LLM_MODEL` | 아니오 | `claude-opus-5` | 프로바이더에 맞는 모델 이름 |
| `ANTHROPIC_API_KEY` | 조건부 | 없음 | `provider=anthropic`일 때. 없으면 서버는 뜨지만 `/actuator/health`가 DOWN |
| `OPENAI_API_KEY` | 조건부 | 없음 | `provider=openai`일 때. 없으면 **기동이 멈춘다** |
| `OPENROUTER_API_KEY` | 조건부 | 없음 | `provider=openrouter`일 때. 없으면 기동이 멈춘다 |
| `HERMES_CORS_ALLOWED_ORIGINS` | **예(운영)** | `http://localhost:3000` | Vercel 도메인. 빠뜨리면 화면은 열리는데 모든 요청이 막힌다 |
| `HERMES_DEMO_COURSES` | 아니오 | 비어 있음 | `uuid\|라벨, uuid\|라벨`. `demoReachability` 태스크가 쓴다 |
| `PORT` | 아니오 | `8080` | Cloud Run이 준다 |

> **키를 이미지에 굽지 않는다.** `.dockerignore`가 `.env`를 막지만, 그건 마지막 방어선이지
> 첫 번째가 아니다. 키는 Secret Manager에서 환경 변수로 주입한다.

## 어느 프로바이더로 띄울 것인가

측정된 것은 OpenAI뿐이다(`gpt-4o-mini`, `gpt-4o`). 기본값 `anthropic`으로 띄우면 **한 번도
재본 적 없는 프로바이더**가 도는 것이고, 하네스가 낸 위반율 0%는 그 서버에 대해 아무것도
말해 주지 않는다. 잰 것을 띄우거나, 띄울 것을 재고 나서 띄운다.

```bash
./gradlew eval --args="openai 5"   # 띄우려는 조합을 먼저 잰다
```

## Cloud Run

```bash
PROJECT=<프로젝트 ID>
REGION=asia-northeast3

# 1. 이미지
gcloud builds submit --tag "gcr.io/$PROJECT/hermes-agent"

# 2. 키 (한 번만)
echo -n "$OPENAI_API_KEY" | gcloud secrets create hermes-openai-key --data-file=-

# 3. 배포
gcloud run deploy hermes-agent \
  --image "gcr.io/$PROJECT/hermes-agent" \
  --region "$REGION" \
  --allow-unauthenticated \
  --min-instances 0 \
  --set-env-vars "HANJEOK_BASE_URL=<한적 주소>,HERMES_LLM_PROVIDER=openai,HERMES_LLM_MODEL=gpt-4o,HERMES_CORS_ALLOWED_ORIGINS=<Vercel 도메인>" \
  --set-secrets "OPENAI_API_KEY=hermes-openai-key:latest"
```

한적이 같은 VPC 안의 VM이면 내부 IP를 쓰고 커넥터를 붙인다. 공개 주소로 부르면
서버-서버 호출이라 **CORS 변경은 여전히 0건**이다 — 브라우저가 한적을 직접 부르지 않는다.

### 배포 확인

```bash
BASE=$(gcloud run services describe hermes-agent --region "$REGION" --format='value(status.url)')

curl -s "$BASE/actuator/health"        # UP 이어야 한다. DOWN 이면 번들이나 키다
curl -s "$BASE/agent/context" | head   # 문서 9개. 번들이 이미지에 구워졌다는 증거
curl -s "$BASE/agent/facts/<uuid>"     # 한적 연결 확인. 503이면 한적에 못 닿은 것
```

`/actuator/health`가 DOWN인 채로 트래픽을 받으면 안 된다 — 번들이 없으면 근거 없이
답하게 되고, 그게 이 프로젝트가 막으려는 일이다.

## Vercel

| 항목 | 값 |
| --- | --- |
| Root Directory | `frontend` |
| Framework | Next.js (자동 감지) |
| 환경 변수 | `NEXT_PUBLIC_AGENT_BASE_URL` = Cloud Run 주소 |

프론트에 내려가는 것은 이 주소 하나뿐이다. 한적 주소도, 어떤 키도 브라우저로 가지 않는다.

빌드는 백엔드가 떠 있지 않아도 성공한다 — 두 페이지가 요청 시점에 그려지고, 연결 거부는
예외가 아니라 "불러오지 못했습니다" 화면이 된다. 첫 빌드가 이것 때문에 죽은 적이 있어
CI가 매번 확인한다.

## 데모 코스

배포해도 **띄울 코스가 없다.** 한적에 코스 3종을 만들어 uuid를 정해야 한다.

1. 목적지가 매우혼잡이고 대안이 붙은 코스
2. 대안이 비어 있는 코스
3. `recommendedDate`가 다른 날을 가리키는 코스

셋은 서로 다른 문서를 인용하게 되므로, 인용 검증이 실제로 작동하는지가 데모에서 드러난다.
uuid가 정해지면 `frontend/src/lib/demo-courses.ts`에 넣고, 도달성을 확인한다.

```bash
HANJEOK_BASE_URL=<주소> HERMES_DEMO_COURSES="<uuid>|매우혼잡+대안" ./gradlew demoReachability
```

코스가 삭제되면 데모가 깨진다. 이건 결함이 아니라 "사실은 백엔드에서만 온다"를 지킨
대가이고, 조용히 깨지지 않도록 이 검사가 있다.
