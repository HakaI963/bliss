#!/usr/bin/env python3
"""ansi_art_gen.py — dev-only generator for the Altaris launch-sticker art.

Turns an image crop into truecolor half-block ANSI text: one terminal cell per
character U+2580 ("▀") where the foreground color (SGR ``38;2;r;g;b``) is the
top pixel and the background color (SGR ``48;2;r;g;b``) is the bottom pixel.

Escape sequences are written as *literal* ``\\033[`` text (backslash, zero,
three, three) exactly like the previous 256-color stickers, because the guest
``altaris-banner-art`` renders the files with ``printf '%b'``.  Never raw ESC
bytes.

Pipeline (uses ffmpeg for decode/scale, stdlib for everything else):

    pipeline  crop + 4x supersample (lanczos) -> knock (bg -> black, optional
              patches/vignette) -> final lanczos downscale -> gen -> render
    knock     background removal by flood fill from the borders
    gen       rgb24 -> ANSI text (median-cut palette, run grouping)
    render    ANSI text -> rgb24 -> PNG preview (re-parses the text it wrote)
    stats     bytes / display width / SGR run report

Dev-only: not shipped in the app.
"""

import argparse
import os
import re
import subprocess
import sys
from collections import Counter


# ----------------------------------------------------------------------------
# small helpers


def run(cmd):
    p = subprocess.run(cmd, capture_output=True)
    if p.returncode != 0:
        sys.stderr.write(p.stderr.decode("utf-8", "replace"))
        raise SystemExit(f"ffmpeg failed: {' '.join(cmd[:8])} ...")


def read_raw(path, w, h):
    data = open(path, "rb").read()
    need = w * h * 3
    if len(data) != need:
        raise SystemExit(f"{path}: expected {need} bytes for {w}x{h}, got {len(data)}")
    return bytearray(data)


def write_raw(path, buf):
    open(path, "wb").write(bytes(buf))


def premultiply_raw(src, w, h, dst):
    """rgba raw -> rgb24 raw composited over black (drops ghost RGB under
    fully/partially transparent pixels)."""
    b = open(src, "rb").read()
    out = bytearray(w * h * 3)
    for i in range(w * h):
        a = b[4 * i + 3]
        if a == 0:
            continue
        j, k = 4 * i, 3 * i
        if a == 255:
            out[k:k + 3] = b[j:j + 3]
        else:
            out[k] = b[j] * a // 255
            out[k + 1] = b[j + 1] * a // 255
            out[k + 2] = b[j + 2] * a // 255
    write_raw(dst, out)
    print(f"premultiply: {src} -> {dst} ({w}x{h})")


def raw_to_png(raw_path, w, h, png, scale=8):
    run(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", f"{w}x{h}", "-i", raw_path,
         "-vf", f"scale={w * scale}:{h * scale}:flags=neighbor", "-frames:v", "1", png])


# ----------------------------------------------------------------------------
# knock — background removal


