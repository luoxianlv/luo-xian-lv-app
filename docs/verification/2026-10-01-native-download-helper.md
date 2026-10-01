# 实际Android下载取消/资源字节仪器：本地准备

新增 `NativeDownloadChecks.run(runner, home, runId)`；runner接线、投放、ADB与代理由根任务负责。本子任务只完成辅助源码、独立javac和7项实际计划/文件握手测试，没有Android成功回执。

## 范围与前置条件

- 仅 `app.luoxianlv.debug`、Debug工作线程、明确automatic/test和 `http://127.0.0.1:18472` 配置。
- 当前来源必须是真实已稳定热更快照，页面在线空闲，没有已有演练场或候选观察。plan的sourceIdentity为空时记录实际来源；非空须一致。
- 1–8个已知小对象，每份≤1MiB、合计≤2MiB。全部对象须没有准确内部副本，且`.part`不存在；辅助不会清理断点来满足条件。
- 捕获普通prepare操作的token引用，要求实际对象请求已开始且指定慢对象的`.part`长度等于前缀。不能最后只读取 `lastRequest`，它会被后续authorize/健康请求覆盖。
- 反射仅读取当前token、调度及来源；不设置priority/Bridge、不改变时钟，不调用check、tick、activate或健康确认。

## 固定计划与握手

root先写 `files/native-download-checks/<runId>/plan.json`（UTF-8无BOM），runId是新32位小写hex。辅助创建`.started`；旧标记或已有control/ACK/report均拒绝，不删除旧内容。

```json
{
  "schema": 1,
  "runId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "mode": "delta",
  "targetSnapshotId": "<64位小写SHA-256>",
  "sourceIdentity": "",
  "expectedObjects": [{"sha256": "<目标对象SHA-256>", "size": 554}],
  "expectedMissingBytes": 554,
  "slowObjectSha": "<expectedObjects中的SHA-256>",
  "prefixBytes": 0
}
```

示例哈希占位须替换，不能直接运行。`mode`仅cancellation/delta；取消前缀必须1..min(size−1,32768)，delta允许0。多个对象的expectedMissingBytes须准确求和。

control.json字段：schema、runId、mode、phase、targetSnapshotId、slowObjectSha、expectedMissingBytes、sourceIdentity、requestedElapsedMs、deadlineElapsedMs。

| control phase | root动作 | ACK |
| --- | --- | --- |
| arm | 代理准备好只延迟指定对象的原始响应，再发布真实签名测试候选 | armed |
| request_captured（delta） | 解除目标响应等待，保留准确原始字节和普通授权 | released |
| request_captured（cancellation） | 继续保持延迟，辅助实际进入演练场 | 不立即释放 |
| cancelled | 先CAS回滚独立测试渠道至原稳定来源，再解除代理，避免取消后普通续传激活目标 | released |
| complete / failed | 收尾代理与本run，保留证据 | 无 |

ACK固定 `{"schema":1,"runId":"<本run>","phase":"armed|released"}`。同run同phase才接受，严格拒绝未知/重复字段、BOM、链接与超限文件。ACK只确认外部driver动作已执行；辅助仍读取真实token/断点/Source/日志才能判成功。driver必须原子替换ACK，不能边写边读。

## 两种真实观察

cancellation：实际启动PracticeActivity，观察真实准备或生命周期变化，等待捕获token明确取消、worker/inFlight结束且失败计数不增加；准确前缀保持。稳定/活动/候选/attempt/phase/quarantine保持，渠道/信任下限可以因正常发现/外部CAS单调增加。关闭自己创建的演练场并恢复原首页，来源实例始终保持原S。转屏可能先使生命周期不可用；报告分别记录priority/lifecycle观察，`purePriorityCauseProven=false`，不把任意进入取消冒称纯优先原因。

delta：代理在完成body之前等待，让小包token能被可靠捕获。释放后读取捕获操作的最终成功正文read字节，严格等于expectedMissingBytes；实际target来源、对象归属/size/内部hash须一致，runtime/business APK必须和原来源相同。仪器在同次运行继续等普通前台健康观察、journal STABLE且stable=target、整组已收尾；不在TRIAL结束并用下一进程冒充同一观察。健康与字节分开记录，不注入时钟，也不缩短60秒窗口。

对象connectionAttempts是客户端尝试连接数；Range/头部重试会增加它，不等于服务器收到的请求或成功次数。objectReadBytes只计成功read出的对象正文，含重试，不是预算预留或TCP重传。代理端实际请求计数由root另外记录并合并，辅助的serverRequestCountProven始终false。

主动观察共用真实单调时钟的285秒预算，演练场关闭和原窗口恢复共用最后15秒；正常结束总时长须≤5分钟。不复用run，不修改业务缓存/用户项目，失败不生成passed=true。若系统主线程本身卡死，框架的同步主线程/启动调用仍可能被系统阻塞，root外部instrumentation上界负责终止；本地policy检查不声称对此具备硬实时保证。

## 独立检查

`pwsh -NoProfile -File tools/test-native-download-plan.ps1`：使用现有公开类和Android37 SDK独立编译真实新helper与NativeNetworkChecks，不执行Gradle。7项通过：精确有界计划、重复/错sum/错mode等拒绝，取消前缀边界、旧run与旧ACK不复用、ACK身份/阶段、坏JSON/超限/重复字段，以及控制文件硬链接原子替换不破坏来源。

这些是实际helper协议检查，不模拟Android下载或声称取消、554字节或健康已通过。后续由root生成候选、代理和设备回执。

【MCP调用简报】本地限定文件、公开编译产物、javac/JUnit；7项通过；无Gradle、设备、API、网络、生产凭据或push。
