"""文件工具箱（convert_file 工具调用）：图片转 PDF、合并拆分 PDF、压缩图片、换图片格式。
每个函数收文件路径，返回一段 JSON：{"ok": true, "files": [...], "note": "..."} 或 {"ok": false, "error": "..."}。"""
import json
import os


def _ok(files, note=""):
    return json.dumps({"ok": True, "files": files, "note": note}, ensure_ascii=False)


def _err(msg):
    return json.dumps({"ok": False, "error": msg}, ensure_ascii=False)


def _open_image(path):
    from PIL import Image, ImageOps
    img = Image.open(path)
    # 手机拍的照片带着旋转信息，先转正
    img = ImageOps.exif_transpose(img)
    return img


def _rgb(img):
    from PIL import Image
    if img.mode in ("RGBA", "LA", "P"):
        img = img.convert("RGBA")
        bg = Image.new("RGB", img.size, (255, 255, 255))
        bg.paste(img, mask=img.split()[-1])
        return bg
    return img.convert("RGB") if img.mode != "RGB" else img


def images_to_pdf(paths_json, out):
    """几张图片按顺序拼成一个 PDF，每张一页（太大的先缩到长边 2400 像素，文件不会太大）。"""
    try:
        paths = json.loads(paths_json)
        pages = []
        for p in paths:
            img = _rgb(_open_image(p))
            img.thumbnail((2400, 2400))
            pages.append(img)
        if not pages:
            return _err("没有图片")
        pages[0].save(out, "PDF", save_all=True, append_images=pages[1:], resolution=150.0)
        return _ok([out], "%d 页" % len(pages))
    except Exception as e:
        return _err("%s: %s" % (type(e).__name__, e))


def merge_pdf(paths_json, out):
    try:
        from pypdf import PdfWriter
        w = PdfWriter()
        total = 0
        for p in json.loads(paths_json):
            w.append(p)
        total = len(w.pages)
        with open(out, "wb") as f:
            w.write(f)
        return _ok([out], "共 %d 页" % total)
    except Exception as e:
        return _err("%s: %s" % (type(e).__name__, e))


def _ranges(spec, n):
    """「1-3,5,8-」→ [[0,1,2],[4],[7..n-1]]；空的就每页一个。"""
    if not spec.strip():
        return [[i] for i in range(n)]
    out = []
    for part in spec.replace("，", ",").split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            a, b = part.split("-", 1)
            a = int(a) if a.strip() else 1
            b = int(b) if b.strip() else n
        else:
            a = b = int(part)
        a, b = max(1, a), min(n, b)
        if a <= b:
            out.append(list(range(a - 1, b)))
    return out


def split_pdf(path, spec, out_prefix):
    """按页码范围拆：每个范围存成一个文件（前缀-1.pdf、前缀-2.pdf……）。"""
    try:
        from pypdf import PdfReader, PdfWriter
        r = PdfReader(path)
        n = len(r.pages)
        groups = _ranges(spec, n)
        if not groups:
            return _err("页码范围不对（这个 PDF 共 %d 页）" % n)
        if len(groups) > 200:
            return _err("要拆成 %d 个文件，太多了，写个页码范围（比如 1-10,11-20）" % len(groups))
        files = []
        for i, g in enumerate(groups):
            w = PdfWriter()
            for k in g:
                w.add_page(r.pages[k])
            name = "%s-%s.pdf" % (out_prefix, ("%d" % (g[0] + 1)) if len(g) == 1 else "%d-%d" % (g[0] + 1, g[-1] + 1))
            with open(name, "wb") as f:
                w.write(f)
            files.append(name)
        return _ok(files, "原文件共 %d 页" % n)
    except Exception as e:
        return _err("%s: %s" % (type(e).__name__, e))


def compress_image(path, out, max_side, quality):
    """缩到长边不超过 max_side（0 = 不缩），存成 JPEG（quality 1-95）；PNG 透明图换成白底。"""
    try:
        img = _rgb(_open_image(path))
        before = os.path.getsize(path)
        if max_side and max_side > 0:
            img.thumbnail((max_side, max_side))
        img.save(out, "JPEG", quality=int(quality), optimize=True, progressive=True)
        return _ok([out], "%d×%d，%d KB → %d KB" % (img.size[0], img.size[1], before // 1024, os.path.getsize(out) // 1024))
    except Exception as e:
        return _err("%s: %s" % (type(e).__name__, e))


def convert_image(path, out, fmt):
    """换格式：jpg、png、webp、pdf、bmp、gif。"""
    try:
        fmt = fmt.lower().replace("jpg", "jpeg")
        img = _open_image(path)
        if fmt in ("jpeg", "pdf", "bmp"):
            img = _rgb(img)
        kw = {"quality": 90} if fmt in ("jpeg", "webp") else {}
        img.save(out, fmt.upper(), **kw)
        return _ok([out], "%d×%d" % img.size)
    except Exception as e:
        return _err("%s: %s" % (type(e).__name__, e))
