"""在 App 里运行 AI 写的 Python 代码：工作目录是对话文件夹，收集输出，超时就停。"""
import ast
import io
import os
import sys
import time
import traceback

_fonts_ready = False


# ---------- 安全限制 ----------
# AI 写的代码可能是被网页、邮件里的注入内容骗出来的。跑代码时用审计钩子（Python 3.8+）挡住危险动作：
# - 读写：只能动这次对话的文件夹；App 自己的其他数据（设置里的 Key、别的对话、记忆）和手机存储都碰不到；
#   Python 自带的库、缓存、临时目录照常能读写。
# - 联网：能上公网，不能连本机和局域网（本机上跑着代理内核这些服务）。
# - 不能启动别的程序，不能用 ctypes 直接碰内存。
# 钩子装上就卸不掉，只在跑用户代码那一段生效（_sandbox 为 None 时什么都不管）。
_sandbox = None
_hook_installed = False

_WRITE_EVENTS = {"os.remove", "os.rename", "os.rmdir", "os.mkdir", "os.chmod", "os.chown", "os.truncate",
                 "os.utime", "shutil.rmtree", "shutil.move"}
# 复制：第一个参数是源（读），第二个是目标（写）
_COPY_EVENTS = {"shutil.copyfile", "shutil.copytree"}
_READ_EVENTS = {"os.listdir", "os.scandir", "glob.glob", "os.chdir"}
_BLOCKED = {"subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.spawn", "os.fork", "os.forkpty",
            "os.kill", "os.killpg", "pty.spawn", "os.symlink", "os.link", "ctypes.dlopen", "ctypes.cdata",
            "os.startfile", "sys._current_frames"}


class _Box:
    def __init__(self, cwd, data_dir, cache_dir):
        real = os.path.realpath
        self.cwd = real(cwd)
        self.data = real(data_dir) if data_dir else None
        home = os.environ.get("HOME", "")
        # 整个缓存目录不放开：手机上 Python 库解压在缓存里，能改就能往库里塞代码。只放开临时目录
        writable = [self.cwd]
        if os.environ.get("TMPDIR"):
            writable.append(real(os.environ["TMPDIR"]))
        try:
            import tempfile
            writable.append(real(tempfile.gettempdir()))
        except Exception:
            pass
        if home:
            # matplotlib、pip 这类库的配置和缓存
            writable += [real(os.path.join(home, x)) for x in (".config", ".cache", ".matplotlib")]
        self.writable = writable
        # 手机上 Python 库第一次用时才解压到这里：只许新建文件（解压），不许改、删已有的
        self.extract = _extract_root()
        readable = list(writable) + ([self.extract] if self.extract else [])
        for p in list(sys.path) + [sys.prefix, sys.exec_prefix, os.path.dirname(os.__file__)]:
            if not p:
                continue
            rp = real(p)
            # 整个 App 数据目录、files 目录本身不能算进来（那等于全放开）
            if rp in (self.data, real(home) if home else None) or rp == os.path.dirname(self.cwd):
                continue
            readable.append(rp)
        self.readable = readable

    @staticmethod
    def _under(path, roots):
        return any(path == r or path.startswith(r + os.sep) for r in roots)

    def check_path(self, path, write):
        if isinstance(path, int):
            return
        if isinstance(path, bytes):
            path = os.fsdecode(path)
        path = os.path.realpath(os.path.join(os.getcwd(), os.fspath(path)))
        if self._under(path, self.writable if write else self.readable):
            return
        if write and self.extract and self._under(path, [self.extract]) and not os.path.exists(path):
            return
        private = (self.data and self._under(path, [self.data])) or \
            any(path == r or path.startswith(r + "/") for r in ("/storage", "/sdcard", "/mnt", "/data/media"))
        if write or private:
            raise PermissionError("安全限制：代码只能读写这次对话的文件夹，碰不到「%s」" % path)

    @staticmethod
    def check_connect(address):
        import ipaddress
        import socket
        if not isinstance(address, tuple) or not address:
            raise PermissionError("安全限制：代码不能连本机的服务")
        host = address[0]
        if isinstance(host, bytes):
            host = host.decode()
        try:
            ip = ipaddress.ip_address(str(host).split("%")[0])
        except ValueError:
            ip = ipaddress.ip_address(socket.gethostbyname(str(host)))
        if getattr(ip, "ipv4_mapped", None):
            ip = ip.ipv4_mapped
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_unspecified or ip.is_reserved or ip.is_multicast \
                or ip in ipaddress.ip_network("100.64.0.0/10"):
            raise PermissionError("安全限制：代码不能连本机或局域网（%s）" % host)


def _extract_root():
    """Chaquopy 把库的原生文件解压到 .../AssetFinder/ 下面；从已加载的模块里找出这个目录。电脑上跑没有，返回 None。"""
    for m in list(sys.modules.values()):
        f = getattr(m, "__file__", None) or ""
        i = f.find(os.sep + "AssetFinder" + os.sep)
        if i >= 0:
            return os.path.realpath(f[:i + len("AssetFinder") + 1])
    return None


def _preload(tree, cwd):
    """代码里 import 的库先在限制外加载好（库本身是 App 自带的，可信）：第一次加载要解压文件，放到限制里可能被挡。
    对话文件夹里的 .py 是用户/AI 写的，不在这里加载。"""
    import importlib
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.update(a.name.split(".")[0] for a in node.names)
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            names.add(node.module.split(".")[0])
    for n in names:
        if n in sys.modules or os.path.exists(os.path.join(cwd, n + ".py")) or os.path.isdir(os.path.join(cwd, n)):
            continue
        try:
            importlib.import_module(n)
        except BaseException:
            pass


def _audit(event, args):
    box = _sandbox
    if box is None:
        return
    if event == "open":
        path, mode, flags = args
        write = (mode is not None and any(c in str(mode) for c in "wax+")) or \
            (mode is None and isinstance(flags, int) and flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND))
        box.check_path(path, bool(write))
    elif event in _WRITE_EVENTS:
        for a in args[:2]:
            if isinstance(a, (str, bytes, os.PathLike)):
                box.check_path(a, True)
    elif event in _COPY_EVENTS:
        box.check_path(args[0], False)
        box.check_path(args[1], True)
    elif event in _READ_EVENTS:
        if args and isinstance(args[0], (str, bytes, os.PathLike)):
            box.check_path(args[0], False)
    elif event in ("socket.connect", "socket.sendto"):
        box.check_connect(args[1])
    elif event in _BLOCKED or event.startswith("os.exec") or event.startswith("os.spawn"):
        raise PermissionError("安全限制：代码不能做这件事（%s）" % event)


def _install_hook():
    global _hook_installed
    if not _hook_installed:
        # 先把会用到的模块加载好：钩子生效后再头一次 import，读的也是 Python 库目录，照样能读
        import ipaddress  # noqa: F401
        import socket  # noqa: F401
        try:
            import ctypes  # noqa: F401  有的库 import 时顺带加载 ctypes，提前加载掉，生效后就不用 dlopen 了
        except Exception:
            pass
        sys.addaudithook(_audit)
        _hook_installed = True


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


def run(code, cwd, timeout, stdin="", data_dir="", cache_dir=""):
    global _sandbox
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
        _preload(tree, cwd)
        _install_hook()
        box = _Box(cwd, data_dir, cache_dir)
        sys.settrace(tracer)
        _sandbox = box
        try:
            exec(compile(tree, "<code>", "exec"), env)
            if last is not None:
                value = eval(compile(last, "<code>", "eval"), env)
                if value is not None:
                    print(repr(value))
        finally:
            _sandbox = None
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
