# MountBehave 开发计划

更新日期: 2026-05-01

## 项目结论

MountBehave 是一个面向 OnStep / OnStepX 赤道仪的 Android 手控器。项目路线可行:OnStep 兼容 LX200 风格命令,可通过 WiFi TCP、USB 串口或蓝牙串口发送同一套 `:...#` 命令。

当前开发重点不是证明"能不能控制电机",而是把实机场景里最容易出问题的部分做稳:

- WiFi 连接和短命令发送必须稳定。
- GOTO / 校准 / 跟踪必须能被完整记录和导出,方便夜间排障。
- Park / GOTO 这些动作必须区分机械轴坐标和天球 RA/Dec,避免概念混用。
- 星图数据要离线可用,体积可控,授权清楚。

## 当前状态

已实现或已验证:

- WiFi TCP 连接 OnStep,默认端口 `9999`。
- 八方向手动移动、停止、急停、速率选择。
- 设置页观测地/时间、跟踪速率、跟踪启动/停止。
- 离线星图:恒星、星座线、DSO、太阳系天体、小天体图层、目标搜索和 GOTO。
- 两星校准实机可用,并能正常 GOTO 目标。
- 三星校准已保留为完整指向模型路径；极轴精调入口仅在保存三星或更多校准模型后可用，两星模型只用于 GOTO / 跟踪补偿。
- 小天体导入已处理 JPL SBDB 的 300 Multiple Choices、同名小行星污染、非编号彗星前缀、用户数据覆盖内置数据、空输入提示和 Android TLS 兼容问题。
- `v0.2.2` 已移除 App 里的 Set Home / Return Home 入口；Home 不再作为当前 UI 功能暴露。
- `v0.2.3` 已补强极区 GOTO:北天极附近目标使用动态阈值,固件空闲且位置稳定时释放本地 GOTO,轮询超时也会清理状态。
- 平板横竖屏旋转时改为 `onConfigurationChanged()` 重建 UI,不再销毁 Activity 或断开 OnStep 连接。
- 命令日志导出支持保存到 `Download/MountBehave` 的 `.txt` 文件,便于华为文件管理、QQ 和电脑 MTP 识别。
- GOTO 状态已加入 active target 轮询和 RA/Dec 到位复核，避免控制器提前报告空闲后错误释放下一次 GOTO。
- Debug APK 可通过 `scripts/build-debug.ps1` 构建。

已知限制:

- 目前主要实机测试覆盖晴空谐波赤道仪 ST17。
- 三星后极轴精调、三星校准、OnStepX 架台切换仍需要更多实机验证。
- 日志系统已落地，后续重点是让实机问题日志覆盖更多边界状态。

## 当前架构

主要模块:

- `OnStepClient`:WiFi TCP 连接和命令传输。
- `MainActivity`:原生 Android View UI、连接状态、手控、校准、设置。
- `SkyChartView`:星图渲染、目标选择、图层显示。
- `SkyCatalog` / `SmallBodyCatalog`:离线星表、DSO、小天体数据。
- `scripts/mock-onstep.ps1`:本地 OnStep mock,用于无实机验证命令流程。

短期原则:

- OnStep 命令发送必须尽量集中到 `OnStepClient`。
- UI 层只记录用户意图和状态变化,不要重复承担传输日志职责。
- 不为了 UI 精致牺牲夜间使用稳定性。

## 近期优先级

1. 命令日志导出实机兼容性继续回归。
2. 极区/限位 GOTO 到位判定和连续 GOTO 实机回归。
3. OnStep / OnStepX 固件能力检测和架台类型适配。
4. 极轴对齐流程改进。
5. GEM GOTO 安全:架台侧确认与监控(Phase 1 先行)。
6. 多星校准流程优化:消除"支架GOTO"按钮,改为自动前置 GOTO。
7. 星图数据和小天体导入继续回归测试。

---

# 1. 命令日志系统增强

## 目标

让用户在夜间现场不接电脑也能导出完整日志,用于 debug 分析。

必须覆盖:

- 所有实际发往 OnStep 的命令。
- 所有收到的回复。
- 所有 socket / timeout / mid-reply close 错误。
- 关键用户操作:连接、断开、移动、停止、GOTO、校准、同步、跟踪、Park、小天体下载。
- 关键状态变化:`connected`、`busy`、`gotoInProgress`、校准进度、跟踪模式。

