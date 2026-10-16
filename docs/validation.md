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
