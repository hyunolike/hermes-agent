#!/usr/bin/env python3
"""README 의 설명 요청 흐름도를 손그림(스케치) 스타일 SVG 로 그린다.

    python3 docs/images/generate_flow.py   # docs/images/flow.svg, flow.en.svg 를 다시 만든다

로고와 같은 방식이다 — 선을 변위 필터로 흔들고, 같은 선을 두 번 그어 덧그린
느낌을 내고, 종이색 배경을 깔아 깃허브 라이트·다크 어느 쪽에서도 읽히게 한다.

노드 좌표는 아래 LAYOUT 하나에서 나온다. 한국어판과 영어판은 문구만 다르고
배치는 공유한다 — 배치가 갈라지면 한쪽을 고칠 때 다른 쪽이 조용히 어긋난다.
"""
import os

OUT = os.path.dirname(os.path.abspath(__file__))

INK = "#2F2A26"
SUB = "#6B625A"
PAPER = "#FDFAF1"
HAND_FONT = ("'Comic Sans MS','Segoe Print','Bradley Hand','Chalkboard SE',"
             "'Comic Neue','Trebuchet MS',sans-serif")

# 종류별 색 — 자료 / 처리 / 분기 / 실패 / 종점
STYLES = {
    "data": ("#F4EEE0", "#9A8F7E"),
    "step": ("#FEFCF6", "#8A8078"),
    "fork": ("#FBEEDC", "#D97757"),
    "fail": ("#F9E1D9", "#C1553C"),
    "good": ("#E6F0E4", "#3F8B58"),
    "note": ("#FFFFFF", "#C3BAAC"),
}

W, H = 1030, 1270


def defs():
    specs = {
        "line": ("0.022 0.03", 1.9, 11),
        "line2": ("0.026 0.022", 2.3, 37),
        "text": ("0.018 0.024", 1.2, 61),
    }
    out = ["<defs>"]
    for name, (freq, scale, seed) in specs.items():
        out.append(
            f'<filter id="w-{name}" x="-25%" y="-25%" width="150%" height="150%" '
            f'color-interpolation-filters="sRGB">'
            f'<feTurbulence type="fractalNoise" baseFrequency="{freq}" numOctaves="2" '
            f'seed="{seed}" result="n"/>'
            f'<feDisplacementMap in="SourceGraphic" in2="n" scale="{scale}" '
            f'xChannelSelector="R" yChannelSelector="G"/>'
            f"</filter>"
        )
    out.append("</defs>")
    return "".join(out)


def sketch(markup):
    """같은 선을 두 번 — 두 번째는 옅게 살짝 어긋나게."""
    return (f'<g filter="url(#w-line)">{markup}</g>'
            f'<g filter="url(#w-line2)" opacity="0.38" transform="translate(0.8,-0.6)">{markup}</g>')


def text(x, y, s, size=17, color=INK, anchor="middle", weight="normal"):
    return (f'<g filter="url(#w-text)"><text x="{x}" y="{y}" text-anchor="{anchor}" '
            f'font-family={HAND_FONT!r} font-size="{size}" font-weight="{weight}" '
            f'fill="{color}">{s}</text></g>')


def rrect(x, y, w, h, r=14):
    return (f"M{x + r} {y} L{x + w - r} {y} Q{x + w} {y} {x + w} {y + r} "
            f"L{x + w} {y + h - r} Q{x + w} {y + h} {x + w - r} {y + h} "
            f"L{x + r} {y + h} Q{x} {y + h} {x} {y + h - r} "
            f"L{x} {y + r} Q{x} {y} {x + r} {y} Z")


def hexa(x, y, w, h, c=26):
    return (f"M{x + c} {y} L{x + w - c} {y} L{x + w} {y + h / 2} "
            f"L{x + w - c} {y + h} L{x + c} {y + h} L{x} {y + h / 2} Z")