## 实施口径

### 1. `OnStepClient` 是唯一 TX/RX 来源

Phase 1 就必须在传输边界接入日志:

- `sendNoReply(command)`:
  - 记录 `TX command`
  - 成功写入后记录 `TX_OK command`
  - 失败记录 `TX_FAIL command <ExceptionClass>: <message>`
- `query(command)`:
  - 记录 `TX command`
  - 成功读到回复后记录 `RX command -> reply`
  - 失败记录 `RX_FAIL command <ExceptionClass>: <message>`
- `handshakeQuery(command)`:
  - 同样记录 `TX`、`RX`、timeout 或空回复。

这样即使上层忘记打日志,命令也不会漏。

### 2. `MainActivity.appendLog(...)` 不再作为命令日志来源

现有 `appendLog("TX ...")` / `appendLog("RX ...")` 要迁移掉,避免重复打印。

迁移后:

- `OnStepClient` 负责 TX/RX。
- `MainActivity` 负责 `USER`、`INFO`、`WARN`、`ERROR`、`DIAG`。
- 校准诊断仍保留详细 `DIAG ALIGN_ACCEPT ...` 信息。

### 3. 最小可靠版本先落地

Phase 1 只做必要功能:

- `Logger.java`
- `LogEntry.java`
- `LogExporter.java`
- `LogShareProvider.java` 或等价 minimal `ContentProvider`,避免引入 AndroidX。
- 内存保留最近 1000 行。
- 文件写入 `files/logs/mountbehave-YYYYMMDD.log`。
- 命令日志卡片支持:
  - 勾选后记录并展开日志
  - 保存到下载目录 / 选择位置保存 / 分享当天日志
  - 清空当天日志
- 首次导出前显示隐私提示。

### 4. Phase 2 覆盖率补齐

Phase 2 在 Phase 1 的传输日志基础上补齐业务上下文,但不改变日志 UI:

- 连接/断开:记录用户点击、host/port、握手结果、连接端口、失败异常和连接状态快照。
- 手控/安全:记录移动方向、速率、停止、全局急停、取消 GOTO、刷新 GOTO 状态。
- GOTO/同步/跟踪:记录目标名、RA/Dec、快速指向修正、命令批次、跟踪速率和单双轴状态变化。
- 校准:记录建议目标、从星图选星、快速同步、多星校准开始/接受/保存/取消、极轴精调跳过和执行结果。
- Park/GOTO 安全:记录 Park/Unpark、取消 GOTO、刷新 GOTO 状态、成功、拒绝和 IO 失败。
- 设置/环境:记录 OnStep/OnStepX 与架台类型选择、手动地点、GPS 权限/GPS 结果、夜视模式、页面切换和星图图层变化。
- 小天体:记录下载参数、HTTP 状态、TLS 回退、单个彗星/小行星查询、清空用户小天体和失败异常。
- 状态快照统一通过 `state ...` 日志记录 `connected`、`busy`、`gotoInProgress`、Park、跟踪、固件、架台和校准进度。

彩色分类、过滤 chip、RecyclerView、搜索、SAF 另存为放到 Phase 3。

## 日志格式

建议单行格式:

```text
2026-04-29T21:13:44.123-04:00 [TX] :GR#
2026-04-29T21:13:44.315-04:00 [RX] :GR# -> 12:31:08
2026-04-29T21:13:45.018-04:00 [USER] tap goto target=Vega
2026-04-29T21:13:46.100-04:00 [ERROR] RX_FAIL :CM# SocketTimeoutException: Read timed out
```

要求:

- 每行最多约 2 KB,过长截断。
- 错误主日志只保留异常类、message、顶层栈帧。
- 完整崩溃栈可写入单独 `crash-YYYYMMDD.log`。
- 文件名使用 ASCII。

## 存储策略

- 内存:加锁 `ArrayDeque<LogEntry>`,最多 1000 行。
- 文件:单线程 executor 串行追加,每条日志 flush。
- 日志保留最近 7 天。
- 写线程每条日志检查日期,跨午夜时切换文件。
- `Logger.init()` 前产生的日志进入 early buffer,init 后 drain 到正式日志。

## 隐私提示

导出前提示用户日志可能包含:

