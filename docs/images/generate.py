#!/usr/bin/env python3
"""README 에 쓰는 손그림(스케치) 스타일 기술 로고 SVG 생성기.

    python3 docs/images/generate.py     # docs/images/ 아래 SVG 를 다시 만든다

산출물은 커밋되어 있으므로 로고를 고칠 때만 돌리면 된다.

- 각 로고는 100x100 좌표계 안에 그린다.
- feTurbulence + feDisplacementMap 으로 선을 흔들어 연필 스케치처럼 보이게 한다.
- 같은 선을 살짝 어긋나게 두 번 그어 덧그린 느낌을 준다.
- 배경에 종이색 카드를 깔아 깃허브 라이트/다크 어느 쪽에서도 선이 읽히게 한다.
"""
import math
import os

OUT = os.path.dirname(os.path.abspath(__file__))
INK = "#2F2A26"

# ---------------------------------------------------------------- primitives

def sketch(markup: str, seed_a: str = "a", seed_b: str = "b", dx: float = 0.9, dy: float = -0.7) -> str:
    """같은 그림을 두 번 겹쳐 그려 덧그린 손그림 느낌을 낸다."""
    return (
        f'<g filter="url(#rough-{seed_a})">{markup}</g>'
        f'<g filter="url(#rough-{seed_b})" opacity="0.42" transform="translate({dx},{dy})">{markup}</g>'
    )


def stroke(d: str, color: str = INK, w: float = 3.2, cap: str = "round", extra: str = "") -> str:
    return (
        f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{w}" '
        f'stroke-linecap="{cap}" stroke-linejoin="round" {extra}/>'
    )


def fill(d: str, color: str, opacity: float = 0.85) -> str:
    return f'<path d="{d}" fill="{color}" opacity="{opacity}"/>'


def circle(cx, cy, r, color=INK, w=3.2, f="none"):
    return (
        f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{f}" stroke="{color}" '
        f'stroke-width="{w}"/>'
    )


# ------------------------------------------------------------------- drawings

def kotlin():
    sq = "M24 24 L76 24 L50 50 L76 76 L24 76 Z"
    return (
        fill("M24 24 L76 24 L50 50 Z", "#F58220", 0.9)
        + fill("M50 50 L76 76 L24 76 Z", "#7F52FF", 0.85)
        + fill("M24 24 L50 50 L24 76 Z", "#C711E1", 0.55)
        + sketch(stroke(sq) + stroke("M24 24 L50 50 L24 76"))
    )


def java():
    cup = "M30 46 L66 46 L62 78 Q61 82 56 82 L40 82 Q35 82 34 78 Z"
    handle = "M66 52 Q78 52 78 60 Q78 68 66 68"
    saucer = "M26 88 L74 88"
    steam1 = "M42 38 Q36 32 42 26 Q48 20 43 14"
    steam2 = "M56 38 Q50 32 56 26 Q62 20 57 14"
    return (
        fill(cup, "#ED8B00", 0.75)
        + sketch(stroke(cup) + stroke(handle) + stroke(saucer, w=3.0)
                 + stroke(steam1, color="#8A7F76", w=2.6)
                 + stroke(steam2, color="#8A7F76", w=2.6))
    )


def spring():
    leaf = "M22 78 Q24 34 74 22 Q84 62 46 76 Q32 80 22 78 Z"
    vein = "M26 76 Q48 62 70 30"
    return (
        fill(leaf, "#6DB33F", 0.8)
        + sketch(stroke(leaf) + stroke(vein, w=2.6))
        + sketch(circle(72, 30, 3.5, f="#FFFFFF", w=2.4), "c", "d")
    )


def gradle():
    a1 = "M18 68 Q34 30 74 34"
    a2 = "M22 78 Q42 44 78 48"
    a3 = "M62 26 L76 32 L70 44"
    return (
        sketch(stroke(a1, color="#02303A", w=5.0)
               + stroke(a2, color="#0B8B92", w=5.0)
               + stroke(a3, color="#02303A", w=3.4))
    )


def anthropic():
    rays = []
    for i in range(12):
        ang = -math.pi / 2 + i * (2 * math.pi / 12)
        r0 = 8
        r1 = 34 if i % 2 == 0 else 27
        x0, y0 = 50 + r0 * math.cos(ang), 50 + r0 * math.sin(ang)
        x1, y1 = 50 + r1 * math.cos(ang), 50 + r1 * math.sin(ang)
        w = 4.6 if i % 2 == 0 else 3.4
        rays.append(stroke(f"M{x0:.1f} {y0:.1f} L{x1:.1f} {y1:.1f}", color="#D97757", w=w))
    return sketch("".join(rays))


def openai():
    petals = []
    for i in range(3):
        ang = i * 60
        petals.append(
            f'<g transform="rotate({ang} 50 50)">'
            + stroke("M50 20 Q72 32 72 50 Q72 68 50 80 Q28 68 28 50 Q28 32 50 20 Z",
                     color="#0D8F72", w=3.0)
            + "</g>"
        )
    return sketch("".join(petals) + circle(50, 50, 6, color="#0D8F72", w=3.0))


