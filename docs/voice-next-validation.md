# 多语音候选验证记录

基线：alpha4 2eddc49e9625f193e71c8b01166bfb695641aa5b；仅在独立副本开发。同步了后续 workflow 的单次 KVM ACL 显式许可开关，下一版不能沿用上一轮授权。

## 当前已运行

- Node 单元测试 21/21 通过（阅读索引、手势、分段、资源和 WebView 网络隔离）。
- Kotlin 2.1.20 对全部生产 Kotlin 源码的独立编译通过，使用 Android 15 android-all API jar、JVM target 17；这不是 APK/dex 构建。
- 23 项 JVM 测试通过（4 项 SpeechTracker + 19 项云请求/响应/确认/取消/假 HTTP 传输测试）；没有真实联网。
- 新增 tests/reader-voice.test.cjs 的 5 项假 DOM / 真实 reader.js 回归已纳入 npm test；不代表浏览器布局或设备播放验收。
- 独立代码审查发现的分段上限、重复确认、迟到 player 回调、超时恢复、临时文件关闭清理和晚到系统初始化问题均已修复并复查。
- 未调用任何付费 API；没有真实 API key，没有发送用户文本。

## 尚未通过/尚未运行

- 新增 scripts/test-voice-ui.cjs：系统/云来源切换、切换先停止、初始化中禁用试听、配置/试听/停止路由、Back 关闭。首次本地执行被沙箱 socket 限制阻塞；提升执行返回宿主 bwrap 只读错误，浏览器断言尚未运行。CI 已加入此脚本。
- 本机没有 Gradle/Android SDK，完整 APK/dex 构建、Android lint 和原生测试需由集成 CI 执行。已单独从 Maven Central 获取校验过的 Kotlin/测试依赖用于源码编译和 JVM 测试，不能代替设备验收。
- 真机系统引擎/音色、云账户兼容性、实际音质、耗电与费用均未实测。模拟测试不代表音质验证。

## 验收清单

- 快速 A→B→A 切换只接受最后 generation 的初始化/朗读事件；失败/超时可重新选择或重试。
- 系统引擎仅列已安装且声明不需联网的中文 voice；显示请求引擎而非错误声称实际绑定引擎。
- 云配置保存不发请求；native 密钥输入不回显、不进入 JS、不备份；删除后不能再次使用。
- 缺 key、错误 key、额度不足、超时、非法 JSON、错误音频、不安全地址/重定向/越界下载均安全报错；Qwen 返回的官方区域 OSS HTTP 音频链接仅在校验后改为 HTTPS，绝不发出 HTTP 请求。
- 确认框必须展示 provider/HTTPS endpoint/model/voice/完整片段/潜在费用；取消、返回、进入后台、改来源后不得继续请求或播放；旧结果不更新当前队列。
- 云音频播放完成才发 done；报错/取消不得自动续下一段。每片段另行确认，当前确认不授权整本书。
- 临时音频结束/取消/重启清理；服务端已接受的请求可能收费，取消不能保证撤回。

## 最终独立审查

当前生产源码无剩余已知阻断项。审查另行核对了 Qwen OSS 下载兼容性：阿里云官方确认 bucket 域名原生支持 TLS；只将允许区域 bucket 的 HTTP 返回链接升级为 HTTPS，保留原始签名路径/查询，不联网尝试 HTTP，不跟随重定向，不传 API Authorization，不重试降级。测试验证连接始终为 HTTPS。

文档：https://www.alibabacloud.com/help/en/oss/user-guide/access-oss-by-https-protocol

后续集成需对候选提交执行完整 CI，再决定版本号及是否发布；本目录未自行提交或发布。

## alpha.5 集成记录

版本 code 5 / 0.1.0-alpha.5。保留 alpha.4 最终 AVD 目录修复、限时清理及默认 false KVM 门控。本轮 alpha.4/alpha.5 必要临时 runner 测试重跑已获授权，不扩展到其他版本或用户设备。集成后 21 Node、全生产 Kotlin 独立编译、23 JVM 测试通过；二次只读审查无代码阻断项。补强临时音频文件创建失败时内存清零，新增引擎及实验云声音手机尺寸截图。最终 APK/lint/Android 模拟器与浏览器结果仍以对应提交 CI 为准。真实服务商 API 与 Vivo Y300t 音频未实测。
