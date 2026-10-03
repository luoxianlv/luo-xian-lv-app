"""私有 R2 上传与完整回读；仅使用 S3 API，不生成客户端下载链接。"""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import os
import re
import threading
import time
from urllib.parse import urlsplit

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


APK_CONTENT_TYPE = "application/vnd.android.package-archive"
APK_CACHE_CONTROL = "public, max-age=2592000, immutable"
PART_SIZE = 5 * 1024 * 1024
WORKERS = 4


def create_client():
    endpoint = os.environ["R2_ENDPOINT"].strip().rstrip("/")
    parsed = urlsplit(endpoint)
    if (parsed.scheme != "https" or not parsed.hostname or
            not parsed.hostname.endswith(".r2.cloudflarestorage.com") or
            parsed.username is not None or parsed.password is not None or
            parsed.port not in (None, 443) or parsed.path or parsed.query or parsed.fragment):
        raise ValueError("R2_ENDPOINT 必须是私有 R2 S3 HTTPS 入口")
    bucket = os.environ["R2_BUCKET"].strip()
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket):
        raise ValueError("R2_BUCKET 格式无效")
    client = boto3.client(
        "s3", endpoint_url=endpoint, region_name="auto",
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        config=Config(
            signature_version="s3v4", connect_timeout=15, read_timeout=60,
            max_pool_connections=WORKERS,
            retries={"mode": "standard", "total_max_attempts": 3},
            s3={"addressing_style": "path"},
            request_checksum_calculation="when_required",
            response_checksum_validation="when_required",
        ),
    )
    return client, bucket


def existing_object(client, bucket, key, size, sha):
    try:
        result = client.head_object(Bucket=bucket, Key=key)
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") in ("404", "NoSuchKey", "NotFound"):
            return False
        raise
    if result.get("ContentLength") != size or result.get("Metadata", {}).get("sha256") != sha:
        raise ValueError("R2 已有对象的大小或摘要不符，拒绝覆盖")
    return True


def multipart_upload(client, bucket, apk, key, sha):
    size = apk.stat().st_size
    upload_id = client.create_multipart_upload(
        Bucket=bucket, Key=key, ContentType=APK_CONTENT_TYPE,
        CacheControl=APK_CACHE_CONTROL, Metadata={"sha256": sha},
    )["UploadId"]
    progress_lock = threading.Lock()
    confirmed = 0

    def upload_part(number):
        nonlocal confirmed
        started = time.monotonic()
        # 每个线程独立读取其分片，最多同时持有 WORKERS 个缓冲区。
        with apk.open("rb") as source:
            source.seek((number - 1) * PART_SIZE)
            data = source.read(PART_SIZE)
        print(f"R2 分片 {number} 开始上传（{len(data)} 字节）", flush=True)
        result = client.upload_part(
            Bucket=bucket, Key=key, UploadId=upload_id, PartNumber=number, Body=data,
        )
        with progress_lock:
            confirmed += len(data)
            print(f"R2 分片 {number} 已确认（{time.monotonic() - started:.1f} 秒）；"
                  f"汇总 {confirmed * 100 // size}%（{confirmed}/{size} 字节）", flush=True)
        return {"PartNumber": number, "ETag": result["ETag"]}

    try:
        count = (size + PART_SIZE - 1) // PART_SIZE
        with ThreadPoolExecutor(max_workers=WORKERS) as pool:
            parts = list(pool.map(upload_part, range(1, count + 1)))
        client.complete_multipart_upload(
            Bucket=bucket, Key=key, UploadId=upload_id, MultipartUpload={"Parts": parts},
        )
    except BaseException:
        # 只撤销本次 UploadId，不删除已有正式对象，也不枚举其他未完成上传。
        try:
            client.abort_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id)
            print("R2 本次未完成分片已撤销", flush=True)
        except Exception as error:
            print(f"R2 本次未完成分片撤销失败（{type(error).__name__}）", flush=True)
        raise


def upload_and_verify(client, bucket, apk, key, sha):
    size = apk.stat().st_size
    if existing_object(client, bucket, key, size, sha):
        print("R2 已有同摘要文件，跳过上传并重新回读校验", flush=True)
    else:
        multipart_upload(client, bucket, apk, key, sha)
    result = client.get_object(Bucket=bucket, Key=key)
    source = result["Body"]
    try:
        if (result.get("ContentLength") != size or
                result.get("ContentType", "").split(";", 1)[0] != APK_CONTENT_TYPE):
            raise ValueError("R2 文件大小或类型错误")
        digest = hashlib.sha256()
        received = 0
        last_percent = -1
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
            received += len(chunk)
            if received > size:
                raise ValueError("R2 文件大小超出发布记录")
            percent = received * 100 // size
            if percent // 10 > last_percent:
                last_percent = percent // 10
                print(f"R2 回读校验：{percent}%（{received}/{size} 字节）", flush=True)
        if received != size or digest.hexdigest() != sha:
            raise ValueError("R2 回读内容与正式签名包不一致")
    finally:
        source.close()