def nextjs():
    n = "M38 70 L38 32 L64 70 L64 32"
    return (
        sketch(circle(50, 50, 30, w=3.4))
        + sketch(stroke(n, w=4.0), "c", "d")
    )


def react():
    orbits = "".join(
        f'<g transform="rotate({a} 50 50)">'
        f'<ellipse cx="50" cy="50" rx="34" ry="13" fill="none" stroke="#1AA2C8" stroke-width="3.0"/>'
        f"</g>"
        for a in (0, 60, 120)
    )
    return sketch(orbits + circle(50, 50, 6.5, color="#1AA2C8", w=3.2, f="#1AA2C8"))


def typescript():
    box = "M20 20 L80 20 L80 80 L20 80 Z"
    t = "M30 52 L56 52 M43 52 L43 74"
    s = "M76 54 Q60 50 61 60 Q62 68 74 68 Q80 70 76 75 Q70 78 61 74"
    return (
        fill(box, "#3178C6", 0.85)
        + sketch(stroke(box))
        + sketch(stroke(t, color="#FFFFFF", w=3.6) + stroke(s, color="#FFFFFF", w=3.6), "c", "d")
    )


def tailwind():
    w1 = "M20 48 Q30 30 42 40 Q52 48 62 40"
    w2 = "M38 68 Q48 50 60 60 Q70 68 80 60"
    return sketch(stroke(w1, color="#1C91C6", w=4.6) + stroke(w2, color="#1C91C6", w=4.6))


def docker():
    boxes = []
    for col in range(3):
        for row in range(2):
            x = 30 + col * 13
            y = 46 - row * 12
            if row == 1 and col != 1:
                continue
            boxes.append(f'<rect x="{x}" y="{y}" width="11" height="10" rx="1.5" '
                         f'fill="#2496ED" opacity="0.8" stroke="{INK}" stroke-width="2.4"/>')
    body = "M24 58 L66 58 Q78 58 84 51 Q83 72 62 74 L42 74 Q26 74 24 62 Z"
    tail = "M24 58 L14 48 Q11 58 18 63 Z"
    spout = "M72 40 Q78 34 84 38"
    return (
        fill(body, "#2496ED", 0.45)
        + sketch("".join(boxes) + stroke(body) + stroke(tail) + stroke(spout, w=2.6))
    )


def cloudrun():
    cloud = ("M30 64 Q18 64 18 54 Q18 44 30 44 Q32 30 46 30 Q60 30 62 42 "
             "Q78 42 78 54 Q78 64 66 64 Z")
    arrow = "M43 46 L59 54 L43 62 Z"
    return (
        fill(cloud, "#4285F4", 0.35)
        + sketch(stroke(cloud))
        + sketch(fill(arrow, "#4285F4", 0.9) + stroke(arrow, w=2.6), "c", "d")
        + sketch(stroke("M26 76 L74 76", w=2.6), "b", "a")
    )


def vercel():
    tri = "M50 24 L80 74 L20 74 Z"
    return fill(tri, "#1A1A1A", 0.88) + sketch(stroke(tri))


def junit():
    box = "M22 22 L78 22 L78 78 L22 78 Z"
    check = "M34 52 L46 64 L68 36"
    return (
        fill(box, "#25A162", 0.28)
        + sketch(stroke(box))
        + sketch(stroke(check, color="#1F8A54", w=5.0), "c", "d")
    )


LOGOS = [
    ("kotlin", kotlin, "Kotlin 2.2.21"),
    ("java", java, "JDK 21"),
    ("spring-boot", spring, "Spring Boot 4.1.0"),
    ("gradle", gradle, "Gradle KTS"),
    ("junit", junit, "JUnit 5"),
    ("anthropic", anthropic, "Anthropic SDK"),
    ("openai", openai, "OpenAI / Router"),
    ("nextjs", nextjs, "Next.js 16"),
    ("react", react, "React 19"),
    ("typescript", typescript, "TypeScript 5"),
    ("tailwind", tailwind, "Tailwind 4"),
    ("docker", docker, "Docker"),
    ("cloud-run", cloudrun, "Cloud Run"),
    ("vercel", vercel, "Vercel"),
]

# ------------------------------------------------------------------ templates

def defs(seeds=("a", "b", "c", "d", "t")):
    out = ["<defs>"]
    freq = {"a": "0.028 0.042", "b": "0.035 0.03", "c": "0.04 0.05", "d": "0.03 0.045",
            "t": "0.02 0.03"}
    scale = {"a": 2.6, "b": 3.2, "c": 2.2, "d": 3.0, "t": 1.5}
    for i, s in enumerate(seeds):
        out.append(
            f'<filter id="rough-{s}" x="-30%" y="-30%" width="160%" height="160%" '
            f'color-interpolation-filters="sRGB">'
            f'<feTurbulence type="fractalNoise" baseFrequency="{freq[s]}" numOctaves="2" '
            f'seed="{7 + i * 13}" result="n"/>'
            f'<feDisplacementMap in="SourceGraphic" in2="n" scale="{scale[s]}" '
            f'xChannelSelector="R" yChannelSelector="G"/>'
            f"</filter>"
        )
    out.append("</defs>")
    return "".join(out)


