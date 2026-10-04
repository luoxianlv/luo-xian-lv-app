# 增量更新实施基线

日期：2026-10-03。用户已批准完整实施；本地开发，不部署、不自动发版，不修改正式 1.1.0。

## 已确定的行为

网页与旧客户端下载完整 APK；具备能力的客户端优先差分，合成目标 APK 后正常系统安装。热更独立，只共享传输、重建和校验工具。基线使用 PackageManager 的实际宿主 APK。最近三个正式基线分别直达目标，不串联补丁；差分必须节省至少 30% 且不少于 64 KiB。失败自动整包，取消不回退，签名无效拒绝整份说明。

引擎固定 HDiffPatch v5.1.3，协议算法名 `hdiff-w26-zstd-v1`，使用 HDIFFW26 窗口格式、2 MiB 旧窗口、256 KiB 分段、Zstd 最大 8 MiB 窗口。只提供解码器，四种现有 ABI、minSdk 26、16 KiB ELF 页兼容。隔离进程只收文件描述符，不访问网络或更新信任配置。

## APK 签名说明 v1

稳定更新清单保留原有所有全包字段，新增可选 `deliveryV1`。它是 `{schema:1, manifest:<标准Base64原始UTF8 JSON>, signature:<标准Base64签名JSON>, trust:<标准Base64信任JSON>, trustSignature:<标准Base64根签名JSON>}`。

说明签名域为 `LXUPDATE-MANIFEST-V1`，使用既有 P-256/SHA-256 内容键和根授权格式，禁止与热更签名域替换。`manifest` 原始字节签名，不重序列化。结构：

```json
{
  "schema": 1,
  "type": "apk-update",
  "applicationId": "app.luoxianlv",
  "environment": "production",
  "variant": "release-universal",
  "issuedAt": "2026-10-03T00:00:00Z",
  "target": {
    "versionCode": 19,
    "versionName": "example",
    "sha256": "<64位小写摘要>",
    "size": 1,
    "certificateSha256": "<实际唯一安装证书摘要>"
  },
  "deltas": [{
    "base": {"versionCode": 18, "sha256": "<摘要>", "size": 1},
    "patch": {
      "algorithm": "hdiff-w26-zstd-v1",
      "sha256": "<摘要>",
      "size": 1,
      "object": "luoxianlv/delta/<targetSha>/<baseSha>/<patchSha>.hpatch"
    }
  }]
}
```

示例版本不决定下次发版号；目标参数从实际正式 APK 读取。当前支持单一 universal APK；系统真正的 split 安装改走整包。无 deliveryV1 时兼容原全包机制；有说明但无效时不得降级为信任其未签名字段。外层全包摘要、大小、版本必须与说明目标一致。

客户端声明 `deltaCapability=hdiff-w26-zstd-v1`。服务端只向具备能力且命中灰度的请求返回增量说明。`GET /api/update/delta/<patchSha>` 仅查已上线说明的准确对象并重定向到 EO；不得接受任意对象路径。默认关闭增量投放；现有全包和网站入口不改变。

## 共享实现与隔离

`app-host -> hot-core -> update-core`，`hot-core -> hot-contract`；business 仅消费 SDK 的 SharedUpdate 桥接，禁止打包引擎或 native 库。update-core 不依赖 hot-core，元数据认证由可信宿主适配器注入。下载、重建、文件校验和持久化各司其职；临时任务、已验证 APK 与热更对象使用独立目录/锁。宿主与旧 Application 对隔离进程跳过业务/统计初始化。

基线、补丁、完整目标依次验证 SHA/size；安装目标另查包名、版本和证书。原文件只读，输出暂存并 fsync，验证后原子提交。下载支持 Range 和持久化断点；重建中止后重建输出重新开始。APK 更新优先于热更，所有任务/回调保留生命周期租约至真正退出，播放优先。

## 实施顺序及验收

1. 固定协议/原生依赖与隔离服务，行为测试和损坏输入测试。
2. Go 工具构建/验证差分、签名说明和热更对象字节差分；发布流程只用正式原 APK，先重建精确对比后上传回读。
3. Rust 兼容入口、准确对象索引、灰度和独立限流；不触碰线上配置。
4. Android 普通更新桥接、恢复、整包回退和热更传输接入；更新 SDK 指纹生成流程。
5. 通过 A→C/B→C、未知/错误基线、空间不足、取消、断网、进程/worker退出、错误签名/证书、完整回退、原生热更健康/回滚测试。四 ABI 构建与 API 26/29/36 可用环境实测。记录真实差分体积/耗时/内存和引擎新增体积，不宣称未测结果。
