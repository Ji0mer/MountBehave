# MountBehave 开发计划

更新日期: 2026-04-29

## 项目结论

MountBehave 是一个面向 OnStep / OnStepX 赤道仪的 Android 手控器。项目路线可行:OnStep 兼容 LX200 风格命令,可通过 WiFi TCP、USB 串口或蓝牙串口发送同一套 `:...#` 命令。

当前开发重点不是证明"能不能控制电机",而是把实机场景里最容易出问题的部分做稳:

- WiFi 连接和短命令发送必须稳定。
- GOTO / 校准 / 跟踪必须能被完整记录和导出,方便夜间排障。
- Home / Park / GOTO 这些动作必须区分机械轴坐标和天球 RA/Dec,避免概念混用。
- 星图数据要离线可用,体积可控,授权清楚。

## 当前状态

已实现或已验证:

- WiFi TCP 连接 OnStep,默认端口 `9999`。
- 八方向手动移动、停止、急停、速率选择。
- 设置页观测地/时间、跟踪速率、跟踪启动/停止。
- 离线星图:恒星、星座线、DSO、太阳系天体、小天体图层、目标搜索和 GOTO。
- 两星校准实机可用,并能正常 GOTO 目标。
- 三星校准已修复接受星流程,仍需更多夜间测试。
- 小天体导入已处理 JPL SBDB 的 300 Multiple Choices、同名小行星污染、非编号彗星前缀、用户数据覆盖内置数据、空输入提示和 Android TLS 兼容问题。
- Debug APK 可通过 `scripts/build-debug.ps1` 构建。

已知限制:

- 目前主要实机测试覆盖晴空谐波赤道仪 ST17。
- 三星校准、极轴精调、OnStepX 架台切换、Home 原生命令仍需要更多实机验证。
- 当前命令日志仍不够完整,需要升级为可导出的全应用调试日志。

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

1. 命令日志系统增强 + 导出。
2. Home / Return Home 改用 OnStep 原生命令。
3. OnStep / OnStepX 固件能力检测和架台类型适配。
4. 极轴对齐流程改进。
5. 星图数据和小天体导入继续回归测试。

---

# 1. 命令日志系统增强

## 目标

让用户在夜间现场不接电脑也能导出完整日志,用于 debug 分析。

必须覆盖:

- 所有实际发往 OnStep 的命令。
- 所有收到的回复。
- 所有 socket / timeout / mid-reply close 错误。
- 关键用户操作:连接、断开、移动、停止、GOTO、校准、同步、跟踪、Park、Home、小天体下载。
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
  - 复制最近 100 行
  - 导出当天日志
  - 清空当天日志
- 首次导出前显示隐私提示。

### 4. Phase 2 覆盖率补齐

Phase 2 在 Phase 1 的传输日志基础上补齐业务上下文,但不改变日志 UI:

- 连接/断开:记录用户点击、host/port、握手结果、连接端口、失败异常和连接状态快照。
- 手控/安全:记录移动方向、速率、停止、全局急停、取消 GOTO、刷新 GOTO 状态。
- GOTO/同步/跟踪:记录目标名、RA/Dec、快速指向修正、命令批次、跟踪速率和单双轴状态变化。
- 校准:记录建议目标、从星图选星、快速同步、多星校准开始/接受/保存/取消、极轴精调跳过和执行结果。
- Park/Home:记录 Park/Unpark、Set Home、Return Home 的确认、成功、拒绝和 IO 失败。
- 设置/环境:记录 OnStep/OnStepX 与架台类型选择、手动地点、GPS 权限/GPS 结果、夜视模式、页面切换和星图图层变化。
- 小天体:记录下载参数、HTTP 状态、TLS 回退、单个彗星/小行星查询、清空用户小天体和失败异常。
- 状态快照统一通过 `state ...` 日志记录 `connected`、`busy`、`gotoInProgress`、Home、Park、跟踪、固件、架台和校准进度。

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

# 2. Home / Return Home 重构

## 背景

旧方案把 Home 当成 RA/Dec 快照保存,再通过普通 GOTO 回去。这会受到 LST、对齐模型、子午线翻转和极轴误差影响,不适合作为"机械回家"功能。

正确方案是使用 OnStep / LX200 原生命令:

- `:hC#`:Calibrate at home,把当前轴位置标记为 home。
- `:hF#`:Find home,让赤道仪机械回到 home。

这两个命令工作在轴坐标,不依赖 RA/Dec、GOTO 模型或校准残差。

## UI

在"安全与夜视"区域加入:

