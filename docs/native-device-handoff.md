# 原生缓冲设备实验执行说明

目标分支：`experiment/kokoro-buffer`。工作流：`.github/workflows/kokoro-device.yml`，显示名称 `Kokoro buffered Android device experiment`。仅手动 workflow_dispatch；没有 Release 步骤。

## 启动门槛

`allow_runner_kvm_acl` 默认 false。仅在获得本轮原生/缓冲设备实验及必要重跑明确许可后，手动选择目标分支并设 true。权限动作只给当前临时 GitHub runner 用户 /dev/kvm 读写 ACL；不改用户设备。不沿用 alpha.6 授权。许可未到时可以提交代码，但不触发需要权限的运行。

## 固定输入与缓存

- 复用已通过的仓库 `ruishwei/inkdock` run `39017151369`、commit `66356a624b116b61b9f022ccd2fdba247ed3e2e7`。
- audit artifact `InkDock-kokoro-experiment-audit-39017151369`，ID `12095860545`。身份/digest与JNI/provenance/notices字节重新校验；不重新编译JNI。artifact过期/删除时安全失败，不能替换成别的版本绕过门禁。
- 使用runner现成JDK17、SDK35/BuildTools35、NDK；缺必要SDK许可证时失败，不自动接受条款。
- 模型按源码KokoroManifest的固定URL/大小/SHA下载。float 381,766,704字节，int8 170,433,930字节，各8文件；runner缓存目录 `build/kokoro-device/models`。每次使用均校验；当前无跨run持久cache依赖，首次运行需要公开模型下载。只将校验过的模型放入测试debug应用私有目录，生产acquire再次验hash。
- 测试设备固定API35 x86_64 emulator-5554；脚本拒绝向任意用户手机写入。

## 验证与产物

构建当前源码APK和test APK、lint、JVM/JS及本地安全测试后，运行准确6项Android测试：真实两模型/两个中文speaker WAV、真实后台native播放、三项取消/hash/staging、合成快PCM的后台预取与停止。仪器exit0不等于通过，脚本校验准确用例、无跳过/失败并检查success JSON。

产物：`InkDock-kokoro-device-evidence-<run>` 包含4段仅合成测试句WAV、2份指标JSON、日志及JUnit；`InkDock-kokoro-buffer-device-apk-<run>` 包含APK/hash/source commit。无小说正文、密钥或用户文件。日志仅来自合成测试。

失败时先取报告修对应源码/测试，不降低合成或播放断言。真实模型测试不要求RTF<1，不把callback或模拟器音频当Vivo真机质量证明。当前调试APK仍保留双ABI/审计符号，约207MB基线；不作为最终轻量手机分发版。