def knock(args):
    buf = read_raw(args.input, args.w, args.h)
    w, h = args.w, args.h
    tol = args.tol

    def px(i):
        return buf[3 * i:3 * i + 3]

    # --patch fills: replace a rectangle with the most common color of a ring
    # around it (used to hide watermarks/logos before the art pass).
    for spec in (args.patch or []):
        x, y, pw, ph = (int(v) for v in spec.split(","))
        ring = []
        for yy in range(max(0, y - 2), min(h, y + ph + 2)):
            for xx in range(max(0, x - 2), min(w, x + pw + 2)):
                if x <= xx < x + pw and y <= yy < y + ph:
                    continue
                ring.append(tuple(px(yy * w + xx)))
        if not ring:
            continue
        med = Counter(ring).most_common(1)[0][0]
        for yy in range(y, min(h, y + ph)):
            for xx in range(x, min(w, x + pw)):
                i = 3 * (yy * w + xx)
                buf[i:i + 3] = bytes(med)

    if args.skip_flood:
        print("knock: flood skipped (--skip-knock); blackout/vignette still apply")
    # flood fill from all border pixels.  Two similarity models:
    #   flood — grow while the *neighbour* stays within tol (gradient friendly,
    #           but soft anti-aliased outlines leak);
    #   key   — grow while the pixel stays within keytol of one background
    #           colour (explicit --keycolor, default: average of the border).
    n = w * h
    bg = bytearray(n)
    stack = []
    if not args.skip_flood:
        for x in range(w):
            stack.append(x)
            stack.append((h - 1) * w + x)
        for y in range(1, h - 1):
            stack.append(y * w)
            stack.append(y * w + w - 1)
        for i in stack:
            bg[i] = 1
    # --blackout fills: hard black rectangles (globe/background leftovers that
    # the colour models cannot classify, applied in supersample coordinates).
    for spec in (args.blackout or []):
        x, y, bw, bh = (int(v) for v in spec.split(","))
        for yy in range(max(0, y), min(h, y + bh)):
            for xx in range(max(0, x), min(w, x + bw)):
                i = yy * w + xx
                bg[i] = 2
    if not args.skip_flood and args.mode == "key":
        if args.keycolor:
            key = tuple(int(v) for v in args.keycolor.split(","))
        else:
            tr = tg = tb = 0
            for i in stack:
                tr += buf[3 * i]
                tg += buf[3 * i + 1]
                tb += buf[3 * i + 2]
            key = (tr // len(stack), tg // len(stack), tb // len(stack))
        ktol = args.keytol
        print(f"knock: key mode, keycolor={key}, keytol={ktol}")

    idx = 0
    while idx < len(stack):
        i = stack[idx]
        idx += 1
        c = buf[3 * i:3 * i + 3]
        x, y = i % w, i // w
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if nx < 0 or ny < 0 or nx >= w or ny >= h:
                continue
            j = ny * w + nx
            if bg[j]:
                continue
            d = buf[3 * j:3 * j + 3]
            if args.mode == "key":
                ok = (abs(d[0] - key[0]) <= ktol and abs(d[1] - key[1]) <= ktol
                      and abs(d[2] - key[2]) <= ktol)
            else:
                ok = (abs(c[0] - d[0]) <= tol and abs(c[1] - d[1]) <= tol
                      and abs(c[2] - d[2]) <= tol)
            if ok:
                bg[j] = 1
                stack.append(j)

    # soft alpha: 0 for bg, 255 for subject, 3x3 blur passes for feathering
    alpha = bytearray(0 if b else 255 for b in bg)
    for _ in range(args.feather):
        out = bytearray(len(alpha))
        for y in range(h):
            for x in range(w):
                s = cnt = 0
                for yy in (y - 1, y, y + 1):
                    if yy < 0 or yy >= h:
                        continue
                    for xx in (x - 1, x, x + 1):
                        if xx < 0 or xx >= w:
                            continue
                        s += alpha[yy * w + xx]
                        cnt += 1
                out[y * w + x] = s // cnt
        alpha = out

    # composite on black, then optional radial vignette fade
    vig = None
    if args.vignette:
        vig = [float(v) for v in args.vignette.split(",")]
    norm = 0.5 * min(w, h)
    for y in range(h):
        for x in range(w):
            i = y * w + x
            a = alpha[i]
            if a == 0:
                buf[3 * i:3 * i + 3] = b"\x00\x00\x00"
                continue
            if a != 255:
                for k in range(3):
                    buf[3 * i + k] = buf[3 * i + k] * a // 255
            if vig is not None:
                cx, cy, r0, r1 = vig
                dx = ((x + 0.5) - cx * w) / norm
                dy = ((y + 0.5) - cy * h) / norm
                d = (dx * dx + dy * dy) ** 0.5
                if d >= r1:
                    f = 0.0
                elif d <= r0:
                    f = 1.0
                else:
                    f = (r1 - d) / (r1 - r0)
                if f < 1.0:
                    for k in range(3):
                        buf[3 * i + k] = int(buf[3 * i + k] * f)
    write_raw(args.out, buf)
    print(f"knock: {args.input} -> {args.out} "
          f"(bg pixels: {sum(bg)}, mode {args.mode}, feather {args.feather}, "
          f"patch {args.patch}, vignette {args.vignette})")


# ----------------------------------------------------------------------------
# gen — rgb24 -> ANSI text


def median_cut(colors, ncolors):
    """colors: list of (count, (r,g,b)). Returns a palette list of rgb tuples."""
    boxes = [colors]
    while len(boxes) < ncolors:
        best_bi, best_range = -1, -1
        for bi, box in enumerate(boxes):
            if len(box) < 2:
                continue
            for ch in range(3):
                vals = [c[1][ch] for c in box]
                rng = max(vals) - min(vals)
                if rng > best_range:
                    best_range, best_bi = rng, bi
        if best_bi < 0:
            break
        box = boxes.pop(best_bi)
        ch = max(range(3),
                 key=lambda c: max(x[1][c] for x in box) - min(x[1][c] for x in box))
        box.sort(key=lambda c: c[1][ch])
        total = sum(c[0] for c in box)
        acc, cut = 0, len(box) - 1
        for i, c in enumerate(box):
            acc += c[0]
            if acc * 2 >= total:
                cut = i
                break
        boxes.append(box[:cut + 1])
        boxes.append(box[cut + 1:])
    palette = []
    for box in boxes:
        tw = sum(c[0] for c in box)
        if tw == 0:
            continue
        palette.append(tuple(sum(c[0] * c[1][ch] for c in box) // tw
                             for ch in range(3)))
    return palette


def gen(args):
    rows, w = args.rows, args.w
    buf = read_raw(args.input, w, rows * 2)
    gstep = max(1, args.gstep)
    black = args.black

    def snap(c):
        if max(c) <= black:
            return (0, 0, 0)
        if gstep > 1:
            c = tuple(min(255, (v // gstep) * gstep + gstep // 2) if v >= gstep else v
                      for v in c)
        return c

    colors = Counter()
    for i in range(w * rows * 2):
        colors[tuple(buf[3 * i:3 * i + 3])] += 1
    palette = [snap(c) for c in
               median_cut([(cnt, c) for c, cnt in colors.items()], args.colors)]
    palette = list(dict.fromkeys(palette))
    if (0, 0, 0) in palette:
        palette.remove((0, 0, 0))
    palette.insert(0, (0, 0, 0))

    def nearest(c):
        best, bd = palette[0], None
        for p in palette:
            d = (c[0] - p[0]) ** 2 + (c[1] - p[1]) ** 2 + (c[2] - p[2]) ** 2
            if bd is None or d < bd:
                best, bd = p, d
                if d == 0:
                    break
        return best

    mapped = [nearest(tuple(buf[3 * i:3 * i + 3])) for i in range(w * rows * 2)]

    # cell grid: (top, bottom) pairs; drop fully black rows and the black
    # columns shared by every row so the block hugs the character.
    grid = []
    for y in range(rows):
        grid.append([(mapped[(2 * y) * w + x], mapped[(2 * y + 1) * w + x])
                     for x in range(w)])
    BLACK = ((0, 0, 0), (0, 0, 0))
    while grid and all(p == BLACK for p in grid[0]):
        grid.pop(0)
    while grid and all(p == BLACK for p in grid[-1]):
        grid.pop()
    offset = min((next((i for i, p in enumerate(row) if p != BLACK), w)
                  for row in grid), default=0)
    grid = [row[offset:] for row in grid]

    lines = []
    runs = 0
    spaces = 0
    for row in grid:
        while row and row[-1] == BLACK:
            row.pop()
        out = []
        last_fg = last_bg = None
        for (top, bot) in row:
            # near-flat cell (smooth gradient): snap bottom to top and emit a
            # plain SPACE coloured via bg only — 1 byte when it repeats.
            if args.flat and max(abs(top[i] - bot[i]) for i in range(3)) <= args.flat:
                bot = top
            if top == bot:
                spaces += 1
                if bot != last_bg:
                    out.append(f"\\033[48;2;{bot[0]};{bot[1]};{bot[2]}m")
                    last_bg = bot
                    last_fg = None  # bg SGR leaves fg untouched; unknown -> force re-emit
                out.append(" ")
                continue
            seq = []
            if last_fg is None or top != last_fg:
                seq.append(f"38;2;{top[0]};{top[1]};{top[2]}")
            if last_bg is None or bot != last_bg:
                seq.append(f"48;2;{bot[0]};{bot[1]};{bot[2]}")
            if seq:
                out.append("\\033[" + ";".join(seq) + "m")
                runs += 1
            out.append("\u2580")
            last_fg, last_bg = top, bot
        out.append("\\033[0m")
        lines.append("".join(out))
    text = "\n".join(lines) + "\n"
    open(args.out, "w", encoding="utf-8").write(text)
    sizes = [len(strip_cells(l)) for l in lines]
    print(f"gen: {args.out} bytes={len(text.encode('utf-8'))} runs={runs} "
          f"spaces={spaces} rows={len(lines)} maxcols={max(sizes) if sizes else 0} "
          f"palette={len(palette)}")


def strip_cells(line):
    return re.sub(r"\\033\[[0-9;]*m", "", line)


# ----------------------------------------------------------------------------
# render — ANSI text -> rgb24 -> PNG (re-parses the text)


SGR = re.compile(r"(?:\\033|\x1b)\[([0-9;]*)m")


def render(args):
    cur_fg = (0, 0, 0)
    cur_bg = (0, 0, 0)
    rows = []
    maxw = 0
    for line in open(args.input, "r", encoding="utf-8").read().split("\n"):
        cells = []
        i = 0
        while i < len(line):
            m = SGR.match(line, i)
            if m:
                params = [int(v) for v in m.group(1).split(";") if v != ""]
                if params == [0]:
                    cur_fg = cur_bg = (0, 0, 0)
                else:
                    j = 0
                    while j < len(params) - 1:
                        if params[j] == 38 and params[j + 1] == 2:
                            cur_fg = tuple(params[j + 2:j + 5])
                            j += 5
                        elif params[j] == 48 and params[j + 1] == 2:
                            cur_bg = tuple(params[j + 2:j + 5])
                            j += 5
                        else:
                            j += 1
                i = m.end()
                continue
            ch = line[i]
            if ch == "\u2580":
                cells.append((cur_fg, cur_bg))
            elif ch == " ":
                cells.append((cur_bg, cur_bg))
            i += 1
        rows.append(cells)
        maxw = max(maxw, len(cells))
    w = maxw
    h = len(rows)
    out = bytearray(w * h * 2 * 3)
    for y, cells in enumerate(rows):
        for x, (top, bot) in enumerate(cells):
            i = 3 * ((2 * y) * w + x)
            out[i:i + 3] = bytes(top)
            i = 3 * ((2 * y + 1) * w + x)
            out[i:i + 3] = bytes(bot)
    write_raw(args.raw_out, out)
    raw_to_png(args.raw_out, w, h * 2, args.out, scale=args.scale)
    print(f"render: {args.input} -> {args.out} ({w}x{h} cells)")


# ----------------------------------------------------------------------------
# pipeline


def pipeline(args):
    work = args.work
    os.makedirs(work, exist_ok=True)
    name = args.name
    ss = args.ss
    W, R = args.w, args.rows
    raw1 = f"{work}/{name}.1.raw"
    raw2 = f"{work}/{name}.2.raw"
    raw3 = f"{work}/{name}.3.raw"
    # 1. crop + supersample (lanczos) so knock works on a small clean buffer
    filters = [f"scale={W * ss}:{R * 2 * ss}:flags=lanczos"]
    if args.crop:
        filters.insert(0, f"crop={args.crop}")
    if args.eq:
        filters.append(f"eq={args.eq}")
    pix = "rgba" if args.alpha else "rgb24"
    run(["ffmpeg", "-v", "error", "-y", "-i", args.input, "-vf", ",".join(filters),
         "-f", "rawvideo", "-pix_fmt", pix, raw1])
    # 1b. alpha sources: composite over black for real (avoids ghost RGB in
    #     fully transparent pixels) before any other processing
    if args.alpha:
        premultiply_raw(raw1, W * ss, R * 2 * ss, raw1)
    # 2. background -> black.  For alpha sources (or --skip-knock) the flood is
    #    skipped; blackout/vignette still apply.
    knock(argparse.Namespace(input=raw1, out=raw2, w=W * ss, h=R * 2 * ss,
                             tol=args.tol, mode=args.mode, keycolor=args.keycolor,
                             keytol=args.keytol, feather=args.feather,
                             patch=args.patch, blackout=args.blackout,
                             vignette=args.vignette,
                             skip_flood=args.alpha or args.skip_knock))
    # 3. final lanczos downscale to the half-block pixel grid (W x rows*2)
    run(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", f"{W * ss}x{R * 2 * ss}", "-i", raw2,
         "-vf", f"scale={W}:{R * 2}:flags=lanczos", "-f", "rawvideo",
         "-pix_fmt", "rgb24", raw3])
    # 4. ANSI text with literal \033 escapes
    gen(argparse.Namespace(input=raw3, out=args.out, w=W, rows=R,
                           colors=args.colors, gstep=args.gstep, black=args.black,
                           flat=args.flat))
    # 5. render the text back to a PNG through the same parser the QA uses
    render(argparse.Namespace(input=args.out, raw_out=f"{work}/{name}.render.raw",
                              out=args.preview, scale=args.scale))


# ----------------------------------------------------------------------------
# stats


def stats(args):
    text = open(args.input, "r", encoding="utf-8").read()
    maxw = 0
    cells = 0
    for line in text.split("\n"):
        c = len(strip_cells(line))
        maxw = max(maxw, c)
        cells += c
    print(f"{args.input}: bytes={len(text.encode('utf-8'))} "
          f"rows={len(text.splitlines())} maxcols={maxw} cells={cells} "
          f"sgr={text.count(chr(92) + '033[')}")


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    k = sub.add_parser("knock")
    k.add_argument("--in", dest="input", required=True)
    k.add_argument("--out", required=True)
    k.add_argument("--w", type=int, required=True)
    k.add_argument("--h", type=int, required=True)
    k.add_argument("--tol", type=int, default=26)
    k.add_argument("--mode", choices=["flood", "key"], default="flood")
    k.add_argument("--keycolor")
    k.add_argument("--keytol", type=int, default=30)
    k.add_argument("--feather", type=int, default=1)
    k.add_argument("--patch", action="append")
    k.add_argument("--blackout", action="append")
    k.add_argument("--skip-flood", action="store_true")
    k.add_argument("--vignette")
    k.set_defaults(func=knock)

    g = sub.add_parser("gen")
    g.add_argument("--in", dest="input", required=True)
    g.add_argument("--out", required=True)
    g.add_argument("--w", type=int, required=True)
    g.add_argument("--rows", type=int, required=True)
    g.add_argument("--colors", type=int, default=32)
    g.add_argument("--gstep", type=int, default=1)
    g.add_argument("--flat", type=int, default=6,
                   help="snap near-flat cells (max channel diff <= N) to the top colour and emit a space")
    g.add_argument("--black", type=int, default=18)
    g.set_defaults(func=gen)

    r = sub.add_parser("render")
    r.add_argument("--in", dest="input", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--raw-out", default="/tmp/opencode/anime-art/work/render.raw")
    r.add_argument("--scale", type=int, default=8)
    r.set_defaults(func=render)

    s = sub.add_parser("stats")
    s.add_argument("--in", dest="input", required=True)
    s.set_defaults(func=stats)

    p = sub.add_parser("pipeline")
    p.add_argument("--in", dest="input", required=True)
    p.add_argument("--crop", help="crop=w:h:x:y in source pixels")
    p.add_argument("--w", type=int, default=44)
    p.add_argument("--rows", type=int, default=20)
    p.add_argument("--ss", type=int, default=4)
    p.add_argument("--tol", type=int, default=26)
    p.add_argument("--mode", choices=["flood", "key"], default="flood")
    p.add_argument("--keycolor")
    p.add_argument("--keytol", type=int, default=30)
    p.add_argument("--feather", type=int, default=1)
    p.add_argument("--skip-knock", action="store_true")
    p.add_argument("--alpha", action="store_true",
                   help="source has an alpha channel: premultiply over black, skip knock")
    p.add_argument("--eq", help="ffmpeg eq= filter chain fragment (e.g. \"brightness=0.05:saturation=1.2\")")
    p.add_argument("--patch", action="append")
    p.add_argument("--blackout", action="append")
    p.add_argument("--vignette")
    p.add_argument("--colors", type=int, default=32)
    p.add_argument("--gstep", type=int, default=1)
    p.add_argument("--flat", type=int, default=6)
    p.add_argument("--black", type=int, default=18)
    p.add_argument("--name", required=True)
    p.add_argument("--work", default="/tmp/opencode/anime-art/work")
    p.add_argument("--out", required=True)
    p.add_argument("--preview", required=True)
    p.add_argument("--scale", type=int, default=8)
    p.set_defaults(func=pipeline)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