```text
[全局急停] [夜视模式]
[Set home] [Return home]
[取消 GOTO] [刷新 GOTO 状态]
[Park] [Unpark]
```

`Set home` 和 `Return home` 都要有确认对话框。

## 实施

- `OnStepCommand` 增加:
  - `HOME_CALIBRATE(":hC#")`
  - `HOME_FIND(":hF#")`
  - 可选 `HOME_FIND_ABORT(":hO#")`
- 删除旧 RA/Dec home 快照相关字段和 `PREF_HOME_*`。
- 删除或重写:
  - `setHomeFromMount`
  - `captureHomeFromMount`
  - `gotoHome`
  - `currentHomeRaHours`
  - `updateHomeViews`
- `Set home` 使用 query 发送 `:hC#`,成功回包应为 `1`。
- `Return home` 使用 query 发送 `:hF#`,成功回包应为 `1`。
- 若 `gotoInProgress` 或 parked 状态不合适,先提示用户处理。

## 风险

- `:hF#` 可能以较高速率回家,必须提示用户确认电缆和镜筒不会碰撞。
- Home 与 Park 是不同概念,UI 文案要明确。
- 开机时如果赤道仪已经在 CWD,通常无需先 `Set home`;OnStep 会把开机姿态视为 home。
- AltAz 模式下 home 语义变为经纬仪轴 home,仍可使用但文案要避免写死 CWD。

## 验证

- Mock 对 `:hC#` / `:hF#` 回 `1`,App 显示成功。
- Mock 回 `0`,App 显示失败。
- 实机:开机在 CWD,直接 Return home 不应大幅移动。
- 实机:GOTO + 跟踪 30 分钟后 Return home,应回到机械 CWD,不受校准模型影响。

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

### B 增强版:All-Star + OnStep 两星模型

流程:

1. 选择第 1 颗亮星。
2. 用户手动移动赤道仪把星放到视野中心。
3. App 发送 `:Sr#` / `:Sd#` / `:CM#` 接受。
4. 选择第 2 颗几何分布合适的亮星。
5. 用户手动居中后再次接受。
6. App 保存 OnStep 两星模型 `:AW#`。
7. App 根据两颗星的目标坐标和同步前实际指向估计极轴方位/高度误差。
8. 用户只调机械方位角和高度角螺丝。

收益:

- 两星后可建立指向模型。
- 可开启双轴跟踪。
- 极轴误差由 App 独立估计,不依赖 OnStepX 扩展命令。

### 保留三星模式

三星校准继续作为完整指向模型路径。

三星极轴精调仍可使用 OnStep 的 `:MP#`,但只在赤道仪模式下启用。

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

- Solver 单元测试:合成已知极轴误差,反解误差小于 5 角秒。
- Mock:两星完成后可保存模型并启动双轴跟踪。
- 实机:两星校准后 GOTO 仍正常,极轴提示方向合理。

---

# 5. 星图与小天体数据

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

# 6. 构建与验证

## 常用命令

```powershell
cd D:\Android_projects\controller
.\scripts\doctor.ps1
.\scripts\build-debug.ps1
```

Debug APK 输出:

```text
D:\Android_projects\controller\app\build\outputs\apk\debug\app-debug.apk
```

## Mock 验证

`scripts/mock-onstep.ps1` 应覆盖:

- 连接握手。
- 移动 / 停止。
- GOTO。
- 两星 / 三星校准。
- `:hC#` / `:hF#`。
- OnStepX 架台类型查询和切换。
- 故障回包和超时。

## 实机验证顺序

1. 连接和停止。
2. 手动移动八方向。
3. GOTO 单目标。
4. 两星校准。
5. 三星校准。
6. 跟踪启动/停止。
7. Return home。
8. 小天体下载与星图显示。

---

# 资料来源

- OnStep GitHub README:https://github.com/hjd1964/OnStep
- OnStep release-4.24 `Command.ino`:校准和 LX200 命令参考。
- OnStepX `Mount.command.cpp` / `Telescope.command.cpp`:架台类型和固件能力命令参考。
- Android USB Host docs:https://developer.android.com/develop/connectivity/usb/host
- Android WiFi permissions:https://developer.android.com/guide/topics/connectivity/wifi-permissions
- JPL SBDB / Horizons:https://ssd.jpl.nasa.gov/
- Yale Bright Star Catalog:https://tdc-www.harvard.edu/catalogs/bsc5.html
- HYG Database:https://astronexus.com/projects/hyg
- Stellarium skycultures:https://github.com/Stellarium/stellarium-skycultures
