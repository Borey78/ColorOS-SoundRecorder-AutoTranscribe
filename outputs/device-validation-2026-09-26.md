# 录音自动转写 1.4.5：2026-09-26 验证记录

## 候选包与本地验证

- 候选包：[RecorderAutoTranscribe-v1.4.5-background-queue-candidate-signed.apk](RecorderAutoTranscribe-v1.4.5-background-queue-candidate-signed.apk)，包名 `io.github.borey.recorderautotranscribe`，versionCode `24`，versionName `1.4.5`。
- APK SHA-256：`6D41FAF974B5DF921994110E6835C40C90013FB5050977208D072D444FA11E29`。
- `:app:testDebugUnitTest` 与 `:app:assembleRelease` 构建通过；40 项单测全通过：ModulePolicy 4、RetryPolicy 13、ScanLifecycleState 4、ScheduledJobCoordinator 11、TaskPolicy 8。
- `apksigner verify` 验证 APK Signature Scheme v3 通过；签名证书 SHA-256 与已安装版本一致：`16614a8768ce54829333ae40ebb7e941f97a3a5e66b6d22d596bd5415e4b329a`。`zipalign -c 4` 通过。
- APK 静态权限清单没有 `RECEIVE_BOOT_COMPLETED`。周期任务仍调用录音机的 15 分钟 Job，未设置持久化、网络、充电或空闲约束。注册时先复用匹配的既有任务，避免重复 `schedule()` 同一 ID。

## 本版代码调整

- JobService 的 `onStartJob` 将扫描交给队列后保持任务运行；冷回调若先于桥接器就绪会先留在待处理 token 中，桥接器就绪后再触发扫描。重复 callback 合并到覆盖该 token 的扫描。
- 扫描快照只有在设置 Provider 确认持久化提交成功后才标记为“成功并保存”。扫描失败请求系统重试；系统中止时停止关联扫描。停止和最终快照回执按同一锁顺序协调；主线程待执行的原服务调用在派发前再次检查中止状态；尚未派发的尝试会恢复原有 retry 标记。
- 设置页将任务登记、最近任务启动、桥接就绪、扫描尝试、成功保存时间和失败状态分别呈现。`100013` 仍作为少于 100 字的短文本终态跳过。
- 重启后首次打开录音机时重建周期任务的路径保留；没有开机接收器，也没有承诺用户打开录音机前的无人值守恢复。

## 设备端状态与边界

- 有线设备序列号 `4d78905a` 确认为 PMA110、Android API 36；录音机为 16.17.8。候选以同签名覆盖安装，设备查询确认模块版本为 1.4.5。安装前前台不是录音机；AudioRecordingMonitor 没有活动录音会话，未操作 V2Ray/VPN 或网络设置。
- 08:54:37，LSPosed 日志记录周期任务登记成功、桥接就绪及录音机 hook 激活；08:54:38 收到 Job ID `1129599538` 的启动回调并开始 `seq=1` 扫描。模块状态页显示“扫描完成”和“最近成功并保存 08:54”；当时录音行已是缓存快照，120/114/91/10/19 是旧统计，不能证明新转录或摘要完成。
- 09:19 再次打开录音机后，LSPosed 记录桥接就绪；日志先后记录 `seq=0` 与 `seq=1` 两个扫描快照保存，均为 120 条。JobScheduler 历史同时记录 Job ID `1129599538` 启动、结束；随后 `get-job-state` 为 `waiting`，周期任务仍登记。该回调是在用户打开录音机并加载进程后发生，不能证明 JobScheduler 单独唤醒冷进程。
- 冷进程专项复测中，普通 `am kill` 因录音机仍有云同步/推送服务而没有结束 PID。确认无录音后，仅终止录音机 PID。`cmd jobscheduler run -f -u 0` 与约束满足模式都没有产生新的进程或启动历史；窗口内周期任务也没有自行启动。再次打开录音机后任务重新登记。故 JobScheduler 单独唤醒冷进程仍未通过；当前候选支持打开录音机后重建任务。
- 本轮 LSPosed 日志还记录 `recorder home entry failed`；首页快捷入口未在本轮人工验收。
- 摘要的“已提交／已启动”仍不算完成。发布酷安前，需要在目标手机上看到新扫描时间和扫描完成回执，并单独确认至少一条新转写和一条摘要已写入录音机原库。
- `outputs/coolapk-description.md` 是 1.4.5 草案，尚未发布。
