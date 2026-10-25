# 1.3.13 验证记录

- 构建：`:app:testDebugUnitTest` 和 `:app:assembleRelease` 均通过。
- 签名：release APK 通过 APK Signature Scheme v2 校验。
- SHA-256：`87EB7E895C4A7654289C9AD44E571CB2F8BC401FD885B76A2EA3A0510F91DDE6`。
- 安装：验证机已安装 `io.github.borey.recorderautotranscribe` 1.3.13（versionCode 18）。
- 跳转：录音机 16.17.8 的 `PlaybackActivity` 可解析。转写使用录音机自身的 `convert` 页参数；摘要使用其自身的 `summary` 页参数。
- 状态边界：转写入口不再显式传入会让录音机误判“摘要已删除”的 false 值；即使摘要只有部分结果，也可点“查看摘要”回到原应用查看。
- 文件位置：每条记录会携带原录音路径；有转写结果时一并携带录音机生成的转写文本路径。摘要实际保存在 `ai_summary.db`，没有声称存在独立摘要文件。
- 边界：验证时手机前台正在使用，未强制打开或抢占录音机页面；待设备空闲后，从模块内点“查看转写／查看摘要／文件位置”即可进行最终前台验收。
