"""在 App 里运行 AI 写的 Python 代码：工作目录是对话文件夹，收集输出，超时就停。"""
import ast
import io
import os
import sys
import time
import traceback

_fonts_ready = False


def _setup_matplotlib():
    """让 matplotlib 不弹窗口，并且能显示中文。"""
    global _fonts_ready
    if _fonts_ready:
        return
    _fonts_ready = True
    try:
        import matplotlib
        matplotlib.use("Agg")
        from matplotlib import font_manager, rcParams
        names = []
        for path in (
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/NotoSansHans-Regular.otf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/DroidSansFallbackFull.ttf",
        ):
            if os.path.exists(path):
                try:
                    font_manager.fontManager.addfont(path)
                    names.append(font_manager.FontProperties(fname=path).get_name())
                except Exception:
                    pass
        if names:
            rcParams["font.sans-serif"] = names + list(rcParams["font.sans-serif"])
            rcParams["font.family"] = "sans-serif"
        rcParams["axes.unicode_minus"] = False
        rcParams["figure.dpi"] = 150
    except ImportError:
        pass


def run(code, cwd, timeout):
    out = io.StringIO()
    old_out, old_err, old_cwd = sys.stdout, sys.stderr, os.getcwd()
    deadline = time.time() + timeout

    def tracer(frame, event, arg):
        if time.time() > deadline:
            raise TimeoutError("运行超过 %d 秒，被停止了" % timeout)
        return tracer

    sys.stdout = sys.stderr = out
    try:
        os.chdir(cwd)
        if "matplotlib" in code or "plt." in code:
            _setup_matplotlib()
        tree = ast.parse(code, "<code>", "exec")
        last = None
        # 最后一行是表达式时，像交互环境一样把它的值打印出来
        if tree.body and isinstance(tree.body[-1], ast.Expr):
            last = ast.Expression(tree.body.pop().value)
        env = {"__name__": "__main__"}
        sys.settrace(tracer)
        try:
            exec(compile(tree, "<code>", "exec"), env)
            if last is not None:
                value = eval(compile(last, "<code>", "eval"), env)
                if value is not None:
                    print(repr(value))
        finally:
            sys.settrace(None)
    except BaseException:
        lines = traceback.format_exc().splitlines()
        # 去掉本文件内部的调用层，只留用户代码相关的部分
        print("\n".join(l for l in lines if "jack_runner.py" not in l)[-4000:])
    finally:
        sys.stdout, sys.stderr = old_out, old_err
        # 只在代码真用过 pyplot 时收尾；不然每次运行都要先加载一遍 matplotlib，白白慢几秒
        plt = sys.modules.get("matplotlib.pyplot")
        if plt is not None:
            try:
                plt.close("all")
            except Exception:
                pass
        os.chdir(old_cwd)
    text = out.getvalue()
    if len(text) > 20000:
        text = text[:20000] + "\n…（输出太长，后面省略）"
    return text


def check(code):
    """只编译不运行，返回语法错误；没错返回空字符串。"""
    try:
        compile(code, "<code>", "exec")
        return ""
    except SyntaxError as e:
        return "第 %s 行：%s" % (e.lineno, e.msg)
