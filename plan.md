# MountBehave 开发计划

更新日期: 2026-05-02

## 当前结论

MountBehave 是面向 OnStep / OnStepX 赤道仪的 Android 手控器。当前主线不是继续扩大功能面，而是把实机夜间使用中最容易出错的流程做稳:

- WiFi TCP 连接失败必须能被明确识别并退出假连接状态。
- GOTO、校准、停止和恢复必须有完整日志，方便复盘。
- GEM 架台侧、OnStep 模型状态和 App 本地状态不能混用。
- 不用 App 侧高度限制替代用户需求；低空彗星等目标允许 GOTO，但必须正确处理 OnStep 限位/拒绝。

## 已完成能力

以下内容已实现，不再作为计划展开:

- WiFi TCP 连接、手动移动、急停、Park/Unpark、跟踪控制。
- 离线星图、恒星/DSO/太阳系/小天体目标搜索和 GOTO。
- 两星/三星校准、模型保存、三星后 Refine PA。
- Set Home / Return Home UI 已移除。
- 极区 GOTO 动态到达阈值和固件 idle 后备判断。
- 横竖屏旋转不再断开连接。
- 命令日志开关、导出、单文件分享和 QQ/华为兼容路径。
- GOTO 失败恢复锁定:连续限位/硬件错误且远离目标时暂停新 GOTO，手控或重连后恢复。
- 星图银河图层、星图时间弹窗、低空目标不做 App 侧强行拦截。

## 本轮实机日志修复

依据日志:

- `mountbehave-20260501-215954 (1).txt`
- `mountbehave-20260501-222012 (1).txt`

前提结论:OnStep 模式下，每次开机后的第一次两星/三星校准是正确的。问题集中在取消、重开、停止后恢复和架台侧异常路径。

### 1. 校准结束状态不可见

问题:多星校准中点击“结束”后，本地状态清空，但顶部状态文本容易被后续 `:Q#` / `:A0#` 异步状态覆盖，用户看不到校准已经结束。

修复:

- `cancelAlignmentSession()` 改走统一 `runMountCommands()` 批处理。
- UI 显示“正在结束校准...”和“校准流程已结束，可重新开始”。
- 无论命令成功还是失败，本地 alignment session 都会清理，避免按钮状态残留。

待验证:

- 校准 1/2 或 1/3 时点击“结束”，状态栏应明确显示已结束。
- 随后再次点击“开始校准”，UI 不应卡在旧目标。

### 2. 重新开始校准时连接/固件半状态

问题:日志中 `:A2#` / `:A3#` 可能 timeout；随后 Android 出现 `ENONET` 时 App 仍显示连接保持，导致用户继续发命令但全部失败。

修复:

- 校准启动失败会回滚本地 alignment session，并提示“校准启动失败”。
- 校准启动批处理失败时会尝试发送 `:Q#` + `:A0#` 清理 OnStep 半校准状态。
- 对 `ENONET`、`EHOSTUNREACH`、`ENETUNREACH`、`EPIPE`、`ECONNRESET` 等硬网络错误，App 主动关闭连接并提示重新连接。
- 普通 `SocketTimeoutException` 不直接断开，避免把固件慢回复误判为 WiFi 断线。

待验证:

- Mock 注入 `:A2#` timeout 后，App 应回到未校准状态。
- 实机 WiFi 断开或切网后，App 应显示连接已断开，而不是继续假连接。

### 3. 校准 preflight GOTO 不一定真正翻转架台侧

问题:第一颗校准星接受前，App 检测 `:Gm#` 与目标预期侧不匹配并提示 GOTO，但 OnStep 默认 preferred pier side 可能仍选择当前侧，导致到达 RA/Dec 后 `:Gm#` 仍不匹配，用户陷入重复弹窗。

修复:

- 校准 preflight 的自动 GOTO 会尝试读取 `:GX96#`，临时设置 `:SX96,E#` 或 `:SX96,W#` 到目标预期侧。
- `:MS#` 发送后立即恢复原 preferred pier side，避免长期改变固件全局偏好。
- 如果固件不支持 `:GX96#` / `:SX96#`，自动降级为原 GOTO 流程并写 DIAG 日志。
- 同一目标自动 GOTO 后仍然架台侧不匹配时，不再重复弹“开始转动”，改为提示换同侧校准星或手控移动到正确侧。
- `:GX96#` 支持情况在当前连接生命周期内缓存；经典 OnStep 首次超时后不再每次多等。
- 若 `:SX96#` 临时覆盖后恢复失败，App 会记录 pending restore，并在下一次 GOTO 或重连后优先尝试恢复原值。
- pending restore 使用 600ms 短超时；固件不支持 `:SX96#` 时停止恢复尝试并提示。
- 用户主动断开连接会清掉 pending restore，避免下一台赤道仪被写入旧会话的 preferred pier side。
- 如果 pending restore 失败但当前仍要执行校准 preflight GOTO，App 仍会尝试把 preferred pier side 设置为当前目标需要的侧，而不是静默退回默认 GOTO。