- OnStep IP 和端口。
- GPS / 手动观测地经纬度。
- 校准星、目标名、时间戳、RA/Dec。

不会包含:

- WiFi 密码。
- 用户账户信息。

首次确认后 24 小时内不重复弹窗。

## 验证

- Mock 校准流程日志包含 `:A2#` / `:A3#`、`:Sr#`、`:Sd#`、`:CM#`、`:AW#`。
- 手动移动日志包含速率命令、方向命令、停止命令和执行结果。
- 断开 mock 服务后,日志包含具体异常类和命令上下文。
- 导出文件能通过系统分享发送。
- 连续 1500 行后,UI 保留最近 1000 行,文件保留全部。
- Phase 2 覆盖点编译通过,并用 `git diff --check` 确认没有空白格式错误。

---

# 2. Home 控制移除记录

## 背景

Home 是 OnStep 的机械轴参考,不是天球 RA/Dec 目标。早期版本先尝试过 App 侧 RA/Dec 快照,后又尝试暴露 OnStep 原生 Home 命令,但实机反馈显示这组入口容易被误解为普通“返回初始目标”或 Park 替代品。

`v0.2.2` 的结论是:在没有更完整的固件能力检测、安装姿态确认和明确安全提示之前,App 不再暴露 Home 标记或返回入口。

## UI

当前"安全与夜视"区域保留:

```text
[全局急停] [夜视模式]
[取消 GOTO] [刷新 GOTO 状态]
[Park] [Unpark]
```

不再显示 Set Home / Return Home,也不再提供相关确认对话框。

## 实施

- 删除 Home 按钮、字符串、状态字段和命令枚举。
- 保留 Park/Unpark 作为停放流程,保留取消 GOTO 和全局急停作为运动中止入口。
- README 只记录 Home 已移除的原因,不再给 Home 操作教程。
- 以后如需重新加入 Home,必须先补齐固件能力检测、安装姿态确认、风险文案和实机回归用例。

## 风险

- 用户可能仍需要机械 Home,但 App 当前无法可靠确认 Home 是否安全。
- Park 和 Home 不是同一概念;文档必须避免把 Park 描述成机械 Home。
- OnStepX、经纬仪、谐波赤道仪和不同开机姿态下 Home 语义差异大,不应在当前测试版里默认暴露。

## 验证

- UI 中不再出现 Set Home / Return Home。
- 代码中不再保留 Home 命令枚举和 Home 操作入口。
- 安全区域仍能执行取消 GOTO、刷新 GOTO 状态、Park/Unpark、夜视模式和全局急停。

---

# 3. OnStep / OnStepX 兼容与架台类型

## 目标

兼容经典 OnStep 和 OnStepX,并在 OnStepX 上支持架台类型识别和安全切换。

## 固件识别

握手时读取:

- `:GVP#`:产品名,例如 `On-Step` 或 `OnStepX`。
- `:GVN#`:版本号。
- `:GVD#`:编译日期。
- `:GU#`:状态字符串,用于辅助判断架台类型。

识别结果:

- `CLASSIC_ONSTEP`
- `ONSTEPX`
- `UNKNOWN`

## 架台类型

当前类型可通过 `:GU#` / `:GW#` 状态字符串判断:

- GEM / 赤道仪。
- Fork。
- AltAz / 经纬仪。
- Unknown。

OnStepX 支持写入架台类型:

- `:SXEM,1#`:GEM。
- `:SXEM,2#`:Fork。
- `:SXEM,3#`:AltAz。

写入后必须提示用户重启赤道仪。运行时切换后继续操作电机有风险。

## UI 适配

- 经典 OnStep:显示当前类型,隐藏切换按钮。
- OnStepX:显示当前类型和切换入口。
- AltAz 模式:
  - 隐藏或禁用架台侧相关按钮。
  - 禁用赤道仪极轴精调。
  - 跟踪应按双轴语义处理。
  - 限位提醒只看高度,不看中天翻转。

## 验证

- Mock classic:不显示切换按钮。
- Mock OnStepX GEM:显示切换按钮。
- Mock OnStepX AltAz:极轴精调禁用。
- 切换后发送 `:SXEM,n#`,提示重启并断开连接。
- ST17 实机确认现有连接和手控不受检测层影响。

---

# 4. 极轴对齐改进

## 目标

