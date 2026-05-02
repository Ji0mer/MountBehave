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
5. README 根据实测结果补充”停止后 GOTO 恢复”和”校准架台侧”说明。
6. 星图同步 + 渐进式指向修正模型（见下节）。

## 星图同步 + 渐进式指向修正模型

状态：已实现。Quick Sync 已从校准页移到星图页“同步”按钮；GOTO/取消合并为同一按钮；App 端 `PointingCorrectionModel` 会在保存两星/三星模型后记录同步残差并在后续 GOTO 前做加权预修正。

### 背景与动机

当前 Quick Sync 放在校准页面，操作路径长且概念上与”校准”绑定，容易误导用户认为它是一次性操作。实际上用户的真实需求是：**GOTO 到达后微调居中，然后就地同步，越同步越准**。这和校准流程是两回事。

同时，当前 `quickPointingCorrection` 只记录一个偏移点，有效范围硬限 10 度，超出即失效——对跨天区使用无效。

本方案将 Quick Sync 从校准页面移除，在星图页面提供 Sync 按钮，并在 2/3 星校准之后构建一个**多点残差修正模型**，随每次同步逐步提升 GOTO 精度。

### 设计决策

#### 两层修正架构

```
OnStep 固件模型 (2/3 星对齐 :AW#)  ← 全局几何修正，保持不变
          +
App 端多点残差修正模型              ← 叠加局部细节，随同步积累
```

OnStep 的多星模型负责修正极轴偏差、机械非正交等系统误差。App 端模型记录 OnStep 模型未覆盖的残余误差，用于每次 GOTO 前预偏移目标坐标。二者独立，不互相干扰。

#### 校正点数据结构

每次用户在星图页面点击 Sync，记录一个校正点：

```
CorrectionPoint {
    double catalogRaHours      // 目标的星表坐标
    double catalogDecDegrees
    double residualRaHours     // = postSyncMountRa - catalogRa（:CM# 之后查询，归一化到 [-12, 12]）
    double residualDecDegrees  // = postSyncMountDec - catalogDec（夹紧到 [-45, 45]）
    long   timestampMs         // 记录时间，用于时效性权重
}
```

`residualRA/Dec` 是 `:CM#` 执行后 OnStep 仍未消化的残余误差。`:CM#` 本身会让 OnStep 在该区域做一次自我修正；若修正后 mount 报告坐标仍与星表有偏差，说明固件无法完全吸收，这部分才需要 app 补偿。若 `:CM#` 后残差很小（< 0.25°），说明 OnStep 自己处理好了，该点不写入模型。

**为何不记录同步前的误差：** `:CM#` 之后 OnStep 已经更新了该区域的内部偏移。如果 app 记录的是同步前的大偏差并在下次 GOTO 时施加，会与 OnStep 自身修正叠加，导致过矫正（double-correction）。

#### 加权插值逻辑（多点生效）

GOTO 前对目标坐标做残差修正：

```
对于新 GOTO 目标 T：
  corrRA = 0, corrDec = 0, totalWeight = 0

  for 每个校正点 P:
      dist = angularDistance(T, P)
      age  = (now - P.timestamp) / SESSION_HALF_LIFE_MS
      w    = exp(-dist² / σ²) × exp(-age)   // 距离高斯权重 × 时间衰减
      corrRA  += P.errorRa  × w
      corrDec += P.errorDec × w
      totalWeight += w

  if totalWeight > MIN_WEIGHT_THRESHOLD:
      commandRA  = T.ra  - corrRA  / totalWeight
      commandDec = T.dec - corrDec / totalWeight
  else:
      commandRA, commandDec = T.ra, T.dec   // 校正点太少/太远，不施加修正
```

参数参考值（可后续调整）：
- `σ = 15.0 度`（高斯宽度，控制校正点影响范围）
- `SESSION_HALF_LIFE_MS = 2小时`（旧校正点权重随时间指数衰减）
- `MIN_WEIGHT_THRESHOLD = 0.01`（低于此阈值视为无有效校正）
- 最多保留 `MAX_CORRECTION_POINTS = 30` 个点，超出时淘汰最旧的

#### 与现有 `quickPointingCorrection` 的关系

新模型完全替代 `quickPointingCorrection`。`gotoCommandPoint()` 改为从 `PointingCorrectionModel` 查询，`clearQuickPointingCorrectionIfOutOfRange()` 不再需要（加权衰减天然处理距离问题）。

#### 模型生命周期

| 事件 | 对残差模型的操作 |
|------|----------------|
| 2/3 星校准完成 (`:AW#`) | **清空**所有校正点（OnStep 模型已更新，旧残差无效） |
| 断开连接 / 重连 | **保留**校正点（OnStep 模型持续有效，残差仍有参考价值） |
| 在星图 Sync 一次 | **追加**一个校正点 |
| 不满足 2/3 星模型条件（`hasAlignmentTrackingModel = false`） | Sync 按钮正常工作，但不记录残差（只发 `:CM#` 给 mount，和原 Quick Sync 等价） |
| 极区（Dec > 80°） | 跳过该点的残差记录（同现有极区保护逻辑） |

