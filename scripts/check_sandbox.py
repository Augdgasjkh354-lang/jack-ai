"""跑代码的安全限制（app/src/main/python/jack_runner.py）：模拟手机上的目录，确认该挡的挡住、正常用法照常。
用法：python scripts/check_sandbox.py（工作目录是仓库根目录）"""
import importlib.util
import os
import shutil
import sys
import tempfile

repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 模拟目录不能放在系统临时目录下（临时目录本来就允许写）
root = os.path.join(repo, "app", "build", "sandbox-check")
shutil.rmtree(root, ignore_errors=True)
data = os.path.join(root, "data")
files = os.path.join(data, "files")
ws = os.path.join(files, "workspaces", "c1")
other = os.path.join(files, "workspaces", "c2")
for d in (ws, other, os.path.join(data, "shared_prefs"), os.path.join(data, "cache")):
    os.makedirs(d)
tmp = os.path.join(root, "tmp")
os.makedirs(tmp)
os.environ["TMPDIR"] = tmp
tempfile.tempdir = None
os.environ["HOME"] = files
with open(os.path.join(data, "shared_prefs", "settings.xml"), "w") as f:
    f.write("<apiKey>sk-secret</apiKey>")
with open(os.path.join(other, "notes.txt"), "w") as f:
    f.write("other")
with open(os.path.join(ws, "data.csv"), "w") as f:
    f.write("a,b\n1,2\n")

spec = importlib.util.spec_from_file_location("jack_runner", os.path.join(repo, "app/src/main/python/jack_runner.py"))
jr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jr)


def run(code):
    return jr.run(code, ws, 20, "", data, os.path.join(data, "cache")).strip()


prefs = os.path.join(data, "shared_prefs", "settings.xml")
blocked = {
    "read app settings (relative)": "print(open('../../../shared_prefs/settings.xml').read())",
    "read app settings (absolute)": "print(open(%r).read())" % prefs,
    "read other conversation": "print(open('../c2/notes.txt').read())",
    "list app data": "import os; print(os.listdir('../../..'))",
    "phone storage": "print(open('/sdcard/DCIM/a.jpg', 'rb'))",
    "write outside": "open(%r, 'w')" % os.path.join(root, "evil.txt"),
    "delete other conversation": "import shutil; shutil.rmtree('../c2')",
    "write cache": "open(%r, 'w')" % os.path.join(data, "cache", "x.so"),
    "symlink": "import os; os.symlink('../../../shared_prefs/settings.xml', 'k')",
    "subprocess": "import subprocess; subprocess.run(['id'])",
    "os.system": "import os; os.system('id')",
    "connect localhost": "import socket; socket.socket().connect(('127.0.0.1', 1080))",
    "connect hostname": "import socket; socket.socket().connect(('localhost', 1080))",
    "connect LAN": "import socket; socket.socket().connect(('192.168.1.1', 80))",
    "ctypes": "import ctypes; ctypes.CDLL('libc.so.6')",
}
allowed = {
    "read own file": ("print(open('data.csv').read().split()[0])", "a,b"),
    "write own file": ("open('out.txt', 'w').write('x'); print('ok')", "ok"),
    "stdlib": ("import json, csv, datetime, collections, decimal; print('ok')", "ok"),
    "temp file": ("import tempfile; f = tempfile.NamedTemporaryFile(); f.write(b'x'); print('ok')", "ok"),
}
bad = []
for name, code in blocked.items():
    out = run(code)
    print("%-30s %s" % (name, out.splitlines()[-1][:80] if out else ""))
    if "安全限制" not in out:
        bad.append("should be blocked: " + name)
for name, (code, want) in allowed.items():
    out = run(code)
    print("%-30s %s" % (name, out.splitlines()[-1][:80] if out else ""))
    if not out.endswith(want):
        bad.append("should work: " + name)
with open(prefs) as f:
    if "sk-secret" not in f.read() or not os.path.exists(other):
        bad.append("protected files were changed")
# 跑完以后不再限制（App 自己的代码照常读写）
open(os.path.join(root, "after.txt"), "w").close()
if bad:
    print("\n".join(bad))
    sys.exit(1)
print("OK")