让目视用户在不接相机解析的情况下更容易完成极轴调整,同时保留现有两星/三星校准和 GOTO 能力。

## 推荐方案

### B 增强版:两星补偿 + 三星 Refine PA

流程:

1. 选择第 1 颗亮星。
2. 用户手动移动赤道仪把星放到视野中心。
3. App 发送 `:Sr#` / `:Sd#` / `:CM#` 接受。
4. 选择第 2 颗几何分布合适的亮星。
5. 用户手动居中后再次接受。
6. App 保存 OnStep 两星模型 `:AW#`,用于 GOTO / 跟踪补偿。
7. 如果用户需要 Refine PA,继续完成三星校准并保存模型。
8. App 解锁极轴精调入口,让用户选择一颗合适亮星并执行 OnStep `:MP#` Refine PA。
9. 用户只调机械方位角和高度角螺丝。

收益:

- 两星后可建立指向模型并改善 GOTO。
- 可开启双轴跟踪。
- Refine PA 遵循 OnStep 的三星或更多校准模型前提；经典 OnStep 和 OnStepX 赤道仪都走同一套 `:MP#` 流程。

### 保留三星模式

三星校准继续作为完整指向模型路径。

极轴精调仅在保存三星或更多模型后使用 OnStep 的 `:MP#`,并且只在赤道仪模式下启用；OnStepX 经纬仪模式隐藏该入口。

### 漂移法向导

作为高精度选项:

- 东方近赤道星用于方位误差。
- 子午线附近星用于高度误差。
- App 通过 `:GR#` / `:GD#` 周期查询 Dec 漂移率。

## 风险

- 两颗校准星几何分布不好会导致解算病态,需要提示用户重选。
- 大于约 5 度的粗对极轴误差可能需要迭代。
- 漂移法耗时长,应作为高级模式。

## 验证

- Mock:两星完成后可保存模型并启动双轴跟踪；三星完成后才解锁并接受 `:MP#`。
- Mock OnStepX AltAz:隐藏极轴精调。
- 实机:两星校准后 GOTO 仍正常；三星校准后 Refine PA 提示方向合理。

---

# 5. GEM GOTO 安全:架台侧确认与监控

## 背景

GEM(德式赤道仪)对准同一个天球目标可能存在两种机械姿态(东侧/西侧)。OnStep 收到 `:MS#` 后自行选择架台侧和移动路径,如果选择的姿态导致镜筒/相机撞到三脚架腿,App 无法提前预测——App 不知道三脚架腿方位、镜筒长度、相机和线缆姿态。

当前 `gotoTarget()` 只发送 `:Sr#` + `:Sd#` + `:MS#`,没有在 GOTO 前确认架台侧。App 有中天/低空**显示提示**,但没有**阻断式确认**。

OnStepX 提供了一组架台侧相关命令,当前项目除 `:Gm#`(已用于校准诊断)外均未实现:

| 命令 | 功能 | 返回 |
|------|------|------|
| `:MD#` | 查询当前目标的 GOTO 目标架台侧 | 0=东侧, 1=西侧, 2=未知 |
| `:GX96#` | 查询 preferred pier side | E/W/B/A |
| `:SX96,E/W/B/A#` | 设置 preferred pier side | 0/1 |
| `:Gm#` | 查询当前架台侧(**已用于校准诊断**) | E#/W#/N# |
| `:GX94#` | 查询当前架台侧(数字格式) | 0=无, 1=东, 2=西 |

`:MD#` 返回值来源(OnStepX `Goto.command.cpp`):
```
if (e == CE_NONE && target.pierSide == PIER_SIDE_EAST) reply[0] = '0';
if (e == CE_NONE && target.pierSide == PIER_SIDE_WEST) reply[0] = '1';
// 默认 reply = "2" (未知/错误)
```

**重要:** `:MD#` 返回的是"如果现在 GOTO,固件打算用哪侧",它受当前 preferred pier side 设置影响,不等于"目标在天球的哪侧"。

OnStepX 的 preferred pier side 策略(偏好,非绝对固定):
- `E`/`W`:偏好东/西侧(不可达时仍可能使用另一侧)
- `B`(PSS_BEST):偏好距离最近的侧
- `A`(PSS_AUTO):偏好跟踪可以不间断继续的侧

