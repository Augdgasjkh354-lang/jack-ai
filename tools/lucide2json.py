"""把 Lucide 的 SVG 图标转成 PPT 能用的路径：只剩 M/L/C/Z 四种命令，坐标乘 100 取整（24×24 → 2400×2400）。"""
import json, math, re, sys, xml.etree.ElementTree as ET

def tokens(d):
    for m in re.finditer(r'[MmLlHhVvCcSsQqTtAaZz]|-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?', d):
        yield m.group()

def arc_to_beziers(x1, y1, rx, ry, phi, fa, fs, x2, y2):
    # SVG 规范 F.6.5 的中心参数化，再每段不超过 90° 拆成三次贝塞尔
    if rx == 0 or ry == 0: return [('L', x2, y2)]
    phi = math.radians(phi); c, s = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = c * dx + s * dy, -s * dx + c * dy
    rx, ry = abs(rx), abs(ry)
    lam = x1p**2 / rx**2 + y1p**2 / ry**2
    if lam > 1: rx *= math.sqrt(lam); ry *= math.sqrt(lam)
    num = rx**2 * ry**2 - rx**2 * y1p**2 - ry**2 * x1p**2
    den = rx**2 * y1p**2 + ry**2 * x1p**2
    co = math.sqrt(max(0, num / den)) if den else 0
    if fa == fs: co = -co
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx, cy = c * cxp - s * cyp + (x1 + x2) / 2, s * cxp + c * cyp + (y1 + y2) / 2
    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0: dt -= 2 * math.pi
    if fs and dt < 0: dt += 2 * math.pi
    n = max(1, math.ceil(abs(dt) / (math.pi / 2)))
    out = []; d = dt / n
    k = 4 / 3 * math.tan(d / 4)
    for i in range(n):
        a1, a2 = t1 + i * d, t1 + (i + 1) * d
        p = []
        for (ex, ey) in [(math.cos(a1) - k * math.sin(a1), math.sin(a1) + k * math.cos(a1)),
                         (math.cos(a2) + k * math.sin(a2), math.sin(a2) - k * math.cos(a2)),
                         (math.cos(a2), math.sin(a2))]:
            X, Y = rx * ex, ry * ey
            p += [c * X - s * Y + cx, s * X + c * Y + cy]
        out.append(('C', *p))
    return out

def parse_path(d):
    t = list(tokens(d)); i = 0; out = []; cur = (0, 0); start = (0, 0); cmd = None; last_c = None; last_q = None
    def num():
        nonlocal i; v = float(t[i]); i += 1; return v
    def flag():
        # 弧线的两个标志位可以紧挨着写（"01.5" 是 0、1、.5）
        nonlocal i
        tok = t[i]
        if len(tok) > 1 and tok[0] in '01':
            t[i] = tok[1:]; return int(tok[0])
        i += 1; return int(float(tok))
    while i < len(t):
        if re.match(r'[A-Za-z]', t[i]): cmd = t[i]; i += 1
        rel = cmd.islower(); C = cmd.upper(); x, y = cur
        if C == 'Z':
            out.append(('Z',)); cur = start; last_c = last_q = None; continue
        if C == 'M':
            nx, ny = num(), num()
            if rel: nx += x; ny += y
            out.append(('M', nx, ny)); cur = start = (nx, ny); cmd = 'l' if rel else 'L'; last_c = last_q = None; continue
        if C in 'LHV':
            if C == 'L': nx, ny = num(), num(); nx, ny = (nx + x, ny + y) if rel else (nx, ny)
            elif C == 'H': nx = num(); nx = nx + x if rel else nx; ny = y
            else: ny = num(); ny = ny + y if rel else ny; nx = x
            out.append(('L', nx, ny)); cur = (nx, ny); last_c = last_q = None; continue
        if C in 'CS':
            if C == 'C': x1, y1 = num(), num(); x1, y1 = (x1 + x, y1 + y) if rel else (x1, y1)
            else: x1, y1 = (2 * x - last_c[0], 2 * y - last_c[1]) if last_c else (x, y)
            x2, y2, nx, ny = num(), num(), num(), num()
            if rel: x2 += x; y2 += y; nx += x; ny += y
            out.append(('C', x1, y1, x2, y2, nx, ny)); cur = (nx, ny); last_c = (x2, y2); last_q = None; continue
        if C in 'QT':
            if C == 'Q': qx, qy = num(), num(); qx, qy = (qx + x, qy + y) if rel else (qx, qy)
            else: qx, qy = (2 * x - last_q[0], 2 * y - last_q[1]) if last_q else (x, y)
            nx, ny = num(), num()
            if rel: nx += x; ny += y
            out.append(('C', x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y), nx + 2 / 3 * (qx - nx), ny + 2 / 3 * (qy - ny), nx, ny))
            cur = (nx, ny); last_q = (qx, qy); last_c = None; continue
        if C == 'A':
            rx, ry, phi = num(), num(), num(); fa = flag(); fs = flag(); nx, ny = num(), num()
            if rel: nx += x; ny += y
            out += arc_to_beziers(x, y, rx, ry, phi, fa, fs, nx, ny); cur = (nx, ny); last_c = last_q = None; continue
        raise ValueError(cmd)
    return out

