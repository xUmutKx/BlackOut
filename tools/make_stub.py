"""Builds app/libs/xposed-stub.jar from tools/xposed-stub (compile-only stub of the Xposed API; the real one is provided by LSPosed at run time)."""
import glob, os, subprocess, tempfile, zipfile

here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, "xposed-stub")
out = os.path.join(here, "..", "app", "libs")
os.makedirs(out, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    files = glob.glob(os.path.join(src, "**", "*.java"), recursive=True)
    subprocess.check_call(["javac", "--release", "8", "-d", tmp] + files)
    with zipfile.ZipFile(os.path.join(out, "xposed-stub.jar"), "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, names in os.walk(tmp):
            for n in names:
                p = os.path.join(root, n)
                z.write(p, os.path.relpath(p, tmp))
print("ok")