参考文档:
- OnStepX GOTO Notes: https://github.com/hjd1964/OnStepX/blob/main/docs/GOTO_NOTES.md
- OnStepX Command Reference: https://github.com/hjd1964/OnStepX/blob/main/docs/COMMAND_REFERENCE.md

## 设计原则

1. **App 不自作聪明避开打腿。** 硬件碰撞保护依赖 OnStep 固件的 meridian/axis/horizon limits。App 只做信息展示、确认和异常停止。
2. **不悄悄改变固件全局行为。** 如果需要临时修改 preferred pier side,GOTO 结束后必须恢复,且需处理 App 崩溃/断连时的残留。
3. **仅 GEM 生效。** AltAz 和 Fork 架台无架台侧概念,相关 UI 和检查在非 GEM 模式下隐藏。
4. **主判据用 `:Gm#` + `expectedPierSideForTarget()`。** `:MD#` 仅作为 OnStepX 辅助信息显示,不替代基于时角的保守判断。原因:`:MD#` 受 preferred pier side 影响,而架台侧安全判断需要的是物理侧 vs 天球侧的关系。

## Phase 1:中天/换侧确认(默认开启,当前实施目标)

目标:GOTO 前让用户知道可能发生翻转,提供确认机会。不修改 `:SX96#`,不自动停止正常翻转。

### 实现

1. **GOTO 前查询:**
   - 用 `:Gm#` 查询当前架台侧。
   - 用 `expectedPierSideForTarget()` 基于时角计算目标预期侧(现有逻辑)。
   - OnStepX 上额外发 `:MD#` 获取固件计划的目标侧,作为辅助信息显示在确认对话框中,但**不替代**时角判断作为主判据。
   - 如果当前侧和预期侧相同,直接发 `:MS#`。
   - 如果不同(将发生翻转),弹出确认对话框。
   - 如果 `:MD#` 返回 `2`(未知)或超时/不支持(经典 OnStep),仅使用时角判断,并在确认框中标注"估算值,非固件确认"。

2. **确认对话框内容:**
   - "该目标计划使用 X 侧(当前 Y 侧),赤道仪将翻转。请确认镜筒和线缆有足够空间后继续。"
   - OnStepX 设备上追加显示:固件目标侧 = `:MD#` 结果。
   - 按钮:确认继续 / 取消。
   - **不提供"本次会话不再提示"**。翻转风险与目标、时角、线缆状态有关,每次翻转都应确认。仅允许"本目标继续"(即用户确认后对同一目标不重复弹)。

3. **中天附近额外提示:**
   - 目标在中天 ±30 分钟时角范围内时,即使不翻转也追加提示:"目标靠近中天,跟踪过程中可能需要翻转。"

4. **兼容性降级:**
   - 经典 OnStep:`:MD#` 超时或返回空,退回纯时角估算,流程不阻塞。
   - `:Gm#` 返回 `N`(无侧)或空:记录日志,不弹架台侧确认,让 OnStep 自行处理。

### 验证

- Mock GEM:目标在对侧时弹确认,同侧时不弹。
- Mock 经典 OnStep:`:MD#` 超时后用时角估算,流程不阻塞。
- Mock AltAz:不弹架台侧确认。
- 同一目标确认后再次 GOTO 不重复弹窗;切换目标后重新弹。

## Phase 2:用户可选 GOTO 策略(高级设置)

前提:Phase 1 实机日志确认 `:MD#`、`:GX96#`、`:SX96#` 在目标 OnStep 设备上表现稳定后再开放。

### 设置项

设置页增加"GOTO 架台侧偏好"选项,仅 GEM 模式可见:

| 选项 | 行为 |
|------|------|
| 跟随 OnStep 默认 | 不修改 preferred pier side,等同 Phase 1 |
| 当前侧优先 | GOTO 前临时 `:SX96,E#` 或 `:SX96,W#`(跟随当前侧) |
| 东侧偏好 | GOTO 前临时 `:SX96,E#` |
| 西侧偏好 | GOTO 前临时 `:SX96,W#` |
| 最短路径(B) | GOTO 前临时 `:SX96,B#` |
| 跟踪连续(A) | GOTO 前临时 `:SX96,A#` |

### 实现

