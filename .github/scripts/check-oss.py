"""用正式上传函数验证 16 MiB 随机文件；只操作当前 CI 的测试前缀。"""
import hashlib
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

import oss2


def test_key():
    run = os.environ["GITHUB_RUN_ID"]
    transport = os.environ["OSS_TRANSPORT"]
    if not run.isdigit() or transport not in {"cubic", "bbr"}:
        raise ValueError("测试路径参数不合法")
    return f"luoxianlv/ci-probes/{run}/{transport}-production.bin"


def create_bucket():
    return oss2.Bucket(
        oss2.Auth(os.environ["OSS_ACCESS_KEY_ID"], os.environ["OSS_ACCESS_KEY_SECRET"]),
        "https://oss-cn-shanghai.aliyuncs.com", os.environ["OSS_BUCKET"], connect_timeout=15,
    )


def measure():
    spec = importlib.util.spec_from_file_location("publish_oss", Path(__file__).with_name("publish-oss.py"))
    publish = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(publish)
    oss2.defaults.connection_pool_size = 4
    base = create_bucket()
    bucket = publish.ReportingBucket(base.auth, base.endpoint, base.bucket_name, connect_timeout=30)
    download = oss2.Bucket(base.auth, "https://oss-luoxianlv.admilk.cn", base.bucket_name,
                          is_cname=True, connect_timeout=30)
    with tempfile.TemporaryDirectory(prefix="oss-probe-") as directory:
        file = Path(directory) / "probe.apk"
        file.write_bytes(os.urandom(16 * 1024 * 1024))
        sha = hashlib.sha256(file.read_bytes()).hexdigest()
        started = time.monotonic()
        publish.upload_and_verify(bucket, download, file, test_key(), sha,
                                  oss2.ResumableStore(root=directory))
        line = f"{os.environ['OSS_TRANSPORT']}：正式上传与完整回读 16 MiB 共 {time.monotonic()-started:.2f} 秒，SHA-256 一致"
        print(line, flush=True)
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(line + "\n")


def main():
    if "--worker" in sys.argv:
        measure()
        return
    key = test_key()
    try:
        # 限制总耗时，避免 socket 空闲超时无法约束持续低速传输。
        subprocess.run([sys.executable, "-u", __file__, "--worker"], check=True, timeout=90)
    finally:
        # 工作进程已退出，才清理其精确路径；不触碰正式版本或其他运行。
        bucket = create_bucket()
        bucket.delete_object(key)
        for upload in oss2.MultipartUploadIterator(bucket, prefix=key):
            if upload.key == key:
                bucket.abort_multipart_upload(key, upload.upload_id)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"上传链路校验失败：{type(error).__name__}", flush=True)
        raise SystemExit(1)