class Node:
    def __init__(self, x, y, w, h, kind="step", shape="rect"):
        self.x, self.y, self.w, self.h = x, y, w, h
        self.kind, self.shape = kind, shape

    @property
    def cx(self):
        return self.x + self.w / 2

    @property
    def cy(self):
        return self.y + self.h / 2

    def render(self, lines):
        bg, border = STYLES[self.kind]
        path = hexa(self.x, self.y, self.w, self.h) if self.shape == "hex" \
            else rrect(self.x, self.y, self.w, self.h)
        dashed = ' stroke-dasharray="7 5"' if self.kind == "note" else ""
        out = [f'<path d="{path}" fill="{bg}"/>',
               sketch(f'<path d="{path}" fill="none" stroke="{border}" '
                      f'stroke-width="2.6" stroke-linejoin="round"{dashed}/>')]
        sizes = [17] + [13.5] * (len(lines) - 1)
        if self.kind == "note":
            sizes = [13.5] * len(lines)
        gaps = [0] + [19] * (len(lines) - 1)
        total = sum(sizes[0:1]) + sum(gaps)
        y = self.cy - total / 2 + sizes[0] * 0.72
        for i, line in enumerate(lines):
            y += gaps[i]
            out.append(text(self.cx, y, line, size=sizes[i],
                            color=INK if i == 0 and self.kind != "note" else SUB))
        return "".join(out)


def arrow(pts, color="#6F665E", w=2.6, head=12, label=None, label_at=None, label_dx=0,
          dash=None):
    """꺾인 화살표. pts 는 [(x, y), ...] — 마지막 두 점이 화살촉 방향을 정한다."""
    d = f"M{pts[0][0]} {pts[0][1]}" + "".join(f" L{x} {y}" for x, y in pts[1:])
    (x0, y0), (x1, y1) = pts[-2], pts[-1]
    import math
    ang = math.atan2(y1 - y0, x1 - x0)
    wings = "".join(
        f"M{x1} {y1} L{x1 - head * math.cos(ang - s):.1f} {y1 - head * math.sin(ang - s):.1f}"
        for s in (0.42, -0.42)
    )
    dashes = f' stroke-dasharray="{dash}"' if dash else ""
    out = sketch(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{w}" '
                 f'stroke-linecap="round" stroke-linejoin="round"{dashes}/>'
                 f'<path d="{wings}" fill="none" stroke="{color}" stroke-width="{w}" '
                 f'stroke-linecap="round"/>')
    if label:
        lx, ly = label_at if label_at else ((pts[0][0] + pts[-1][0]) / 2 + label_dx,
                                           (pts[0][1] + pts[-1][1]) / 2)
        half = 4.6 * len(label) + 8
        out += (f'<rect x="{lx - half}" y="{ly - 14}" width="{half * 2}" height="20" rx="6" '
                f'fill="{PAPER}" opacity="0.92"/>'
                + text(lx, ly, label, size=13, color="#8A5A3C"))
    return out


# ── 배치 ───────────────────────────────────────────────────────────────────
LAYOUT = {
    "backend":  Node(52, 74, 372, 76, "data"),
    "bundle":   Node(596, 74, 372, 76, "data"),
    "norm":     Node(52, 196, 372, 76),
    "loader":   Node(596, 196, 372, 76),
    "facts":    Node(52, 318, 372, 62, "data"),
    "assembler": Node(596, 318, 372, 76),
    "service":  Node(300, 434, 420, 62),
    "provider": Node(286, 542, 448, 84, "fork", "hex"),
    "anthropic": Node(28, 548, 240, 72, "note"),
    "openrouter": Node(752, 548, 250, 72, "note"),
    "result":   Node(346, 672, 336, 62),
    "validator": Node(286, 786, 448, 84, "fork", "hex"),
    "unavail":  Node(756, 912, 250, 84, "fail"),
    "explained": Node(326, 920, 378, 62, "good"),
    "forbidden": Node(300, 1026, 430, 76),
    "tally":    Node(300, 1148, 430, 76, "good"),
}