def paper_tile(x, y, size=100):
    """종이 카드 — 라이트/다크 테마 어디서 보든 스케치가 읽히도록 배경을 깐다."""
    pad = 3
    d = (f"M{x + pad} {y + pad + 10} Q{x + pad} {y + pad} {x + pad + 10} {y + pad} "
         f"L{x + size - pad - 10} {y + pad} Q{x + size - pad} {y + pad} {x + size - pad} {y + pad + 10} "
         f"L{x + size - pad} {y + size - pad - 10} Q{x + size - pad} {y + size - pad} {x + size - pad - 10} {y + size - pad} "
         f"L{x + pad + 10} {y + size - pad} Q{x + pad} {y + size - pad} {x + pad} {y + size - pad - 10} Z")
    return (f'<path d="{d}" fill="#FDFAF1"/>'
            + f'<g filter="url(#rough-b)"><path d="{d}" fill="none" stroke="#C9BFAE" stroke-width="2"/></g>')


def write_single(name, draw):
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100" '
        f'role="img" aria-label="{name} logo, hand-drawn">'
        + defs()
        + paper_tile(0, 0)
        + f'<g transform="translate(50 50) scale(0.86) translate(-50 -50)">{draw()}</g>'
        + "</svg>"
    )
    with open(os.path.join(OUT, "stack", f"{name}.svg"), "w") as fp:
        fp.write(svg)


GROUPS = [
    ("Backend", ["kotlin", "java", "spring-boot", "gradle", "junit"]),
    ("LLM", ["anthropic", "openai"]),
    ("Frontend", ["nextjs", "react", "typescript", "tailwind"]),
    ("Deploy", ["docker", "cloud-run", "vercel"]),
]

HAND_FONT = ("'Comic Sans MS','Segoe Print','Bradley Hand','Chalkboard SE',"
             "'Comic Neue','Trebuchet MS',sans-serif")


def write_board():
    by_name = {n: (d, label) for n, d, label in LOGOS}
    cell_w, cell_h = 132, 150
    cols = 5
    pad_x, pad_y = 34, 96
    rows = []
    y = pad_y
    for title, names in GROUPS:
        rows.append((title, names, y))
        y += cell_h + 40
    width = pad_x * 2 + cols * cell_w
    height = rows[-1][2] + 168

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" '
        f'height="{height}" role="img" aria-label="hermes-agent tech stack, hand drawn">',
        defs(),
        f'<rect x="0" y="0" width="{width}" height="{height}" rx="18" fill="#FDFAF1"/>',
        f'<g filter="url(#rough-a)"><rect x="6" y="6" width="{width - 12}" height="{height - 12}" '
        f'rx="16" fill="none" stroke="#C9BFAE" stroke-width="2.5"/></g>',
        f'<g filter="url(#rough-t)"><text x="{width / 2}" y="50" text-anchor="middle" '
        f'font-family={HAND_FONT!r} font-size="30" fill="{INK}">hermes-agent · tech stack</text></g>',
        f'<g filter="url(#rough-c)"><path d="M{width / 2 - 170} 64 Q{width / 2} 74 {width / 2 + 170} 62" '
        f'fill="none" stroke="#D97757" stroke-width="3" stroke-linecap="round"/></g>',
    ]

    for title, names, top in rows:
        parts.append(
            f'<g filter="url(#rough-t)"><text x="{pad_x + 6}" y="{top - 10}" '
            f'font-family={HAND_FONT!r} font-size="20" fill="#6B625A">{title}</text></g>'
        )
        parts.append(
            f'<g filter="url(#rough-d)"><path d="M{pad_x + 6} {top - 2} L{width - pad_x - 6} {top - 4}" '
            f'fill="none" stroke="#DCD2C0" stroke-width="2"/></g>'
        )
        start = (width - len(names) * cell_w) / 2
        for i, name in enumerate(names):
            draw, label = by_name[name]
            x = start + i * cell_w + (cell_w - 100) / 2
            parts.append(f'<g transform="translate({x} {top + 8})">')
            parts.append(paper_tile(0, 0))
            parts.append(f'<g transform="translate(50 50) scale(0.86) translate(-50 -50)">{draw()}</g>')
            parts.append("</g>")
            parts.append(
                f'<g filter="url(#rough-t)"><text x="{x + 50}" y="{top + 130}" text-anchor="middle" '
                f'font-family={HAND_FONT!r} font-size="15" fill="{INK}">{label}</text></g>'
            )

    parts.append("</svg>")
    with open(os.path.join(OUT, "tech-stack.svg"), "w") as fp:
        fp.write("".join(parts))


if __name__ == "__main__":
    os.makedirs(os.path.join(OUT, "stack"), exist_ok=True)
    for name, draw, _label in LOGOS:
        write_single(name, draw)
    write_board()
    print("wrote", len(LOGOS), "logos +", "tech-stack.svg", "to", OUT)
