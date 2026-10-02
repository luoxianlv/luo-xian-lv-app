# OAuth CN 回调轻量验证（2026-10-02）

APP 新授权使用 `https://www.luoxianlv.cn/login/callback`，发起时将地址与 state、PKCE verifier 一起保存，换码时复用同一地址。旧版已发起但未完成、尚未记录地址的请求保留 `.com` 回调兼容；新授权不再使用旧域名。

网页本来就按当前 origin 生成回调，CN 页面会将 APP 授权返回到 `luoxianlv://oauth/callback`。服务端透传请求中的 redirect_uri，无需部署网页或 API；未覆盖网页仓库已有的其他修改。

验证通过：

- Debug 三层 APK 构建、3 项 PlatformResponseParserTest，0 失败。
- 浏览器访问 CN 回调页，缺授权码时显示预期错误。
- 使用公开测试 state/PKCE challenge 访问新授权入口，正常跳至鼠鼠登录页并保留 CN redirect_uri；未进行实际账号登录，不能据此宣称已完成真实授权或完全核实供应商白名单。
- CN 换码接口发送空 JSON，返回 400 / oauth_code_required，未请求或使用真实授权码。
- 模拟器安装新 Debug APK，发送错误 state 的测试 APP 回调，系统成功打开 MainActivity，进程保持运行，没有发起真实登录。

原始构建日志 `.local/oauth-cn-smoke-build-20261002.txt`。本轮为轻量联通与回调检查，没有执行真实账号授权、令牌交换、APP 发版或热更投放。
