# 系统文件预缓冲实验（未发布）

本分支使用synthesizeToFile和私有临时音频两槽预准备；不是仅QUEUE_ADD排队。代码/主机测试通过，Android平台性能待验证。无原生Kokoro JNI，不覆盖main正式预览。见docs/bounded-speech-prefetch.md。

# InkDock

轻量、开源、离线优先的 Android Markdown + LaTeX 阅读器。第一版面向 Vivo Y300t / Android 15；离线阅读不需要账号，不申请广泛存储权限。可选实验性云语音需要用户自己的服务商账号/API key，并使用网络权限。代码采用 MIT 许可。

> 当前为早期预览。Android 编译结果见 [Actions](https://github.com/ruishwei/inkdock/actions)，安装包见 [Releases](https://github.com/ruishwei/inkdock/releases)。尚未经过 Vivo Y300t 真机验证。

## 当前功能（alpha.6）

- 系统文件选择器与 Android「分享」导入 UTF-8 Markdown / 文本（上限 8 MiB）
- KaTeX 脚本、样式和全部字体随 APK 打包；断网也能显示 `$...$`、`$$...$$`、`\(...\)` 与 `\[...\]`
- H1–H6 分级、可折叠目录，点击章节标题定位
- 按完整 Markdown 顶层块分组，每次最多渲染 40 块；代码、表格、列表、展示公式不会被固定字数截断
- 内容 SHA-256 标识文档，以段落/块锚点记住进度；字号、夜间模式
- 单排紧凑图标底栏：目录、朗读/停止、更多。轻点正文显示或隐藏；无悬浮恢复按钮
- 不再提供上下划或右滑目录操作，滑动只用于阅读。目录标题直接跳转，独立箭头展开/收起子章节；字号、主题、声音与导入移至「更多」
- 短轻点排除选字、控件、公式/代码/表格、弹层、双击与滚动后短时间误触；保留隐藏滚动条。系统栏背景与日夜正文协调，内容继续避让系统栏
- 朗读所选文字或从当前正文段连续往后朗读；「声音」选择已安装系统引擎、查看请求引擎与当前语言/音色，选择该引擎已安装离线中文音色、调节语速、保存并试听；无语音包时明确提示

## alpha.6 后台与离线安装中心

- 离线队列由 mediaPlayback 前台服务持续推进，通知/锁屏媒体控制可暂停、恢复和停止。暂停恢复从当前分片开头重读；Home/锁屏不再依赖网页回调推进。音频焦点丢失、耳机拔出会暂停。
- 声音设置增加“离线包安装与配置”：官方来源、已安装引擎配置/模型管理、重新检测与返回选音色。此中心不在 InkDock 内下载模型，下载与配置由外部兼容系统 TTS 引擎完成。
- 更多中的导入/字号/主题等动作先关闭面板，取消文件选择不留下更多遮罩。
- 云朗读仍需逐片段确认，后台取消云流程，不能后台自动批准新片段。

后台实现及平台依据见 [后台阅读说明](docs/background-reading.md)，外部模型管理边界见 [离线语音中心](docs/offline-voice-center.md)。真实第三方引擎与 Vivo Y300t 省电策略仍需真机确认。

## 安装和试读

从 Releases 下载 `InkDock-debug.apk`，可用 `SHA256SUMS.txt` 校验。按 Android 提示允许当前浏览器或文件管理器安装应用。打开后即有合成中文公式示例，点击「导入」选择自己的 `.md` 文件。导入只在设备内生成当前文档副本，不上传。

预览版使用 CI 临时生成的 Android debug 签名。不同预览版本可能签名不同，升级时可能需要先卸载旧版，卸载会清除本地副本和进度。请保留原始 Markdown 文件。当前不提供正式发行签名。

## 构建

需要 JDK 17、Gradle 8.11.1、Android SDK Platform 35 / Build Tools 35.0.0。Android Studio 可以直接打开项目，配置 SDK/JDK 后构建。也可安装上述官方工具后：

```sh
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
gradle --no-daemon :app:assembleDebug :app:lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。仓库暂未附带 Gradle wrapper；请安装指定 Gradle，CI 使用 Gradle 官方 setup action 下载对应版本。

## 测试与离线资源

需要 Node.js 22。运行：

```sh
npm ci --ignore-scripts
npm test
npm run vendor
git diff --exit-code -- app/src/main/assets/vendor
```

测试覆盖 H1–H6、代码/公式内伪标题、长块边界、CRLF、空文档、未闭合代码、1 万块索引、列表和引用定义、KaTeX 实际渲染及 CSS 引用的所有字体存在。离线资产是提交到仓库的真实依赖文件，不从 CDN 加载。可用 `CHROMIUM_PATH=/usr/bin/chromium node scripts/browser-smoke.cjs` 运行桌面 Chromium 冒烟测试（不是 Android 真机测试）。

版本：Android Gradle Plugin 8.9.2、Kotlin 2.1.20；JS 依赖的精确版本及完整性见 `package.json` / `package-lock.json`，许可证随 `assets/vendor` 一同分发。应用 UI 没有远程资源；系统 WebView 与 TTS 由 Android 提供。

## 返回与语音排查（alpha.4）

系统返回先关闭目录、更多或声音弹层，再次返回才离开阅读器。Android 13–15 使用原生 Back 回调，旧系统有兼容处理。

语音区分「请求已排队」「引擎报告开始」「完成」「错误」。入队成功不等于手机已经出声。失败会显示具体错误并提示，声音设置可查看本次会话诊断（仅状态、字符数、耗时和错误码，不含正文）；若引擎快速报告完成却无声音，请检查媒体音量、蓝牙输出、系统 TTS 语音包与离线音色。声音设置支持切换已安装 Android TTS 引擎；可选实验性 Qwen / MiniMax 原生云朗读配置及逐片段确认。真实云账号可用性、收费和音色效果尚未实测。

CI 运行 Android 15 模拟器的原生 Back 回归和语音状态机测试；模拟器结果不能代替 Vivo Y300t 真机音频验证。

## 安全边界与限制

- Manifest 为可选云 TTS 声明 INTERNET 权限；每次启动默认系统离线。WebView 仍禁止网络请求，只允许应用自带资产。只有原生云模块在用户确认当前服务商、完整片段和可能费用之后发起 HTTPS 请求。导入 HTML 按文字显示，渲染结果经 DOMPurify 清洗；外链导航、图片和表单禁用。KaTeX `trust:false`。
- 文档源码、索引仍整体保存在内存；仅 DOM 渲染分组，不是流式文件读取或无限虚拟滚动。极大的单个表格/代码块仍可能卡顿。8 MiB 是保护上限，不是低内存设备性能保证。
- 只保留一份当前文档副本。重新导入相同内容可恢复旧锚点；修改内容会被视作新文档。没有书架、全文搜索、手工书签或导出进度。
- 支持从当前正文段连续往后朗读并高亮，选中文字只读所选部分。标题、纯公式与代码块跳过；长段分片不截断尾部。播放中按钮明确变为「停止」，不是暂停，重播从当前段落起读。公式语音不是专门的数学朗读。按系统引擎的离线/已安装标记筛选音色，不请求在线音色或下载。系统 TTS 是独立服务，其实现由设备供应商决定；音色情感和自然度不能靠切换语言保证。应用可请求切换已安装引擎，并分别保存音色。Android 公开 API 无法查询实际绑定引擎且可能回退，因此界面标注“请求使用引擎”；不把系统默认名称当成实际连接证明。VoxSherpa/Piper/Kokoro 需另行安装兼容的 Android TTS 引擎和中文离线模型，本仓库不附带其代码或模型。
- Markdown/KaTeX 不等于完整 TeX；不支持宏包、外部资源或任意 HTML。现代 JavaScript 要求较新的 Android System WebView；主要验证目标为 Android 15。
- 不支持 EPUB/PDF、图片、云同步。没有分析埋点。

## 发布

GitHub Actions 在 push/PR 时测试并构建 debug APK。维护者可在 `Android debug` 的 `Run workflow` 输入 `release_version`（如 `v0.1.0-alpha.6`）；构建成功后使用临时 GITHUB_TOKEN 发布 prerelease、APK 与 SHA256，无需持久密钥。

## English

An offline-first Android Markdown + LaTeX reader MVP. Native Kotlin shell, bundled KaTeX/Marked/DOMPurify, collapsible headings, bounded block rendering, local reading anchors, font/theme controls and device offline Chinese TTS. Optional native cloud TTS uses INTERNET permission, defaults to offline every launch, and requires per-segment native consent. Keys are entered only in native UI and encrypted with Android Keystore. Cloud effects/account compatibility are not live-verified. Preview builds are debug-signed and not yet device-validated. MIT; third-party notices are bundled.

## 多语音验证边界（alpha.6）

- 系统引擎切换：切换先停播，销毁旧实例，generation 隔离旧初始化/朗读回调；离线中文音色按引擎保存。
- Qwen/MiniMax：输入模型、音色和官方 HTTPS 地址；API key 不经 JavaScript，不写日志，以 Keystore AES-GCM 加密保存到不备份目录。支持删除本机 key。
- 每一云片段都要重新确认精确文本、服务商、地址和潜在收费；取消或进入后台使当前请求失效，音频只临时存于私有不备份目录，结束/取消删除。已提交服务端的请求无法保证撤回或免收费。
- 本轮只使用合成文字和 mock 做契约/错误/取消测试，没有请求付费接口，没有上传用户文档，没有真实账号或音色实测。统一试听样例是人工合成测试文案，云试听仍需要用户确认真实发送。
- 自定义配置地址只接受已实现协议对应的官方 HTTPS 主机；不接受任意代理。禁止 HTTP、凭据内嵌 URL、重定向和向音频下载发送 API key。IndexTTS2 仅保留未来自建服务设计，不展示已可用按钮；Pocket/Supertonic 不作为中文默认选项。
- 不做声音克隆，不把多模态对话 API 当成 TTS。

工程交付状态见 docs/voice-next-validation.md；独立源码审查已完成；请核对对应提交的 Android CI 结果，模拟测试不能代替真实服务商与真机音频验证。