1. GOTO 前查询并保存当前 `:GX96#` 值。
2. 根据策略发送 `:SX96,X#`。
3. 发送 `:MS#`。
4. GOTO 完成(`:D#` 轮询结束)或失败后,恢复 `:SX96,{原值}#`。
5. **竞态保护:** App 在每次临时覆盖时记录 `{原值, 目标值, 时间戳, connectionGeneration}`。仅当 App 明确记录了"临时覆盖未恢复"且 connectionGeneration 匹配时才提示恢复;不通过"重连后比较 `:GX96#` 是否不同"来推断——用户可能在别的客户端合法修改过,固件也未必持久化该值。

### 验证

- Mock:各策略发送正确的 `:SX96#` 命令,GOTO 后恢复。
- Mock GOTO 中途断连:重连后根据记录提示恢复或忽略。
- Mock 用户在别的客户端改过 `:GX96#`:不误报残留。
- 实机:验证 `:SX96#` 生效且 GOTO 后恢复。

## Phase 3:GOTO 过程监控

前提:Phase 1/2 实机日志确认 OnStepX 翻转过程中 `:Gm#` 的实际变化规律后再实施。

### 实现

1. GOTO 轮询(已有 `:D#`)中增加 `:Gm#` 或 `:GX94#` 查询当前架台侧。
2. **运动中只记录,不自动停止。** OnStepX GOTO/翻转可能经过 home/waypoint/avoid 阶段,运动中 `:Gm#` 可能暂时处于旧侧或无侧,此时自动停止会误杀正常翻转。
3. **GOTO 空闲后核对最终状态:**
   - `:D#` 报告空闲后,查询 `:Gm#` 获取最终架台侧。
   - 与预期不一致时记录警告日志,但不自动 `:Q#`——此时赤道仪已停止,强制停止没有意义。
   - 在状态栏显示:"GOTO 完成,最终架台侧为 X(预期 Y),请确认姿态安全。"
4. **真正触发 `:Q#` 急停的条件:**
   - 用户定义的硬限位/危险区被触发(未来可扩展)。
   - 固件报告限位错误(`:MS#` 返回 6 或轮询中检测到错误状态)。
   - 不以"运动中架台侧 != 预测侧"作为急停条件。

### 验证

- Mock:正常翻转过程中不误停。
- Mock:GOTO 结束后架台侧与预期不一致时显示警告。
- 实机:正常 GOTO 不被中断;日志记录翻转过程中的架台侧变化。

## 文档与免责

README 和 App 内帮助中明确写清楚:

- App 可以提醒、确认、选择 preferred pier side、记录异常。
- App **不能替代 OnStep 固件限位**,不能保证所有机械碰撞都能提前预测。
- 用户有责任在 OnStep 固件中正确设置 meridian limits 和 axis limits。
- 首次使用 GOTO 前建议低速试运行,确认空间无碰撞。

---

# 6. 多星校准流程优化

## 现状与问题

当前多星校准流程:

```
开始校准 → 设置目标(从星图选星) → [支架GOTO](可选) → 手动居中 → 接受
```

校准第一颗星时,`acceptAlignmentStarWithDiagnostics()` 会检查当前架台侧(`:Gm#`)和目标星的预期架台侧(`expectedPierSideForTarget()`)。如果不匹配,App 阻止 `:CM#` 发送并提示用户先点"支架GOTO"。

**"支架GOTO"按钮存在的根本原因:** OnStep 的 `:CM#` 同步命令要求当前架台侧和目标星的天球位置一致。如果用户手动把赤道仪指向了一颗在东侧的星,但赤道仪物理上处于西侧,`:CM#` 会返回错误或清空校准模型。GOTO 会自动完成翻转,使架台侧和天球位置匹配。

**注意:** 校准 `:CM#` 的安全判据是物理架台侧 vs 天球侧的匹配,应继续使用 `:Gm#` + `expectedPierSideForTarget()`。`:MD#` 是 GOTO 目标侧预测,受 preferred pier side 影响,不能直接等同于 `:CM#` 的安全判据。

**用户体验问题:**

1. "支架GOTO"名称不直观,用户不理解为什么校准流程里需要 GOTO。
2. 按钮在"设置目标"和"支架GOTO"之间切换,但用户可能不注意到标签变化。
3. pier side 不匹配时的错误信息技术性太强,普通用户不知道该怎么做。
4. 整个流程步骤过多:选星 → 点 GOTO → 等移动完成 → 手动精调居中 → 接受。

## 不可消除的约束

