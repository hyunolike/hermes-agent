#!/usr/bin/env python3
"""README 의 배포 구성도를 손그림(스케치) 스타일 SVG 로 그린다.

    python3 docs/images/generate_deploy.py   # docs/images/deploy.svg, deploy.en.svg

흐름도(generate_flow.py)의 원시 도형을 그대로 쓴다 — 두 그림이 같은 손글씨로
읽혀야 하고, 도형 코드가 갈라지면 한쪽만 고쳐진다.

내용의 출처는 docs/deploy.md, .github/workflows/build.yml, Dockerfile,
application.yml 이다. 이 넷이 바뀌면 이 그림도 바뀌어야 한다.
"""
import os

from generate_flow import INK, PAPER, SUB, Node, arrow, defs, rrect, sketch, text

OUT = os.path.dirname(os.path.abspath(__file__))

W, H = 1120, 1100


def zone(x, y, w, h, title):
    path = rrect(x, y, w, h, 18)
    return (f'<path d="{path}" fill="#F7F2E5" opacity="0.75"/>'
            + sketch(f'<path d="{path}" fill="none" stroke="#B9AE9B" stroke-width="2.4" '
                     f'stroke-dasharray="10 6"/>')
            + text(x + 20, y + 27, title, size=15, color="#8A7F72", anchor="start"))


def dashed(pts, label=None, label_at=None):
    """점선 화살표 — 요청 경로가 아닌 것(키 주입, 같은 코드를 쓴다는 사실)에 쓴다."""
    return arrow(pts, color="#A79B8B", w=2.2, label=label, label_at=label_at,
                 dash="8 5")


LAYOUT = {
    "browser":  Node(70, 92, 290, 74, "data"),
    "front":    Node(80, 216, 516, 88),
    "api":      Node(80, 392, 630, 100),
    "explain":  Node(80, 500, 300, 70),
    "llm":      Node(410, 500, 300, 70),
    "context":  Node(80, 610, 300, 90),
    "bundle":   Node(410, 610, 300, 90, "data"),
    "cors":     Node(80, 716, 630, 54, "note"),
    "harness":  Node(784, 216, 292, 88, "note"),
    "hanjeok":  Node(784, 398, 292, 84, "data"),
    "provider": Node(784, 500, 292, 90, "fork"),
    "secret":   Node(784, 610, 292, 90, "fail"),
    "wiki":     Node(80, 896, 226, 84, "data"),
    "drift":    Node(330, 896, 246, 84, "fork"),
    "ci":       Node(596, 896, 240, 84),
    "image":    Node(852, 896, 224, 84, "good"),
}

ZONES = [
    ("vercel", 56, 180, 564, 144),
    ("outside", 768, 180, 324, 144),
    ("cloudrun", 56, 360, 670, 432),
    ("external", 768, 360, 324, 432),
    ("cicd", 56, 846, 1036, 200),
]

