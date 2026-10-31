#!/usr/bin/env python3
"""Extract jack-ai style templates from .pptx files.

Mirrors the logic of jack-ai's PptStyle.kt (theme scheme colors, fonts,
painted colors from slides/masters/layouts) and outputs Template entries
in the exact shape StyleTemplates.kt stores in style_templates.json:

  {"name": ..., "kind": "ppt", "settings": {}, "colors": {
     "primary", "accent", "bg", "title", "text", "soft", "card",
     "muted", "dark", "latin", "ea", "headLatin", "headEa"},
   "isDefault": false, "source": ..., "createdAt": <ms>}

Only stdlib is used. Usage:
  ppt_theme_extract.py <file.pptx ...> [-o style_templates.json]
  ppt_theme_extract.py --dir <folder-of-pptx> [-o style_templates.json]

License note: only run this on .pptx files whose license allows
attribution-free reuse (see WHITELIST.md next to this script).
"""
import argparse
import json
import re
import sys
import time
import zipfile
from collections import Counter
from pathlib import Path

OFFICE_DEFAULTS = {"4472C4", "156082", "5B9BD5", "4F81BD", "ED7D31", "E97132", "0F9ED5"}
SLIDE_W_EMU = 12192000.0
SLIDE_H_EMU = 6858000.0


def rgb(h):
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def hexcol(r, g, b):
    return "%02X%02X%02X" % (max(0, min(255, round(r))),
                             max(0, min(255, round(g))),
                             max(0, min(255, round(b))))


def lum(h):
    r, g, b = rgb(h)
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255


def saturation(h):
    r, g, b = rgb(h)
    mx, mn = max(r, g, b), min(r, g, b)
    return 0.0 if mx == 0 else (mx - mn) / mx


def hue(h):
    r, g, b = [c / 255.0 for c in rgb(h)]
    mx, mn = max(r, g, b), min(r, g, b)
    d = mx - mn
    if d == 0:
        return 0.0
    if mx == r:
        v = ((g - b) / d) % 6
    elif mx == g:
        v = (b - r) / d + 2
    else:
        v = (r - g) / d + 4
    return v * 60


def hue_gap(a, b):
    d = abs(hue(a) - hue(b)) % 360
    return min(d, 360 - d)


def mix(a, b, t):
    """Mix color a toward b by t (0..1)."""
    ra, ga, ba = rgb(a)
    rb, gb, bb = rgb(b)
    return hexcol(ra + (rb - ra) * t, ga + (gb - ga) * t, ba + (bb - ba) * t)


def darken(h, t):
    return mix(h, "000000", t)


def _block(xml, tag):
    m = re.search(r"<a:%s>(.*?)</a:%s>" % (tag, tag), xml, re.S)
    return m.group(1) if m else None


def _scheme_color(theme_xml, tag):
    block = _block(theme_xml, tag)
    if not block:
        return None
    m = re.search(r'(?:srgbClr val|lastClr)="([0-9A-Fa-f]{6})"', block)
    return m.group(1).upper() if m else None


def _font(theme_xml, kind, script):
    block = _block(theme_xml, kind)
    if not block:
        return None
    m = re.search(r'<a:%s typeface="([^"]+)"' % script, block)
    if m and m.group(1).strip() and not m.group(1).startswith("+"):
        return m.group(1)
    if script == "ea":
        m = re.search(r'<a:font script="Hans" typeface="([^"]+)"', block)
        if m:
            return m.group(1)
    return None


def _resolve_theme(names, read):
    """Find the main theme file via the rels chain.

    Microsoft catalog files number masters/themes with extra digits
    (slideMaster11 -> theme11), so slideMaster1/theme1 cannot be assumed.
    Chain: presentation rels -> first slideMaster -> its rels -> theme.
    Falls back to slideMaster1's rels, then to any theme file.
    """
    pres_rels = read("ppt/_rels/presentation.xml.rels") or ""
    masters = re.findall(r'Target="[^"]*(slideMasters/slideMaster\d+\.xml)"', pres_rels)
    if not masters:
        masters = sorted(n for n in names
                         if re.match(r"ppt/slideMasters/slideMaster\d+\.xml$", n))
        masters = [m[4:] for m in masters]
    for master in masters:
        rels = read("ppt/slideMasters/_rels/" + master.split("/")[-1] + ".rels") or ""
        m = re.search(r'Target="[^"]*(theme/theme\d+\.xml)"', rels)
        if m:
            return "ppt/" + m.group(1)
    themes = sorted(n for n in names if re.match(r"ppt/theme/theme\d+\.xml$", n))
    return themes[0] if themes else None


