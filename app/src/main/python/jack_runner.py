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


def _forget_folder_modules(cwd):
    """解释器一直开着：文件夹里的 .py 改过之后，import 拿到的还是上次的旧版本。每次运行前把它们从缓存里清掉。"""
    root = os.path.realpath(cwd) + os.sep
    for name, mod in list(sys.modules.items()):
        f = getattr(mod, "__file__", None)
        if f and os.path.realpath(f).startswith(root):
            del sys.modules[name]


def run(code, cwd, timeout, stdin=""):
    out = io.StringIO()
    old_out, old_err, old_in, old_cwd = sys.stdout, sys.stderr, sys.stdin, os.getcwd()
    # input() 读的是事先给的几行；读完了就报 EOFError，不会卡住等人
    sys.stdin = io.StringIO(stdin or "")
    deadline = time.time() + timeout

    def tracer(frame, event, arg):
        if time.time() > deadline:
            raise TimeoutError("运行超过 %d 秒，被停止了" % timeout)
        return tracer

    sys.stdout = sys.stderr = out
    try:
        os.chdir(cwd)
        # 文件夹里的几个 .py 能互相 import，而且总是最新的
        _forget_folder_modules(cwd)
        if cwd not in sys.path:
            sys.path.insert(0, cwd)
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
    except SystemExit as e:
        # 脚本自己调 exit() / sys.exit()、unittest.main()：正常结束，只说一下退出码
        if e.code not in (None, 0):
            print("（程序退出，退出码 %s）" % e.code)
    except BaseException:
        lines = traceback.format_exc().splitlines()
        # 去掉本文件内部的调用层，只留用户代码相关的部分
        print("\n".join(l for l in lines if "jack_runner.py" not in l)[-4000:])
    finally:
        sys.stdout, sys.stderr, sys.stdin = old_out, old_err, old_in
        # 只在代码真用过 pyplot 时收尾；不然每次运行都要先加载一遍 matplotlib，白白慢几秒
        plt = sys.modules.get("matplotlib.pyplot")
        if plt is not None:
            try:
                plt.close("all")
            except Exception:
                pass
        os.chdir(old_cwd)
        if cwd in sys.path:
            sys.path.remove(cwd)
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