KO = {
    "title": "배포 구성 — 잰 것을 그대로 띄운다",
    "zones": {
        "vercel": "Vercel",
        "outside": "배포 경로 밖",
        "cloudrun": "Cloud Run · asia-northeast3 · 최소 0 / 최대 3 · 1Gi",
        "external": "서버-서버 — 브라우저는 닿지 않는다",
        "cicd": "GitHub Actions (build.yml) — 평가는 여기서 돌지 않는다",
    },
    "browser": ["사용자 브라우저", "agent.hanjeok.com"],
    "front": ["Next.js 16 화면", "/course/[uuid] · /evidence",
              "NEXT_PUBLIC_AGENT_BASE_URL = Cloud Run 주소 하나뿐"],
    "api": ["presentation",
            "GET /agent/facts/{uuid} · /agent/context",
            "POST /agent/explain · /agent/ask · /agent/ask/stream (이어 묻기)",
            "/actuator/health — 번들 못 읽으면 DOWN, 트래픽 안 받는다"],
    "explain": ["explain", "ExplanationService"],
    "llm": ["llm", "프로바이더 어댑터"],
    "context": ["context", "BundleLoader · PromptAssembler", "CitationValidator"],
    "bundle": ["hanjeok-bundle.txt", "문서 9개 — 이미지에 구워져 있다",
               "런타임에 위키를 부르지 않는다"],
    "cors": ["CORS 는 HERMES_CORS_ALLOWED_ORIGINS 하나로 잠긴다",
             "한적 주소도 키도 브라우저로 가지 않는다"],
    "harness": ["./gradlew eval — 유료 · 비결정적", "서버를 띄우지 않고 application 층 직접",
                "운영 이미지에 harness 가 없다"],
    "hanjeok": ["한적 백엔드", "api.hanjeok.com", "요청당 호출 3회"],
    "provider": ["LLM 프로바이더", "배포된 것: openai · gpt-4o",
                 "anthropic · openrouter 로 교체"],
    "secret": ["Secret Manager", "hermes-openai-key",
               "환경 변수로만 — 이미지에 굽지 않는다"],
    "wiki": ["travel-context-wiki", "build-bundle.sh"],
    "drift": ["번들 표류 검사", "커밋된 번들과 다르면 실패"],
    "ci": ["gradlew build · pnpm test", "네트워크도 API 키도 없이 돈다"],
    "image": ["docker build → 이미지", "Artifact Registry"],
    "edges": {
        "browse": "HTTPS · 허용된 오리진만",
        "facts": "3회",
        "ask": "1회",
        "same": "같은 코드",
        "deploy": "gcloud run deploy",
    },
    "alt": ("배포 구성도: 브라우저가 Vercel 의 Next.js 화면을 열고, 화면은 Cloud Run 의 "
            "hermes-agent 서버(presentation · explain · context · llm)를 부른다. 서버는 "
            "한적 백엔드를 요청당 3회, LLM 프로바이더를 1회 부르고, 키는 Secret Manager 에서 "
            "환경 변수로 주입된다. 근거 번들은 GitHub Actions 가 위키에서 다시 만들어 표류를 "
            "검사한 뒤 이미지에 구워 배포한다. 평가 하네스는 배포 경로 밖에 있다."),
}

EN = {
    "title": "Deployment — ship what was measured",
    "zones": {
        "vercel": "Vercel",
        "outside": "outside the deployment path",
        "cloudrun": "Cloud Run · asia-northeast3 · min 0 / max 3 · 1Gi",
        "external": "server-to-server only",
        "cicd": "GitHub Actions (build.yml) — the evaluation never runs here",
    },
    "browser": ["The user's browser", "agent.hanjeok.com"],
    "front": ["Next.js 16 UI", "/course/[uuid] · /evidence",
              "NEXT_PUBLIC_AGENT_BASE_URL = the Cloud Run URL, nothing else"],
    "api": ["presentation",
            "GET /agent/facts/{uuid} · /agent/context",
            "POST /agent/explain · /agent/ask · /agent/ask/stream (follow-ups)",
            "/actuator/health — DOWN without the bundle, so no traffic"],
    "explain": ["explain", "ExplanationService"],
    "llm": ["llm", "provider adapter"],
    "context": ["context", "BundleLoader · PromptAssembler", "CitationValidator"],
    "bundle": ["hanjeok-bundle.txt", "9 documents — baked into the image",
               "the wiki is never called at runtime"],
    "cors": ["CORS is locked by HERMES_CORS_ALLOWED_ORIGINS alone",
             "neither hanjeok's address nor any key reaches the browser"],
    "harness": ["./gradlew eval — paid · non-deterministic",
                "calls the application layer, starts no server",
                "harness is absent from the production image"],
    "hanjeok": ["hanjeok backend", "api.hanjeok.com", "3 calls per request"],
    "provider": ["LLM provider", "deployed: openai · gpt-4o",
                 "swappable: anthropic · openrouter"],
    "secret": ["Secret Manager", "hermes-openai-key",
               "env vars only — never baked in"],
    "wiki": ["travel-context-wiki", "build-bundle.sh"],
    "drift": ["bundle drift check", "fails if the commit differs"],
    "ci": ["gradlew build · pnpm test", "no network, no API keys"],
    "image": ["docker build → image", "Artifact Registry"],
    "edges": {
        "browse": "HTTPS · allowed origins only",
        "facts": "3x",
        "ask": "1x",
        "same": "same code",
        "deploy": "gcloud run deploy",
    },
    "alt": ("Deployment diagram: the browser opens the Next.js UI on Vercel, which calls the "
            "hermes-agent server on Cloud Run (presentation · explain · context · llm). The "
            "server calls the hanjeok backend three times per request and the LLM provider "
            "once; keys arrive from Secret Manager as environment variables. GitHub Actions "
            "rebuilds the evidence bundle from the wiki, fails on drift, and bakes it into the "
            "image that gets deployed. The evaluation harness sits outside the deployment path."),
}