KO = {
    "backend": ["백엔드 응답 4종 중 3종", "course · congestion · alternatives"],
    "bundle": ["hanjeok-bundle.txt", "문서 9개"],
    "norm": ["FactsNormalizer", "평평한 facts 객체 하나로 정규화"],
    "loader": ["BundleLoader", "FILE 마커 파싱 · 마커 위조 검사"],
    "facts": ["BackendFacts(courseUuid, json)"],
    "assembler": ["PromptAssembler", "systemText = 번들 원문 그대로"],
    "service": ["ExplanationService.explain()"],
    "provider": ["ExplanationProvider", "어느 프로바이더로 물을 것인가"],
    "anthropic": ["Anthropic", "1h 캐시 + 구조화 출력"],
    "openrouter": ["OpenRouter", "tool_choice 로 스키마 강제"],
    "result": ["ProviderResult"],
    "validator": ["CitationValidator", "번들에 실재하는 경로인가?"],
    "unavail": ["Unavailable(거절 사유)", "설명 없음 = 안전한 실패"],
    "explained": ["Explained(설명 + 인용)"],
    "forbidden": ["ForbiddenBehaviours.check()", "금지 행동 8종 판정"],
    "tally": ["ViolationTally", "실행당 위반 / 원시 발생 횟수 집계"],
    "title": "설명 요청 흐름 — 순위는 백엔드, 설명은 LLM",
    "edges": {"answered": "Answered", "refused": "Refused · Failed",
              "valid": "Valid", "invalid": "Invalid"},
    "alt": ("설명 요청 흐름도: 백엔드 응답 3종이 FactsNormalizer 를 지나 BackendFacts 로, "
            "hanjeok-bundle.txt 는 BundleLoader 와 PromptAssembler 를 지나 systemText 로 "
            "들어가 ExplanationService.explain() 에서 만난다. ExplanationProvider"
            "(Anthropic 또는 OpenRouter)가 낸 ProviderResult 는 Refused·Failed 면 "
            "Unavailable, Answered 면 CitationValidator 로 간다. 인용이 유효하면 "
            "Explained 가 되어 ForbiddenBehaviours.check() 를 거치고, 두 갈래 모두 "
            "ViolationTally 로 모인다."),
}

EN = {
    "backend": ["3 of 4 backend responses", "course · congestion · alternatives"],
    "bundle": ["hanjeok-bundle.txt", "9 documents"],
    "norm": ["FactsNormalizer", "flattened into one facts object"],
    "loader": ["BundleLoader", "parse FILE markers · reject forged ones"],
    "facts": ["BackendFacts(courseUuid, json)"],
    "assembler": ["PromptAssembler", "systemText = the bundle, verbatim"],
    "service": ["ExplanationService.explain()"],
    "provider": ["ExplanationProvider", "which provider gets asked"],
    "anthropic": ["Anthropic", "1h cache + structured output"],
    "openrouter": ["OpenRouter", "schema forced via tool_choice"],
    "result": ["ProviderResult"],
    "validator": ["CitationValidator", "does the path exist in the bundle?"],
    "unavail": ["Unavailable(reason)", "no explanation = the safe failure"],
    "explained": ["Explained(text + citations)"],
    "forbidden": ["ForbiddenBehaviours.check()", "judge the eight behaviours"],
    "tally": ["ViolationTally", "runs-with-violation / raw occurrences"],
    "title": "Explanation request flow — backend ranks, the LLM explains",
    "edges": {"answered": "Answered", "refused": "Refused · Failed",
              "valid": "Valid", "invalid": "Invalid"},
    "alt": ("Explanation request flow: three backend responses pass through "
            "FactsNormalizer into BackendFacts, while hanjeok-bundle.txt passes through "
            "BundleLoader and PromptAssembler into systemText; both meet in "
            "ExplanationService.explain(). The ProviderResult from ExplanationProvider "
            "(Anthropic or OpenRouter) becomes Unavailable when Refused or Failed, and "
            "goes to CitationValidator when Answered. Valid citations make it Explained, "
            "which ForbiddenBehaviours.check() judges; both branches end in ViolationTally."),
}