def ellipse(cx, cy, rx, ry):
    return [('M', cx + rx, cy)] + arc_to_beziers(cx + rx, cy, rx, ry, 0, 0, 1, cx - rx, cy) + arc_to_beziers(cx - rx, cy, rx, ry, 0, 0, 1, cx + rx, cy) + [('Z',)]

def rect(x, y, w, h, rx, ry):
    if rx == 0 and ry == 0:
        return [('M', x, y), ('L', x + w, y), ('L', x + w, y + h), ('L', x, y + h), ('Z',)]
    rx = rx or ry; ry = ry or rx
    d = f"M{x+rx} {y}H{x+w-rx}A{rx} {ry} 0 0 1 {x+w} {y+ry}V{y+h-ry}A{rx} {ry} 0 0 1 {x+w-rx} {y+h}H{x+rx}A{rx} {ry} 0 0 1 {x} {y+h-ry}V{y+ry}A{rx} {ry} 0 0 1 {x+rx} {y}Z"
    return parse_path(d)

def encode(ops):
    parts = []
    for o in ops:
        parts.append(o[0] + ','.join(str(round(v * 100)) for v in o[1:]))
    return ' '.join(parts)

def convert(path):
    root = ET.parse(path).getroot(); paths = []
    for e in root.iter():
        tag = e.tag.split('}')[-1]; a = e.attrib; f = lambda k, d=0: float(a.get(k, d))
        if tag == 'path': ops = parse_path(a['d'])
        elif tag == 'circle': ops = ellipse(f('cx'), f('cy'), f('r'), f('r'))
        elif tag == 'ellipse': ops = ellipse(f('cx'), f('cy'), f('rx'), f('ry'))
        elif tag == 'rect': ops = rect(f('x'), f('y'), f('width'), f('height'), f('rx'), f('ry'))
        elif tag == 'line': ops = [('M', f('x1'), f('y1')), ('L', f('x2'), f('y2'))]
        elif tag in ('polyline', 'polygon'):
            v = [float(n) for n in re.findall(r'-?\d*\.?\d+', a['points'])]
            ops = [('M', v[0], v[1])] + [('L', v[j], v[j + 1]) for j in range(2, len(v), 2)] + ([('Z',)] if tag == 'polygon' else [])
        else: continue
        paths.append(encode(ops))
    return paths

icons_dir, names_file, out = sys.argv[1:4]
names = [n.strip() for n in open(names_file, encoding='utf-8').read().split() if n.strip()]
res = {}; missing = []
for n in names:
    try: res[n] = convert(f"{icons_dir}/{n}.svg")
    except FileNotFoundError: missing.append(n)
json.dump(res, open(out, 'w', encoding='utf-8'), separators=(',', ':'))
print(len(res), 'icons; missing:', missing)
