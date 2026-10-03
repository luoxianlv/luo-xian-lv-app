"""私有 R2 发布边界：校验、分片撤销与凭据/下载入口隔离。"""

from contextlib import redirect_stdout
import hashlib
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import publish_r2 as r2
from botocore.exceptions import ClientError
from botocore.response import StreamingBody
from botocore.stub import ANY, Stubber


class R2Tests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.apk = Path(temporary.name) / "signed.apk"
        self.data = b"same signed APK fixture"
        self.apk.write_bytes(self.data)
        self.sha = hashlib.sha256(self.data).hexdigest()
        self.key = f"luoxianlv/release/1.0.9/{self.sha}/app-release.apk"
        self.client = Mock()
        self.client.head_object.side_effect = ClientError({"Error": {"Code": "404"}}, "HeadObject")
        self.client.create_multipart_upload.return_value = {"UploadId": "owned-upload"}
        self.client.upload_part.return_value = {"ETag": "fixture-part"}
        self.body = io.BytesIO(self.data)
        self.client.get_object.return_value = {
            "ContentType": r2.APK_CONTENT_TYPE, "ContentLength": len(self.data), "Body": self.body,
        }

    def transfer(self):
        with redirect_stdout(io.StringIO()):
            r2.upload_and_verify(self.client, "fixture", self.apk, self.key, self.sha)

    def test_private_upload_and_complete_readback(self):
        self.transfer()
        self.client.create_multipart_upload.assert_called_once_with(
            Bucket="fixture", Key=self.key, ContentType=r2.APK_CONTENT_TYPE,
            CacheControl="public, max-age=2592000, immutable", Metadata={"sha256": self.sha},
        )
        self.client.upload_part.assert_called_once_with(
            Bucket="fixture", Key=self.key, UploadId="owned-upload", PartNumber=1, Body=self.data,
        )
        self.client.complete_multipart_upload.assert_called_once_with(
            Bucket="fixture", Key=self.key, UploadId="owned-upload",
            MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": "fixture-part"}]},
        )
        self.client.get_object.assert_called_once_with(Bucket="fixture", Key=self.key)
        self.client.generate_presigned_url.assert_not_called()
        self.client.abort_multipart_upload.assert_not_called()
        self.client.delete_object.assert_not_called()
        self.assertTrue(self.body.closed)

    def test_existing_object_still_verified_without_overwrite(self):
        self.client.head_object.side_effect = None
        self.client.head_object.return_value = {"ContentLength": len(self.data), "Metadata": {"sha256": self.sha}}
        self.transfer()
        self.client.create_multipart_upload.assert_not_called()
        self.client.get_object.assert_called_once()
        self.client.delete_object.assert_not_called()

    def test_existing_mismatch_is_never_overwritten(self):
        self.client.head_object.side_effect = None
        for metadata in ({}, {"ContentLength": len(self.data), "Metadata": {"sha256": "bad"}},
                         {"ContentLength": len(self.data) + 1, "Metadata": {"sha256": self.sha}}):
            with self.subTest(metadata=metadata):
                self.client.head_object.return_value = metadata
                with self.assertRaises(ValueError):
                    self.transfer()
        self.client.create_multipart_upload.assert_not_called()
        self.client.delete_object.assert_not_called()

    def test_head_permission_error_is_not_missing_object(self):
        self.client.head_object.side_effect = ClientError({"Error": {"Code": "AccessDenied"}}, "HeadObject")
        with self.assertRaises(ClientError):
            self.transfer()
        self.client.create_multipart_upload.assert_not_called()

    def test_upload_failure_aborts_only_owned_multipart(self):
        self.client.upload_part.side_effect = OSError("fixture failure")
        with self.assertRaises(OSError):
            self.transfer()
        self.client.abort_multipart_upload.assert_called_once_with(
            Bucket="fixture", Key=self.key, UploadId="owned-upload",
        )
        self.client.complete_multipart_upload.assert_not_called()
        self.client.delete_object.assert_not_called()
        self.client.list_multipart_uploads.assert_not_called()

    def test_complete_failure_and_cleanup_failure_preserve_original_error(self):
        self.client.complete_multipart_upload.side_effect = ValueError("original")
        self.client.abort_multipart_upload.side_effect = OSError("cleanup")
        with self.assertRaisesRegex(ValueError, "original"):
            self.transfer()
        self.client.abort_multipart_upload.assert_called_once()

    def test_corrupt_type_size_and_bytes_never_pass(self):
        for data, kind, size in ((b"x" * len(self.data), r2.APK_CONTENT_TYPE, len(self.data)),
                                 (self.data[:-1], r2.APK_CONTENT_TYPE, len(self.data)),
                                 (self.data + b"extra", r2.APK_CONTENT_TYPE, len(self.data)),
                                 (self.data, "text/html", len(self.data)),
                                 (self.data, r2.APK_CONTENT_TYPE, len(self.data) + 1)):
            with self.subTest(kind=kind, size=size):
                body = io.BytesIO(data)
                self.client.get_object.return_value = {"ContentType": kind, "ContentLength": size, "Body": body}
                with self.assertRaises(ValueError):
                    self.transfer()
                self.assertTrue(body.closed)

    def test_bounded_parts_preserve_order_and_complete_data(self):
        data = b"a" * r2.PART_SIZE + b"final part"
        self.apk.write_bytes(data)
        self.sha = hashlib.sha256(data).hexdigest()
        self.client.get_object.return_value = {
            "ContentType": r2.APK_CONTENT_TYPE, "ContentLength": len(data), "Body": io.BytesIO(data),
        }
        self.client.upload_part.side_effect = lambda **request: {"ETag": f"part-{request['PartNumber']}"}
        self.transfer()
        parts = {call.kwargs["PartNumber"]: call.kwargs["Body"] for call in self.client.upload_part.call_args_list}
        self.assertEqual(parts[1] + parts[2], data)
        self.assertEqual(len(parts[1]), 5 * 1024 * 1024)
        self.assertEqual(self.client.complete_multipart_upload.call_args.kwargs["MultipartUpload"]["Parts"],
                         [{"PartNumber": 1, "ETag": "part-1"}, {"PartNumber": 2, "ETag": "part-2"}])

    def test_client_uses_private_sigv4_auto_with_bounded_timeout(self):
        environment = {"R2_ENDPOINT": "https://fixture.r2.cloudflarestorage.com",
                       "R2_BUCKET": "fixture", "R2_ACCESS_KEY_ID": "fixture-id",
                       "R2_SECRET_ACCESS_KEY": "fixture-secret"}
        with patch.dict(os.environ, environment), patch.object(r2.boto3, "client") as create:
            client, bucket = r2.create_client()
        self.assertIs(client, create.return_value)
        self.assertEqual(bucket, "fixture")
        self.assertEqual(create.call_args.kwargs["region_name"], "auto")
        config = create.call_args.kwargs["config"]
        self.assertEqual(config.signature_version, "s3v4")
        self.assertEqual(config.s3["addressing_style"], "path")
        self.assertEqual(config.connect_timeout, 15)
        self.assertEqual(config.read_timeout, 60)
        self.assertEqual(config.max_pool_connections, 4)

    def test_real_sdk_request_contract_without_network(self):
        environment = {"R2_ENDPOINT": "https://fixture.r2.cloudflarestorage.com",
                       "R2_BUCKET": "fixture", "R2_ACCESS_KEY_ID": "fixture-id",
                       "R2_SECRET_ACCESS_KEY": "fixture-secret"}
        with patch.dict(os.environ, environment):
            client, bucket = r2.create_client()
        self.addCleanup(client.close)
        source = StreamingBody(io.BytesIO(self.data), len(self.data))
        with Stubber(client) as stub:
            request = {"Bucket": bucket, "Key": self.key}
            stub.add_client_error("head_object", service_error_code="NoSuchKey", http_status_code=404,
                                  expected_params=request)
            stub.add_response("create_multipart_upload", {"UploadId": "owned-upload"},
                              dict(request, ContentType=r2.APK_CONTENT_TYPE, CacheControl="public, max-age=2592000, immutable",
                                   Metadata={"sha256": self.sha}))
            stub.add_response("upload_part", {"ETag": "fixture-part"},
                              dict(request, UploadId="owned-upload", PartNumber=1, Body=ANY))
            stub.add_response("complete_multipart_upload", {},
                              dict(request, UploadId="owned-upload", MultipartUpload={
                                  "Parts": [{"PartNumber": 1, "ETag": "fixture-part"}]}))
            stub.add_response("get_object", {"ContentType": r2.APK_CONTENT_TYPE,
                                             "ContentLength": len(self.data), "Body": source}, request)
            with redirect_stdout(io.StringIO()):
                r2.upload_and_verify(client, bucket, self.apk, self.key, self.sha)
            stub.assert_no_pending_responses()

    def test_rejects_public_or_injected_endpoint(self):
        for endpoint in ("http://fixture.r2.cloudflarestorage.com", "https://oss-cf.luoxianlv.cn",
                         "https://user:pass@fixture.r2.cloudflarestorage.com",
                         "https://fixture.r2.cloudflarestorage.com?token=secret",
                         "https://fixture.r2.cloudflarestorage.com/bucket"):
            with self.subTest(endpoint=endpoint), patch.dict(os.environ, {"R2_ENDPOINT": endpoint}), \
                    patch.object(r2.boto3, "client") as create:
                with self.assertRaises(ValueError):
                    r2.create_client()
                create.assert_not_called()


if __name__ == "__main__":
    unittest.main()