经过分析,以下约束无法在 App 层绕过:

1. **`:CM#` 需要架台侧匹配** — 这是 OnStep 固件行为,App 无法绕过。
2. **翻转需要物理移动** — 无论谁触发,赤道仪都必须实际执行翻转动作。
3. **GOTO 后仍需手动精调** — 校准要求精确居中,GOTO 精度不够。
4. **手动移动不会改变架台侧** — 用方向键微调不触发翻转,只有 GOTO 或用户手动大幅移动并 sync 才能改变。

**结论:** 不能完全去掉 GOTO 这个动作,但可以将它融入流程,让用户感觉不到它是一个独立步骤。

## 方案:自动前置 GOTO + 简化流程

### 核心思路

将"支架GOTO"从用户手动操作变为**接受流程的自动前置步骤**:

```
原流程: 选星 → [手动点 支架GOTO] → 等移动 → 手动居中 → 接受
新流程: 选星 → 接受(自动检测 → 需翻转时弹确认 → 用户确认后自动 GOTO → 提示居中 → 再次接受发 :CM#)
```

### 详细设计

**Step 1: 统一入口**

移除独立的"支架GOTO"按钮。校准 UI 简化为:

```
[开始校准]  [设置目标]
[居中后接受]  [取消校准]
```

"设置目标"始终是选星,"居中后接受"是唯一的推进动作。

**Step 2: 智能接受流程(preflight 阶段)**

关键实现约束:当前 `acceptAlignmentStarWithDiagnostics()` 入口立即 `busy = true`,而 `sendGotoTarget()` 在 `busy` 时直接 return。因此"点接受 → 检测不匹配 → 自动 GOTO"**不能**在现有函数内部直接调用。

实现方式:拆出独立的 **preflight 阶段** 完成架台侧检查。查询 `:Gm#` 期间短暂设置 `busy = true` 防止重复点击或并发命令；回到 UI 线程后先释放 `busy = false`,再进入接受流程或弹窗后的自动 GOTO。

1. 用户点击"居中后接受"。
2. **Preflight(查询期间 `busy = true`):** 在 IO 线程查询 `:Gm#` 获取当前架台侧,用 `expectedPierSideForTarget()` 计算目标预期侧。
3. **架台侧匹配:** 直接进入 `acceptAlignmentStarWithDiagnostics()`,流程与现在一致(此时才设 `busy = true`)。
4. **架台侧不匹配且是第一颗星:**
   - 回到 UI 线程并释放 `busy`,弹出简化确认:"这颗星在赤道仪的另一侧,需要先让赤道仪转过去。转动过程中请注意镜筒空间。"
   - 按钮:"开始转动" / "换一颗星"
   - 用户确认后,调用 `sendGotoTarget()`(此时 `busy = false`,调用正常进入)。
   - GOTO 完成回调中显示:"赤道仪已到位,请用方向键精确居中后再次点击接受。"
   - 用户第二次点击"接受"时,preflight 检测架台侧已匹配,正常进入 `acceptAlignmentStarWithDiagnostics()`。

**Step 3: 引导选星避免翻转**

在建议校准星列表中,优先推荐和当前架台侧一致的星。如果用户首选星和当前侧不一致,标注提示但不阻止选择。具体做法:

- `resolveCalibrationTarget()` 选星时,查询当前 `:Gm#`。
- 排序时给同侧星加权,让它们排在前面。
- 如果用户从星图手动选了对侧的星,在设置目标后立即显示一行小提示:"此星在对侧,接受时赤道仪需要翻转"。
- 同侧优先只是排序权重,不强制排除对侧星;几何分布检查仍然保留。

**Step 4: 后续星的处理**

第二颗及以后的校准星,OnStep 已建立部分模型,`:CM#` 对架台侧的要求更宽松(当前代码也只在 `acceptedBefore == 0` 时检查)。这些星不需要前置 GOTO,preflight 直接放行。

### 文案优化

| 现在 | 改为 |
|------|------|
| "支架GOTO" | (移除) |
| "OnStep 当前支架侧为 E，但这颗星按当前时间应在 W 侧接受；为避免 OnStep 清空校准，请先点击"支架GOTO"…" | "这颗星在赤道仪的另一侧,需要先转过去。请确认镜筒空间后点击"开始转动"。" |
| "设置目标" / "支架GOTO"(同一按钮切换) | "设置目标"(始终固定) |
| "已让赤道仪 GOTO %s。现在请用方向键把它重新居中…" | "赤道仪已转到 %s 附近。请用方向键精确居中,然后点击"居中后接受"。" |

