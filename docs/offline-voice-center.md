# 离线语音安装与配置：交付边界与后续原生路线

## 此次可用功能

声音设置新增“离线包安装与配置”。这是**外部 Android TTS 引擎配置中心，不是内置模型下载器**。

- 打开维护者官方 sherpa TTS Engine APK 目录、VoxSherpa Releases、Kokoro 中文说明、Piper 中文模型卡。按钮明确“打开/查看”，不会声称下载成功。
- 通过系统 TTS_SERVICE 查询当前安装且启用的导出服务；不通过名称猜测模型是否就绪。
- 对每个引擎发送限定包名的 ACTION_INSTALL_TTS_DATA；不支持则打开其启动页。设置入口读取该服务真实 tts-engine 元数据中的 settingsActivity，校验同包、exported、enabled；不可用回退系统 TTS/无障碍设置。
- 点“重新检测”更新引擎；回声音设置选择该引擎→切换/重试→选择离线中文音色→保存并试听。模型/音色仅按引擎实际报告列出，没有虚构已安装状态。
- 打开原生窗口前关闭所有 Web 面板；重复打开只保留一个原生窗口。系统 Back 关闭窗口后不恢复遮罩；显式“返回声音设置”按钮才重新打开声音设置。
- 只固定官方 HTTPS 网页入口；不携带书中文字，不开放任意 URL bridge。浏览器预览会说明功能仅 Android 可用。
- 下载进度、暂停取消、存储占用、删除、模型选择属于独立引擎应用，InkDock 本版不读取/管理其沙箱文件。

## 上游来源核实（2026-10-17）

1. sherpa-onnx Android 官方说明明确提供 SherpaOnnxTtsEngine 供电子书应用替换系统引擎；普通 TTS 演示 APK 并非该接口。
   - https://github.com/k2-fsa/sherpa-onnx/blob/master/android/README.md
   - https://k2-fsa.github.io/sherpa/onnx/android/prebuilt-apk.html
   - https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html
2. Kokoro v1.1-zh 原始权重 Apache-2.0，中英双语、103 个说话人；原始 PTH 327 MB。原始权重 SHA256 为 b1d8410fa44dfb5c15471fd6c4225ea6b4e9ac7fa03c98e8bea47a9928476e2b，**不是转换后 ONNX/压缩包的哈希，不能复用作下载校验**。
   - https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh
   - https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html
   - https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/kokoro/v1.1-zh/run.sh
   - 官方列出 kokoro-int8-multi-lang-v1_1.tar.bz2 与非量化版；尚未取得并核实发行包 SHA256，因此没有把这两个地址作为自动下载安装源。
3. Piper huayan medium ONNX 63,201,294 bytes，配置另需 6,922 bytes；模型卡中的 Dataset License 明确 Unknown。仓库页总体 MIT 标签不等于此训练集许可已知。不能把未解决的训练数据许可写成 MIT 模型。
   - https://huggingface.co/rhasspy/piper-voices/blob/main/zh/zh_CN/huayan/medium/MODEL_CARD
   - https://huggingface.co/rhasspy/piper-voices/blob/2525f22960f62209936f0ae3cce3ffe7b2c18f1f/voices.json
4. VoxSherpa 维护者发布页和仓库说明提供系统 TTS/应用内模型管理；Android 11+、ARM64。它是第三方独立应用，其 GPL 代码没有复制或链接入 InkDock。
   - https://github.com/CodeBySonu95/VoxSherpa-TTS/releases
   - https://github.com/CodeBySonu95/VoxSherpa-TTS/blob/main/LICENSE
5. eSpeak-NG 有独立 GPL 许可；sherpa 主仓库 Apache-2.0 不能自动覆盖原生二进制依赖。
   - https://github.com/espeak-ng/espeak-ng/blob/master/COPYING

## 内嵌路线：尚未完成，不能与此配置中心混为一谈

直接集成 sherpa-onnx JNI 是技术上可行的后续工程，当前仓库没有该推理库。不是只加一个 ONNX 下载按钮就能播放。完成标准如下：

1. 锁定官方 sherpa-onnx 发布版本、Android arm64-v8a JNI/Kotlin API，并验证下载物哈希/来源、16KB page 对齐、Android 8–15 支持。审计所有传递依赖及 eSpeak/GPL 义务，确定是否可与当前 MIT 代码按合规方式发布；必要时隔离为独立引擎 APK，不能隐去 GPL 依赖。
2. 优先 Kokoro 中文 v1.1-zh int8；锁定转换脚本版本、模型发布包、完整 SHA256 清单及每个必要文件（模型/voices/tokens/词典/中文 FST/语音处理数据）许可证。原始 PTH 哈希不能验证 ONNX。Piper 中文需额外解决 huayan 数据许可 Unknown，或选择有明确许可的替代中文模型。
3. 模型下载器：明确大小、剩余空间检查、Wi-Fi/移动数据提示、分块进度、取消、失败重试、临时文件与原子提交、完整哈希验证、防路径穿越解压；未验证前不允许选为可用音色。
4. 存储与配置：私有模型目录、已验证版本清单、使用中禁止误删、取消后清理、模型删除/空间显示；音色编号来自准确版本映射，不能跨v1.0/v1.1复用。
5. 推理：后台服务单线程模型生命周期、音频焦点、AudioTrack/MediaSession、分段预生成、取消 generation token、防旧音频继续、内存释放/低内存处理。与系统 TTS 服务路径共享队列接口但不假设相同回调。
6. 验证：至少一次真实中文模型校验→加载→合成→播放→取消→切音色→删除闭环；离线/坏包/断网/空间不足/进程终止/锁屏/来电/耳机断连测试；Y300t实际延迟、内存、温升与听感。不能以编译或无音频模拟器取代真机试听。

工作量按阶段而非虚假截止估计：许可与制品验证、下载存储层、JNI推理层、后台音频接合、设备矩阵测试均为独立工程/验收项。任何未取得许可或实机不达标都应阻止“内置可用”宣称。

## 本次验证

- Node 全套测试及新增安装中心路由/重复点击/浏览器降级/固定HTTPS入口检查。
- Kotlin 所有原生源码以 Android API stubs 编译检查，结果见交接报告。
- 未在用户设备安装第三方 APK，未下载大型模型，未实际试听神经中文模型。
- 安装器、OEM设置页面、第三方模型管理页需 Android 真机/集成测试进一步确认；系统 intent 有安全回退，不伪造成功。
