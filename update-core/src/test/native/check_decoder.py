"""对生产 JNI 使用的同一 decoder 验证准确输出和全部截断边界。"""
import gzip
from pathlib import Path
import subprocess
import sys
import tempfile

decoder = Path(sys.argv[1]).resolve()
fixture = Path(__file__).resolve().parents[1] / "resources" / "w26"
with tempfile.TemporaryDirectory(prefix="lxupdate-native-") as temporary:
    root = Path(temporary)
    old = gzip.decompress((fixture / "old.bin.gz").read_bytes())
    new = gzip.decompress((fixture / "new.bin.gz").read_bytes())
    patch = (fixture / "update.hpatch").read_bytes()
    (root / "old").write_bytes(old)
    (root / "patch").write_bytes(patch)
    args = [str(decoder), str(root / "old"), str(root / "patch"), str(root / "output"), str(len(new))]
    subprocess.run(args, check=True)
    assert (root / "output").read_bytes() == new
    same_file = args.copy()
    same_file[3] = str(root / "old")
    assert subprocess.run(same_file, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0
    assert (root / "old").read_bytes() == old
    for length in range(len(patch)):
        (root / "patch").write_bytes(patch[:length])
        result = subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        assert result.returncode != 0, f"截断 {length} 未拒绝"
    (root / "patch").write_bytes(patch + b"trailing")
    assert subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0
    assert (root / "old").read_bytes() == old
    print(f"精确重建 {len(new)} 字节；{len(patch)} 个截断边界及尾随数据全部拒绝；基线未修改。")
