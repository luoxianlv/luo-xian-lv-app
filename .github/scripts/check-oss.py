"""仅手动执行：比较现有入口，测试对象限定在本次 CI 前缀，不生成更新清单。"""
import hashlib
import os
import time

import oss2


ROUTES = {
    "regional": ("https://oss-cn-shanghai.aliyuncs.com", False),
    "alternate": ("https://cn-shanghai.taihangpfm.cn", False),
    "cname": ("https://oss-luoxianlv.admilk.cn", True),
}


def main():
    route = os.environ["OSS_ROUTE"]
    endpoint, cname = ROUTES[route]
    bucket = oss2.Bucket(
        oss2.Auth(os.environ["OSS_ACCESS_KEY_ID"], os.environ["OSS_ACCESS_KEY_SECRET"]),
        endpoint, os.environ["OSS_BUCKET"], is_cname=cname, connect_timeout=15,
    )
    run = os.environ["GITHUB_RUN_ID"]
    assert run.isdigit()
    key = f"luoxianlv/ci-probes/{run}/{route}.bin"
    data = os.urandom(512 * 1024)
    started = time.monotonic()
    try:
        print(f"测试 {route}：512 KiB 上传", flush=True)
        bucket.put_object(key, data)
        uploaded = time.monotonic()
        with bucket.get_object(key) as response:
            downloaded = response.read()
        ended = time.monotonic()
        assert hashlib.sha256(data).digest() == hashlib.sha256(downloaded).digest()
        line = f"{route}: 上传 {uploaded-started:.2f}s，回读 {ended-uploaded:.2f}s，SHA-256 一致"
        print(line, flush=True)
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(line + "\n")
    finally:
        # 只删除当前运行、当前入口的测试对象。
        bucket.delete_object(key)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"链路测试失败：{type(error).__name__}", flush=True)
        raise SystemExit(1)
