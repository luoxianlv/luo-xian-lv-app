# 下载字节/取消外部driver：本地交付

`tools/test-native-download.ps1`只允许独立emulator-5554、API127.0.0.1:18472及CLI/.local内明确Fixture/AuthDirectory。开发阶段没有实际运行driver、CLI、ADB、API或代理；只完成PowerShell解析和33项真实纯policy/mock检查。设备成功仍由根任务运行后补充。

## 调用和资料

```powershell
pwsh -NoProfile -File tools/test-native-download.ps1 `
  -Fixture '<CLI/.local内本次cancellation或delta目录>' `
  -Candidate candidate.lxhp -Mode cancellation `
  -AuthDirectory '<CLI/.local内测试授权目录>' `
  -Lxhot '<明确lxhot.exe路径>' -Gateway '<明确download-gateway.exe路径>'
```

Fixture提供root.public.json、已签candidate.lxhp及plan-template.json。模板字段为targetSnapshotId、sourceIdentity（可空）、expectedObjects、expectedMissingBytes、slowObjectSha、prefixBytes，可含schema/runId/mode；driver创建新runId和固定设备计划，不复用旧握手。AuthDirectory只检查路径/目录元数据，admin-token仅以文件路径传CLI，不读取或输出内容。

辅助入口参数是 `-e downloadRunId <32hex>`。每run公开计划/指标及报告保存在Fixture/download-run-<runId>，设备路径与[helper握手规范](2026-10-01-native-download-helper.md)一致。

## 顺序和收尾

1. 公开根类型检查、CLI完整验签和Debug/test/live/小资源范围检查，随后upload登记；不把上传当发布。
2. 新代理进程隐形启动，只listen127.0.0.1:18474→18472；只认本进程监听。拒绝已有18474或device tcp:18472反向端口，不接管其他任务。
3. helper的arm到达后，读取实际Source S并要求API当前发布对应S。直接发布指定target，fallback=S、预期修订/replace-release/本run幂等身份均明确。发布回执及实时状态核对后才ACK armed。
4. delta在request_captured后释放指定原始对象响应并ACK released；cancellation保持等待，cancelled后先CAS回滚S，再释放与ACK。正常delta保留已确认target，正常取消恢复S。
5. 严格核对本run/mode/target/source/passed、实际字节及同次自然健康；读取最终complete控制文件，避免进程退出和最后一轮轮询的竞态。
6. 失败也只尝试恢复本轮明确发布；必须exact release ID、target、revision一致。若提交响应未知，仅在可能已提交的预期修订下重放原幂等请求取得归属；其他发布/修订不覆盖，保留失败诊断。
7. 共同300秒上界，主动工作预留最后15秒收尾；只停止本次ADB/代理，失败才停止独立Debug仪器，撤销并验证本次反向端口，恢复首页。全部核验后才生成最终成功报告和“通过”文字。

所有外部命令通过ProcessStartInfo.ArgumentList传参并设置CreateNoWindow；读取管道异步，单命令有界，不输出CLI原始错误/凭据或服务URL。操作不删除用户数据、热更对象/断点、Wallpaper项目或旧run。

## 指标与target绑定

报告保留客户端objectReadBytes/objectConnectionAttempts，不拿DownloadBudget预留代替流量。代理只统计声明slowObjectSha匹配的GET响应；driver要求每row.snapshotId==计划target、索引/offset/status/计数一致才合并，避免把同hash其他快照请求算成本目标。准确CLI发布归属、普通API授权和原始对象流程仍由真实API/客户端保障。

bodyBytesReturnedToProxy仅是上游body向代理Read返回的字节，不代表TCP送达或客户端消费；gatewayBytesAreTcpDelivery=false。helper的serverRequestCountProven保持false，driver没有把只记录慢对象的响应列表扩称全部服务端请求计数。

## 本地验证

`tools/test-native-download-driver-policy.ps1`提取实际纯policy函数并执行33项检查：候选scope/资源/大小、重复对象、非法前缀、JSON重复/大小写混淆、握手run/phase、旧回执拒绝、未健康TRIAL拒绝、预算与实际read区别、代理target/hash绑定、路径越界，以及同target不同release/新revision不得回滚。另静态核对隐藏进程、结构化参数和成功输出位于收尾验证之后。

这些mock不验证实际外部子进程/未知提交恢复或设备时序；root实际运行必须继续核对原始instrumentation、API渠道恢复和代理停止。无法确认恢复的失败不被包装为成功。

【MCP调用简报】本地限定源码/文档、PowerShell解析及33项policy/mock；无实际CLI/ADB/API/代理、秘密文件读取或push。