#### Sync 发出的命令序列

```
:Q#
:Sr<RA>#
:Sd<Dec>#
:CM#
:TQ# / :To# / :T2# / :Te#
:GR# → afterRa
:GD# → afterDec
→ residualRa  = afterRa  - catalogRa
→ residualDec = afterDec - catalogDec
→ if hasAlignmentTrackingModel
     && |residual| > POST_SYNC_RESIDUAL_THRESHOLD（0.25°）
     && |catalogDec| < 80°:
       加入校正点 (catalogRa, catalogDec, residualRa, residualDec, now)
```

残差在 `:CM#` **之后**读取，确保记录的是 OnStep 无法自行消化的部分，避免与 OnStep 自身修正叠加。

### 实现步骤

1. **新建 `PointingCorrectionModel.java`**
   - 存储 `CorrectionPoint` 列表（最多 30 个）
   - 提供 `addPoint(catalogRa, catalogDec, postSyncMountRa, postSyncMountDec)`，内部计算残差并判断阈值
   - 提供 `correctTarget(Target) → EquatorialPoint`（含加权插值逻辑）
   - 提供 `clear()`
   - 不依赖 Android，便于单元测试

2. **改造 `MainActivity` 中的指向修正相关代码**
   - 新增 `PointingCorrectionModel pointingModel` 字段（替代 4 个 `quickPointing*` 字段）
   - `gotoCommandPoint()` 改为调用 `pointingModel.correctTarget(target)`
   - 删除 `enableQuickPointingCorrection()`、`clearQuickPointingCorrection()`、`clearQuickPointingCorrectionIfOutOfRange()`、`actualPointingFromMountReport()`
   - `saveAlignmentModel()` 完成后调用 `pointingModel.clear()`
   - `cancelAlignmentSession()` 完成后调用 `pointingModel.clear()`

3. **在星图页面新增 Sync 按钮**
   - 按钮显示条件：已连接 + 非 busy + 有选中目标（星图当前高亮目标）
   - Handler：`syncStarChartTarget(Target target)`
   - 成功后 toast/状态栏显示”已同步 [目标名]，模型已更新（N 个校正点）”
   - 若 `!hasAlignmentTrackingModel`：成功后显示”已同步 [目标名]”（与建模前的 Quick Sync 等价）

4. **从校准页面移除 Quick Sync**
   - 删除校准页面的”快速同步”入口按钮及相关 `setCalibrationTarget()`、`syncQuickCalibrationTarget()` 路径
   - 校准页面保留：2星/3星对齐、Refine PA

5. **日志**
   - `addPoint` 时记录：`INFO pointing-model-add target=... mountDelta=... points=N`
   - `correctTarget` 时若施加修正：`DEBUG pointing-model-apply target=... corrRAdeg=... corrDecdeg=... weight=...`
   - 清空时记录：`INFO pointing-model-clear reason=alignment-saved|cancel`

#### 坏模型（校准偏差 > 1°）下的覆盖边界

若用户 2/3 星校准时未精确居中，模型初始误差可能超过 1 度，此时同步的效果：

| 情况 | 结果 |
|------|------|
| Sync 后 GOTO 同区域目标 | ✅ OnStep 的 `:CM#` 已在该区域自我修正，精度大幅提升 |
| Sync 后 GOTO 附近目标（< σ = 15°） | ✅ app 残差模型插值覆盖，精度提升 |
| Sync 后 GOTO 远处目标（无校正点覆盖） | ⚠️ 坏模型误差在其他天区可能不同，app 不施加修正（`totalWeight < MIN_WEIGHT_THRESHOLD`） |
| 多次 Sync 分布在不同天区 | ✅ 各区域逐步覆盖，精度越来越均匀 |
| 初始偏差极大（> 5°）导致目标出镜 | ❌ 用户无法居中，Sync 无从触发；建议重做校准 |

本方案不修复底层坏模型，只逐区域打补丁。如果坏模型误差在全天差异很大（如极轴严重偏移），需要足够多的 Sync 点才能覆盖全天，建议仍以重做校准为优先。

### 待验证

- `:CM#` 前后 mount 报告坐标差异日志：确认残差确实在同步后被 OnStep 吸收，而非同步前后无变化。
- 2 星校准后首次 GOTO，偏差约 X arcmin；Sync 一次后再 GOTO 同目标，偏差应明显减小。
- 坏校准场景（有意不对准校准星）Sync 一次后同区域 GOTO，不应出现过矫正。
- 跨天区 GOTO（> σ，无校正点），`totalWeight < MIN_WEIGHT_THRESHOLD`，不施加修正。
- 校准完成时校正点正确清空；重新校准后旧残差不影响新会话。
- 极区目标不触发残差记录，但 `:CM#` 仍正常发出。
- 断开重连后校正点保留，GOTO 精度维持。

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