def extract(pptx_path):
    """Return (template_dict, summary) for one .pptx file."""
    with zipfile.ZipFile(pptx_path) as z:
        names = z.namelist()

        def read(name):
            try:
                return z.read(name).decode("utf-8", "replace")
            except KeyError:
                return None

        theme_path = _resolve_theme(names, read)
        theme_xml = read(theme_path) if theme_path else None
        if not theme_xml:
            raise ValueError("no theme found in %s" % pptx_path)

        dk1 = _scheme_color(theme_xml, "dk1") or "000000"
        lt1 = _scheme_color(theme_xml, "lt1") or "FFFFFF"
        dk2 = _scheme_color(theme_xml, "dk2") or dk1
        accents = [c for c in (_scheme_color(theme_xml, "accent%d" % i) for i in range(1, 7)) if c]

        slide_xmls = [n for n in names
                      if re.match(r"ppt/(slides/slide|slideMasters/slideMaster|slideLayouts/slideLayout)\d+\.xml$", n)]
        counts = Counter()
        bgs = Counter()
        for name in slide_xmls:
            xml = read(name) or ""
            m = re.search(r"<p:bg>.*?srgbClr val=\"([0-9A-Fa-f]{6})\".*?</p:bg>", xml, re.S)
            if m:
                c = m.group(1).upper()
                bgs[c] += 1
                counts[c] += 1.0
            for sm in re.finditer(r"<p:spPr>(.*?)</p:spPr>", xml, re.S):
                sp = sm.group(1)
                ext = re.search(r'<a:ext cx="(\d+)" cy="(\d+)"', sp)
                if not ext:
                    continue
                fill = re.search(
                    r"^(?:(?!<a:ln).)*?<a:solidFill><a:srgbClr val=\"([0-9A-Fa-f]{6})\"(/>|>(.*?)</a:srgbClr>)",
                    sp, re.S)
                if not fill:
                    continue
                alpha_m = re.search(r'<a:alpha val="(\d+)"', fill.group(3) or "")
                alpha = int(alpha_m.group(1)) if alpha_m else 100000
                if alpha < 50000:
                    continue
                area = (int(ext.group(1)) * int(ext.group(2))
                        / (SLIDE_W_EMU * SLIDE_H_EMU))
                counts[fill.group(1).upper()] += min(max(area, 0.0), 1.0)
            for tm in re.finditer(
                    r'<a:rPr[^>]*>\s*<a:solidFill><a:srgbClr val="([0-9A-Fa-f]{6})"', xml):
                counts[tm.group(1).upper()] += 0.005

        painted = [c for c, _ in counts.most_common()
                   if saturation(c) > 0.25 and 0.08 <= lum(c) <= 0.9]
        theme_colors = [c for c in accents if saturation(c) > 0.2]
        default_theme = not theme_colors or theme_colors[0] in OFFICE_DEFAULTS
        palette = list(dict.fromkeys(
            (painted + theme_colors) if (default_theme and painted) else (theme_colors + painted)))
        primary = palette[0] if palette else "1E40AF"
        accent = next((c for c in palette[1:]
                       if hue_gap(c, primary) > 25 or abs(lum(c) - lum(primary)) > 0.1),
                      mix(primary, "FFFFFF", 0.35))
        bg = bgs.most_common(1)[0][0] if bgs else lt1
        dark = lum(bg) < 0.35
        text = (lt1 if lum(lt1) > 0.6 else "E5E7EB") if dark else (dk1 if lum(dk1) < 0.4 else "333333")
        if dark:
            title = "F8FAFC"
        elif lum(dk2) < 0.35 and saturation(dk2) > 0.15:
            title = dk2
        else:
            title = darken(primary, 0.35)
        head_ea = _font(theme_xml, "majorFont", "ea")
        body_ea = _font(theme_xml, "minorFont", "ea")
        head_latin = _font(theme_xml, "majorFont", "latin")
        body_latin = _font(theme_xml, "minorFont", "latin")
        colors = {
            "primary": primary,
            "accent": accent,
            "bg": bg,
            "title": title,
            "text": text,
            "soft": mix(primary, bg, 0.8 if dark else 0.9),
            "card": mix(primary, bg, 0.88 if dark else 0.965),
            "muted": mix(text, bg, 0.45),
            "dark": dark,
            "latin": body_latin or body_ea or "微软雅黑",
            "ea": body_ea or "微软雅黑",
            "headLatin": head_latin or head_ea or body_latin or "微软雅黑",
            "headEa": head_ea or body_ea or "微软雅黑",
        }
        fonts = sorted({f for f in (head_ea or head_latin, body_ea or body_latin) if f})
        summary = ("primary #%s, accent #%s, bg #%s (%s), fonts %s"
                   % (primary, accent, bg, "dark" if dark else "light",
                      "、".join(fonts) if fonts else "微软雅黑"))
        if default_theme and painted:
            summary += " (theme is Office default, colors taken from most-painted)"
        stem = Path(pptx_path).stem
        template = {
            "name": stem,
            "kind": "ppt",
            "settings": {},
            "colors": colors,
            "isDefault": False,
            "source": Path(pptx_path).name,
            "createdAt": int(time.time() * 1000),
        }
        return template, summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="*")
    ap.add_argument("--dir")
    ap.add_argument("-o", "--output")
    args = ap.parse_args()
    paths = [Path(f) for f in args.files]
    if args.dir:
        paths += sorted(Path(args.dir).glob("*.pptx"))
    if not paths:
        ap.error("give .pptx files or --dir")
    templates = []
    for p in paths:
        try:
            t, summary = extract(str(p))
        except Exception as e:  # noqa: BLE001 - report and continue
            print("SKIP %s: %s" % (p, e), file=sys.stderr)
            continue
        templates.append(t)
        print("%s -> %s" % (p.name, summary))
    if args.output:
        Path(args.output).write_text(
            json.dumps(templates, ensure_ascii=False, indent=2), encoding="utf-8")
        print("wrote %d templates to %s" % (len(templates), args.output))


if __name__ == "__main__":
    main()