### 降级兼容

- 如果 `:Gm#` 查不到(返回空或 `N` 或超时),退回时角估算,行为与现在一致。
- 如果自动 GOTO 被 OnStep 拒绝(`:MS#` 返回非 0),显示拒绝原因并建议"换一颗同侧的星",不阻塞用户手动操作。

### 风险

- 用户点"接受"后赤道仪突然大幅移动可能吓到新用户 → preflight 弹确认对话框,文案明确说"赤道仪将转动",用户必须主动点"开始转动"。
- 自动 GOTO 失败后用户可能困惑 → 显示拒绝原因并建议换星。
- 建议星排序变化可能导致选出几何分布不好的星 → 同侧优先只是排序权重,几何分布检查仍然保留。

### 验证

- Mock GEM 架台侧不匹配:点接受后弹确认(preflight 阶段,`busy` 仍为 false),确认后自动 GOTO(正常进入 `sendGotoTarget`),GOTO 完成后提示居中,再次接受成功(进入 `acceptAlignmentStarWithDiagnostics`)。
- Mock GEM 架台侧匹配:点接受后直接 `:CM#`,无额外步骤。
- Mock AltAz:无架台侧逻辑,直接接受。
- Mock GOTO 被拒绝:显示原因,不卡死流程。
- 建议星列表:同侧星排在前面,对侧星标注提示但可选。
- 实机:两星校准完整流程,验证翻转和接受。

---

# 7. 星图与小天体数据

## 已完成

- 恒星显示星等限制提升到较暗星等,并支持窄视场寻星。
- 太阳、月亮、行星使用离线低/中精度算法。
- 小行星/彗星支持用户按名称或编号从 JPL SBDB 添加。
- 用户添加的小天体覆盖内置旧数据。
- 彗星支持椭圆、近抛物和双曲轨道分支。
- 星图支持银河大致位置。

## 后续关注

- 太阳系天体精度说明要在 README 中保持清楚。
- 太阳系天体不应作为校准目标。
- 小天体下载失败时必须写入日志,包含查询参数、HTTP 状态和异常原因。
- 数据授权说明要继续保持在 README 中。

---

# 8. 构建与验证

## 常用命令

```powershell
cd MountBehave
powershell -ExecutionPolicy Bypass -File .\scripts\doctor.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\build-release.ps1
```

Debug APK 输出:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Mock 验证

`scripts/mock-onstep.ps1` 应覆盖:

- 连接握手。
- 移动 / 停止。
- GOTO。
- 两星 / 三星校准。
- OnStepX 架台类型查询和切换。
- 故障回包和超时。
- OnStepX 架台侧命令:`:Gm#`、`:GX94#`、`:MD#`、`:GX96#`、`:SX96,X#`。

## 实机验证顺序

1. 连接和停止。
2. 手动移动八方向。
3. GOTO 单目标。
4. 两星校准。
5. 三星校准。
6. 跟踪启动/停止。
7. Park/Unpark。
8. 小天体下载与星图显示。

---

# 资料来源

- OnStep GitHub README:https://github.com/hjd1964/OnStep
- OnStep release-4.24 `Command.ino`:校准和 LX200 命令参考。
- OnStepX `Mount.command.cpp` / `Telescope.command.cpp`:架台类型和固件能力命令参考。
- OnStepX GOTO Notes:https://github.com/hjd1964/OnStepX/blob/main/docs/GOTO_NOTES.md
- OnStepX Command Reference:https://github.com/hjd1964/OnStepX/blob/main/docs/COMMAND_REFERENCE.md
- Android USB Host docs:https://developer.android.com/develop/connectivity/usb/host
- Android WiFi permissions:https://developer.android.com/guide/topics/connectivity/wifi-permissions
- JPL SBDB / Horizons:https://ssd.jpl.nasa.gov/
- Yale Bright Star Catalog:https://tdc-www.harvard.edu/catalogs/bsc5.html
- HYG Database:https://astronexus.com/projects/hyg
- Stellarium skycultures:https://github.com/Stellarium/stellarium-skycultures