def render(words):
    n = LAYOUT
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" '
        f'height="{H}" role="img" aria-label="{words["alt"]}">',
        defs(),
        f'<rect width="{W}" height="{H}" rx="20" fill="{PAPER}"/>',
        sketch(f'<rect x="8" y="8" width="{W - 16}" height="{H - 16}" rx="18" fill="none" '
               f'stroke="#C9BFAE" stroke-width="2.5"/>'),
        text(W / 2, 46, words["title"], size=23),
        sketch(f'<path d="M{W / 2 - 250} 60 Q{W / 2} 70 {W / 2 + 250} 58" fill="none" '
               f'stroke="#D97757" stroke-width="3" stroke-linecap="round"/>'),
    ]

    # 두 갈래 입력 — 사실(facts)과 근거(bundle)
    for a, b in (("backend", "norm"), ("norm", "facts"), ("bundle", "loader"),
                 ("loader", "assembler")):
        parts.append(arrow([(n[a].cx, n[a].y + n[a].h), (n[b].cx, n[b].y - 6)]))

    # 둘이 만나는 곳
    parts.append(arrow([(n["facts"].cx, n["facts"].y + n["facts"].h),
                        (n["facts"].cx, 412), (n["service"].x + 90, 412),
                        (n["service"].x + 90, n["service"].y - 6)]))
    parts.append(arrow([(n["assembler"].cx, n["assembler"].y + n["assembler"].h),
                        (n["assembler"].cx, 412), (n["service"].x + n["service"].w - 90, 412),
                        (n["service"].x + n["service"].w - 90, n["service"].y - 6)]))

    parts.append(arrow([(n["service"].cx, n["service"].y + n["service"].h),
                        (n["provider"].cx, n["provider"].y - 6)]))

    # 프로바이더 두 갈래는 어댑터 안의 차이일 뿐이라 점선 메모로 붙인다
    for side in ("anthropic", "openrouter"):
        note = n[side]
        near = (note.x + note.w + 4, note.cy) if side == "anthropic" else (note.x - 4, note.cy)
        far = (n["provider"].x - 2, note.cy) if side == "anthropic" \
            else (n["provider"].x + n["provider"].w + 2, note.cy)
        parts.append(sketch(f'<path d="M{near[0]} {near[1]} L{far[0]} {far[1]}" fill="none" '
                            f'stroke="#C3BAAC" stroke-width="2.2" stroke-dasharray="6 5"/>'))

    parts.append(arrow([(n["provider"].cx, n["provider"].y + n["provider"].h),
                        (n["result"].cx, n["result"].y - 6)]))

    # 실패 갈래 — 거절과 통신 실패는 같은 곳으로 간다
    parts.append(arrow(
        [(n["result"].x + n["result"].w, n["result"].cy), (970, n["result"].cy),
         (970, n["unavail"].y - 6)],
        label=words["edges"]["refused"], label_at=(806, n["result"].cy - 10)))

    parts.append(arrow(
        [(n["result"].cx, n["result"].y + n["result"].h),
         (n["validator"].cx, n["validator"].y - 6)],
        label=words["edges"]["answered"], label_at=(n["result"].cx, 762)))

    # 인용이 하나라도 어긋나면 설명 전체가 되돌려진다
    parts.append(arrow(
        [(n["validator"].x + n["validator"].w, n["validator"].cy),
         (858, n["validator"].cy), (858, n["unavail"].y - 6)],
        label=words["edges"]["invalid"], label_at=(788, n["validator"].cy - 10)))

    parts.append(arrow(
        [(n["validator"].cx, n["validator"].y + n["validator"].h),
         (n["explained"].cx, n["explained"].y - 6)],
        label=words["edges"]["valid"], label_at=(n["validator"].cx, 899)))

    parts.append(arrow([(n["explained"].cx, n["explained"].y + n["explained"].h),
                        (n["forbidden"].cx, n["forbidden"].y - 6)]))
    parts.append(arrow([(n["forbidden"].cx - 60, n["forbidden"].y + n["forbidden"].h),
                        (n["forbidden"].cx - 60, n["tally"].y - 6)]))

    # 설명이 안 나간 실행도 센다 — 세지 않으면 전멸한 실행이 무결점으로 읽힌다
    parts.append(arrow([(n["unavail"].cx, n["unavail"].y + n["unavail"].h),
                        (n["unavail"].cx, 1122), (n["tally"].cx + 125, 1122),
                        (n["tally"].cx + 125, n["tally"].y - 6)]))

    for key, node in n.items():
        parts.append(node.render(words[key]))

    parts.append("</svg>")
    return "".join(parts)


if __name__ == "__main__":
    for name, words in (("flow.svg", KO), ("flow.en.svg", EN)):
        with open(os.path.join(OUT, name), "w") as fp:
            fp.write(render(words))
        print("wrote", name)
