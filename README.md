# ClearskyGoto

<p align="center">
  <img src="docs/images/clearsky_wordmark.png" alt="ClearskyGoto" width="180">
</p>

ClearskyGoto 是针对晴空谐波赤道仪使用流程开发的 Android 控制 App。它面向现场目视和轻量拍摄场景，提供 WiFi 连接、星图 GOTO、方向控制、支架校准、跟踪控制、观测地/时间同步、太阳系天体星历、小天体星历和命令日志导出。

本项目是在 MountBehave 代码基础上继续开发而来；当前文档、界面和功能说明均以 ClearskyGoto 与晴空谐波赤道仪为主体。

> 这不是 OnStep 官方 App。控制真实赤道仪时，请始终保留实体断电、控制器急停或其他独立安全手段。

## 当前状态

- 当前分支：`main`
- 当前版本：`0.0.1`
- 目标设备：晴空谐波赤道仪系列，当前实机验证以晴空 ST17 为主
- 控制协议：OnStep / OnStepX 兼容命令，默认 TCP `192.168.0.1:9999`
- 推荐用途：目视手控、星图选星、GOTO、两星/三星校准、现场日志反馈
- 不推荐用途：无人值守自动化、无人看护长时间跟踪、未确认机械限位的高速跨天区 GOTO

## 功能概览

| 模块 | 说明 |
| --- | --- |
| 设置 | 选择 OnStep / OnStepX，OnStepX 下可选择赤道仪或经纬仪模式；包含状态、安全、太阳系天体星历、语言和命令日志 |
| 连接/同步 | 连接晴空赤道仪 WiFi 控制端口；同步观测地、日期、时间；选择跟踪速率并启动跟踪 |
| 星图 | 离线星图、GOTO、同步、支架校准和半透明方向控制盘集中在一个页面 |
| 手控 | 星图页面内的四向控制盘支持显示/隐藏、拖动位置、移动速率选择和全局停止 |
| 校准 | 支持两星/三星校准；三星模型后可进行极轴精调；经纬仪模式隐藏极轴相关流程 |
| GOTO | 支持星图点选、目标搜索、RA/Dec 输入、GOTO 状态轮询、取消 GOTO 和到位复核 |
| 跟踪 | 恒星速、月球速、太阳速；经纬仪模式使用双轴跟踪语义 |
| 星历 | 内置 2025-01-01 至 2050-01-01 的 JPL DE440s 主要太阳系天体抽样表，超出范围自动回退解析模型 |
| 小天体 | 内置常见小行星/彗星基线，可按名称添加小行星或彗星 |
| 日志 | 记录 TX/RX、用户操作、状态快照、校准诊断和异常；支持导出与分享 |
| 语言 | 支持中文和英文 UI，默认中文 |

## 安装与构建

### 本地构建

```powershell
cd D:\Android_projects\controller
powershell -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

输出位置：

```text
app\build\outputs\apk\debug\app-debug.apk
```

Release 构建：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-release.ps1
```

### 安装到设备

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

真实连接测试建议使用 Android 手机或平板，并让设备直接连接赤道仪 WiFi。电脑模拟器适合检查界面和离线星图，不适合验证真实 WiFi 热点连接。

### 电脑端预览

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\preview-app.ps1
```

## 基本使用流程

1. 打开 App，进入“设置”，确认固件类型和支架类型。
2. 进入“连接/同步”，连接晴空赤道仪 WiFi 控制端口。
3. 使用 GPS 或手动输入观测地，点击同步时间/地点。
4. 进入“星图”，拖拽或搜索目标。
5. 连接后点击 GOTO，等待状态显示到位或空闲。
6. 如果目标在目镜中有偏差，用方向控制盘居中后点击同步。
7. 需要建立指向模型时，展开星图里的支架校准面板，执行两星或三星校准。
8. 出现异常时，先停止赤道仪，再从设置页导出日志反馈。

## 支架校准

校准入口在星图页面。点击星图右侧的校准图标后，星图下方会展开校准面板；再次点击图标会收起面板。收起面板不会取消正在进行的校准流程。

两星/三星校准流程：

1. 选择“两星校准”或“三星校准”。
2. 点击“开始校准”。
3. 设置当前校准目标，可以输入名称/坐标、推荐亮星，或在星图中选择。
4. 用方向控制盘把目标居中。
5. 点击“居中后同步”接受当前星。
6. 对后续校准星重复设置目标和居中同步。
7. 最后一颗星接受后，App 自动请求保存模型。

极轴精调只在赤道仪模式下显示，并要求先完成三星或更多星的模型。两星模型用于 GOTO / 跟踪补偿，不解锁 Refine PA。

## 安全注意事项

- “停止”悬浮按钮始终可见，但不能替代实体断电或控制器急停。
- Park 状态下会禁用 GOTO、同步、跟踪和方向控制等常规动作。
- 切换 OnStepX 支架类型后，App 会强制断开连接，要求用户重启控制器后再继续。
- 如果 App 提示限位、硬件错误或需要手控恢复，请先低速移出危险位置，再继续测试。
- 不要在不了解机械限位、线缆路径和镜筒转动范围的情况下反复高速 GOTO。

## 星图与星历数据

主要星图数据包括：

- HYG Database 恒星子集
- OpenNGC 深空天体子集
- d3-celestial 星座连线
- NASA SVS Deep Star Maps 2020 银河背景
- JPL DE440s 主要太阳系天体抽样表，覆盖 2025-01-01 至 2050-01-01
- JPL SBDB 小天体元素，内置基线并支持用户按需添加

详细来源、授权和再生成方法见 [docs/catalog-data.md](docs/catalog-data.md)。

## 测试指南

实机测试人员请阅读 [docs/tester-guide.md](docs/tester-guide.md)。测试反馈请尽量包含：

- App 版本
- 手机/平板型号和 Android 版本
- 赤道仪型号、固件类型和支架模式
- 连接方式、IP 和端口
- 操作步骤、目标名称和发生时间
- App 显示状态与赤道仪实际动作
- 导出的日志文件

## 开发信息

常用命令：

```powershell
.\scripts\doctor.ps1
.\scripts\build-debug.ps1
.\scripts\preview-app.ps1
```

无赤道仪时可启动本地 mock：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\mock-onstep.ps1
```

Android 模拟器连接地址：

```text
10.0.2.2:9999
```

项目实现、调试、文档和发布准备主要由 OpenAI Codex 与 Anthropic Claude Code 辅助完成，最终验证和真实设备安全判断仍由维护者负责。
