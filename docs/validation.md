# 预览版验收记录

## 云端本地已通过

- `npm test`：8/8 通过。覆盖 H1–H6、公式/代码伪标题、块边界、CRLF、空文档、未闭合代码、10,001 块索引、完整列表与引用定义。
- Node 直接执行随应用打包的 KaTeX 0.19.0，渲染积分公式成功。
- 检查 KaTeX CSS 引用的所有字体都已随应用打包，无 CDN 资源。
- `npm audit`：0 个已知漏洞（2026-10-16）。
- JavaScript 语法检查通过。

## 独立静态审查与修复

- 原先空行切块会破坏列表和跨段引用，已改为 Marked 完整顶层 token 索引，保留全局引用定义并新增回归测试。
- 不可信 HTML 的 id/style 可能影响阅读控件，已将原始 HTML 转义为文字，并用 DOMPurify 清洗生成结果、移除 id/name/链接属性。KaTeX 信任模式关闭。
- 空文档遗留旧页导航，已重置页码、状态和按钮；浏览器测试覆盖重复导入及空导入。
- CI 增加离线资产重新生成后差异检查，确保 package-lock 与提交资产一致。

## CI / 设备待验证

本地环境没有 Android SDK、Gradle 或 JDK 编译器，无法在此编译 APK。桌面 Chromium 受到沙箱 socket 限制，未成功启动；不将其记为测试通过。

GitHub Actions 会执行浏览器冒烟测试、Android assembleDebug 与 lintDebug。浏览器测试覆盖公式、目录、表格、主题/字号、分页、空导入、HTML 隔离及无网络请求，并保留 390×844 公式页/目录/夜间截图 artifact。测试结果以对应 commit 的实际 Actions run 为准。

Vivo Y300t / Android 15 的安装、系统文件选择器、分享入口、离线中文 TTS、横竖屏与真实超长文档性能需要真机确认。桌面浏览器通过也不能替代真机验收。

## alpha.2（2026-10-17）

- 基线为已发布 alpha.1 / main `9edcf8691aeeefe42b8a45d4921b3e8e514d063e`，无其他远端源码变更。
- 本地 Node 测试 12/12 通过：新增下划显示、上划隐藏、右滑目录、边缘排除、选字/多指/长按/斜滑过滤。
- 底部工具栏由 Android 父布局避让系统栏，CSS 兼容浏览器 safe-area；保留「工具」恢复入口与目录按钮。CSS 与原生 WebView 均隐藏滚动条。
- CI 浏览器测试追加实际 touch 事件处理路径、公式/代码/表格排除、选字保护、底部位置断言、声音面板模拟桥，保存底部栏与声音设置截图。模拟桥不验证真实 Android TTS。
- 离线音色排除 network-required 与 notInstalled，避免主动请求下载；保存前检查 setVoice/setSpeechRate 结果和实际 voice；设置保存在设备 SharedPreferences。系统默认引擎标签与实际语言/音色分别展示，音色质量取决于外部系统引擎。
- alpha.2 Android 编译、lint 与浏览器测试结果须以新 commit 的 Actions run 为准。Y300t 系统返回边缘/导航栏/原生选字和声音效果仍待真机确认。