def render(words):
    n = LAYOUT
    e = words["edges"]
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" '
        f'height="{H}" role="img" aria-label="{words["alt"]}">',
        defs(),
        f'<rect width="{W}" height="{H}" rx="20" fill="{PAPER}"/>',
        sketch(f'<rect x="8" y="8" width="{W - 16}" height="{H - 16}" rx="18" fill="none" '
               f'stroke="#C9BFAE" stroke-width="2.5"/>'),
        text(W / 2, 48, words["title"], size=23),
        sketch(f'<path d="M{W / 2 - 230} 62 Q{W / 2} 72 {W / 2 + 230} 60" fill="none" '
               f'stroke="#D97757" stroke-width="3" stroke-linecap="round"/>'),
    ]
    for key, x, y, w, h in ZONES:
        parts.append(zone(x, y, w, h, words["zones"][key]))

    # 박스를 먼저 그린다 — 화살표 레이블이 박스에 가려지면 안 된다
    for key, node in n.items():
        parts.append(node.render(words[key]))

    # 브라우저 → 화면 → 서버
    parts.append(arrow([(n["browser"].cx, n["browser"].y + n["browser"].h),
                        (n["browser"].cx, n["front"].y - 6)]))
    parts.append(arrow([(560, n["front"].y + n["front"].h), (560, n["api"].y - 6)],
                       label=e["browse"], label_at=(560, 346)))

    # 서버 안쪽 — 층 사이
    parts.append(arrow([(200, n["api"].y + n["api"].h), (200, n["explain"].y - 6)]))
    parts.append(arrow([(n["explain"].x + n["explain"].w, n["explain"].cy),
                        (n["llm"].x - 6, n["llm"].cy)]))
    parts.append(arrow([(200, n["explain"].y + n["explain"].h), (200, n["context"].y - 6)]))
    parts.append(arrow([(n["bundle"].x - 6, n["bundle"].cy + 10),
                        (n["context"].x + n["context"].w + 2, n["context"].cy + 10)]))

    # 서버 → 밖으로 나가는 호출 둘. 캐시가 적중해도 한적 3회는 그대로 나간다.
    parts.append(arrow([(n["api"].x + n["api"].w, n["api"].cy),
                        (n["hanjeok"].x - 6, n["hanjeok"].cy)],
                       label=e["facts"], label_at=(747, n["api"].cy - 20)))
    parts.append(arrow([(n["llm"].x + n["llm"].w, n["llm"].cy),
                        (n["provider"].x - 6, n["provider"].cy - 10)],
                       label=e["ask"], label_at=(747, n["llm"].cy - 20)))

    # 키는 이미지가 아니라 런타임에 들어온다
    parts.append(dashed([(n["secret"].x - 6, n["secret"].cy + 14), (732, n["secret"].cy + 14)]))

    # 하네스는 같은 코드를 부르지만 이 경로 위에 없다
    parts.append(dashed([(n["harness"].x - 6, n["harness"].cy + 16), (668, n["harness"].cy + 16),
                         (668, 354)],
                        label=e["same"], label_at=(668, 320)))

    # CI — 번들을 위키에서 다시 만들고, 표류하면 실패시키고, 이미지에 굽는다
    parts.append(arrow([(n["wiki"].x + n["wiki"].w, n["wiki"].cy), (n["drift"].x - 6, n["drift"].cy)]))
    parts.append(arrow([(n["drift"].x + n["drift"].w, n["drift"].cy), (n["ci"].x - 6, n["ci"].cy)]))
    parts.append(arrow([(n["ci"].x + n["ci"].w, n["ci"].cy), (n["image"].x - 6, n["image"].cy)]))
    parts.append(arrow([(n["image"].cx, n["image"].y - 6), (n["image"].cx, 816), (400, 816),
                        (400, 798)],
                       label=e["deploy"], label_at=(560, 806)))

    parts.append("</svg>")
    return "".join(parts)


if __name__ == "__main__":
    for name, words in (("deploy.svg", KO), ("deploy.en.svg", EN)):
        with open(os.path.join(OUT, name), "w") as fp:
            fp.write(render(words))
        print("wrote", name)
