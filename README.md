# InkDock

轻量、开源、离线优先的 Android Markdown + LaTeX 阅读器。第一版面向 Vivo Y300t / Android 15；不需要账号，不申请网络或广泛存储权限。代码采用 MIT 许可。

> 当前为早期预览。Android 编译结果见 [Actions](https://github.com/ruishwei/inkdock/actions)，安装包见 [Releases](https://github.com/ruishwei/inkdock/releases)。尚未经过 Vivo Y300t 真机验证。

## 第一版功能

- 系统文件选择器与 Android「分享」导入 UTF-8 Markdown / 文本（上限 8 MiB）
- KaTeX 脚本、样式和全部字体随 APK 打包；断网也能显示 `$...$`、`$$...$$`、`\(...\)` 与 `\[...\]`
- H1–H6 分级、可折叠目录，点击「阅读此节」定位
- 按完整 Markdown 顶层块分组，每次最多渲染 40 块；代码、表格、列表、展示公式不会被固定字数截断
- 内容 SHA-256 标识文档，以段落/块锚点记住进度；字号、夜间模式
- 朗读所选文字或当前可见段落，使用系统已安装的离线中文 TTS 语音；无语音包时明确提示

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

## 安全边界与限制

- Manifest 没有 INTERNET 权限；WebView 禁止网络请求，只允许应用自带资产。导入 HTML 按文字显示，渲染结果经 DOMPurify 清洗；外链导航、图片和表单禁用。KaTeX `trust:false`。
- 文档源码、索引仍整体保存在内存；仅 DOM 渲染分组，不是流式文件读取或无限虚拟滚动。极大的单个表格/代码块仍可能卡顿。8 MiB 是保护上限，不是低内存设备性能保证。
- 只保留一份当前文档副本。重新导入相同内容可恢复旧锚点；修改内容会被视作新文档。没有书架、全文搜索、手工书签或导出进度。
- 预览版不会自动连续朗读，公式语音不是专门的数学朗读。需要系统离线中文语音包；不会回退到网络语音。
- Markdown/KaTeX 不等于完整 TeX；不支持宏包、外部资源或任意 HTML。现代 JavaScript 要求较新的 Android System WebView；主要验证目标为 Android 15。
- 不支持 EPUB/PDF、图片、云同步。没有分析埋点。

## 发布

GitHub Actions 在 push/PR 时测试并构建 debug APK。维护者可在 `Android debug` 的 `Run workflow` 输入 `release_version`（如 `v0.1.0-alpha.1`）；构建成功后使用临时 GITHUB_TOKEN 发布 prerelease、APK 与 SHA256，无需持久密钥。

## English

An offline-first Android Markdown + LaTeX reader MVP. Native Kotlin shell, bundled KaTeX/Marked/DOMPurify, collapsible headings, bounded block rendering, local reading anchors, font/theme controls and device offline Chinese TTS. No INTERNET permission. Preview builds are debug-signed and not yet device-validated. MIT; third-party notices are bundled.