待验证:

- OnStepX 或支持 `SX96` 的固件上，第一颗星需要换侧时应真实换侧。
- 经典 OnStep 不支持该命令时不能崩溃，日志应记录降级。

### 4. 停止/急停后下一次 GOTO 路径异常

问题:模型建立后急停或手动停止，下一次 GOTO 有时会先绕到北天极附近再去目标。日志显示 App 在 `:Q#` 后很快看到一次空 `:D#` 就下发新目标，OnStep 内部状态可能尚未稳定。

修复:

- 每次 GOTO 前的 `:Q#` 后增加 500ms settle。
- 最多 8 次轮询 `:D#`，并要求连续 2 次 idle。
- idle 时同时读取 `:GR#` / `:GD#`，要求位置稳定在 0.05 度内，避免刚停下仍在内部收敛时下发新目标。

待验证:

- 急停后立即 GOTO Castor、Capella、太阳/月亮等不同区域目标，不应出现无意义极点绕行。
- 低空目标仍允许发送；如果 OnStep 限位拒绝，应走现有 GOTO 恢复/诊断逻辑。

### 4.1 审阅后补强

- ENONET/EHOSTUNREACH/EPIPE 等硬断网错误出现后，不再尝试 `:Q#` / `:A0#` 失败清理，避免额外 socket connect timeout。
- 用户在 busy 状态下点击校准“结束”时，仍会先清理本地校准状态，避免 UI 留在旧会话。
- `:GX96#` 读取改为短超时，W/B/A/E 都按“读到 # 或 socket close”处理。
- `:SX96#` 空回复不再视为成功。
- `:SX96#` pending 恢复和普通恢复也改为短超时。
- 主动 disconnect 清 pending；被动 connection-lost 保留 pending 以便同一设备重连后收尾。

### 5. 校准接受时 `:Sr#` / `:Sd#` 延迟

问题:ALIGN_ACCEPT 路径仍用 `query()` 读取 `:Sr#` / `:Sd#` 单字符回复，导致每条命令多等约 2 秒。

修复:

- `acceptAlignmentStarWithDiagnostics()` 中 `:Sr#` / `:Sd#` 改为 `queryShortReply()`。
- `:CM#` 保持 `query()`，因为该命令可能返回带 `#` 的字符串。

待验证:

- 接受每颗校准星时，`Sr/Sd` 回复应从约 2 秒降到毫秒级。

## 其他日志观察

- 低空/近地平目标是用户刚需，App 不做 15 度高度硬拦截。
- 日志中 Moon / Castor 等长路径 GOTO 最终成功；这类路径需要继续通过实机观察区分“正常大角度 slew”和“停止恢复异常”。
- `:GE#`、`:Gm#` 和 GOTO idle 诊断仍是后续判断限位、架台侧和硬件故障的核心依据。

## 后续优先级

1. 实机回归本轮 5 项修复。
2. OnStepX 架台类型检测和 UI 适配继续验证。
3. Refine PA 三星后实机验证。
4. Mock 增加 `:GX96#` / `:SX96#`、硬网络错误、校准启动 timeout 场景。
5. README 根据实测结果补充“停止后 GOTO 恢复”和“校准架台侧”说明。

## 验证命令

```powershell
cd E:\Android_projects\MountBehave
powershell -ExecutionPolicy Bypass -File .\scripts\doctor.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\build-release.ps1
```

Debug APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 资料来源

- OnStep: https://github.com/hjd1964/OnStep
- OnStepX GOTO Notes: https://github.com/hjd1964/OnStepX/blob/main/docs/GOTO_NOTES.md
- OnStepX Command Reference: https://github.com/hjd1964/OnStepX/blob/main/docs/COMMAND_REFERENCE.md
- JPL SBDB / Horizons: https://ssd.jpl.nasa.gov/
