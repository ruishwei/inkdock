# 后台离线朗读（alpha.6）

离线朗读的完整片段队列交由 Android mediaPlayback 前台 Service 保存和推进。Activity/WebView 只是控制与展示端，Home、锁屏不再依靠网页收到 done 后提交下一段。系统结束应用进程后不会自动恢复播放，也不在开机时自动启动。

通知和媒体会话提供暂停、恢复与停止。暂停恢复会从当前分片开头重读，尚非逐字恢复。耳机拔出或丢失音频焦点会暂停，避免意外外放；请主动恢复。停止会清空队列并释放播放资源。前台服务是系统允许持续播放的机制，不能保证绕过厂商省电限制。

云朗读继续逐片段明确同意；进入后台会取消当前云流程，未确认片段不会发送。返回前台后需重新点击并确认，不把一次同意扩展成整本书授权。

## 验证边界

计划在 Android 15 模拟器使用测试 APK 内的合成 TTS 引擎，走真实系统 TTS Binder 路径，验证 Home/锁屏跨段、媒体控制停止。测试不证明第三方真实引擎音质、Vivo Y300t 省电策略或扬声器实际出声。以对应提交 CI 报告为运行结果。

## 平台依据

- [Android 14 前台服务类型与权限](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Android 13 通知运行时权限及媒体会话豁免](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [音频焦点](https://developer.android.com/media/optimize/audio-focus)
- [后台播放架构](https://developer.android.com/media/media3/session/background-playback)

使用平台 MediaSession/Service 接口，无需新增云协议或模型依赖。KVM 测试权限仍默认 false；本轮 alpha.6 及必要重跑已另获临时 runner 当前测试用户 ACL 授权，不涉及用户设备。

## alpha.6 回归记录

- 本地 Node 回归 27 项通过；生产 Kotlin 和所有 instrumentation 源码签名编译通过（非 APK 构建）。
- 新增 5 项 Android 设备回归：Home 后灭屏跨 3 片段；通知实际 PendingIntent 停止；通知暂停/恢复；ACTION_SEND 分享后重建保留队列；未确认云片段后台不发送；导入选择器取消无遮罩（首项合并多个断言）。测试引擎只打包在单独 test APK，生产 APK 不含测试入口。
- 原生回归使用通知已发布的 PendingIntent.send()，不是手点通知按钮；屏幕睡眠/唤醒与 Home 使用系统 keyevent。
- 独立审查修复：试听后台被 JS 停止、分享 Intent 重建重复导入、过期快照污染新文档/手动阅读进度、服务恢复快照被旧 queueId 拒绝。
- 最终 APK、lint、浏览器及 Android15 测试运行结果以本版本 GitHub Actions 为准；无真实云请求或真实离线引擎音质验证。
