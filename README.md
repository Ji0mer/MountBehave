# MountBehave

<p align="center">
  <img src="docs/images/mountbehave_icon.png" alt="MountBehave app icon" width="150">
</p>

<p align="center">
  <strong>面向 OnStep 赤道仪的 Android 目视手控器</strong>
</p>

<p align="center">
  <a href="#当前状态"><img alt="Status" src="https://img.shields.io/badge/status-field--testing-orange"></a>
  <a href="#版本说明"><img alt="Version" src="https://img.shields.io/badge/version-v0.1.0-blue"></a>
  <a href="#功能概览"><img alt="Platform" src="https://img.shields.io/badge/platform-Android-green"></a>
  <a href="#星图数据与授权"><img alt="Catalog" src="https://img.shields.io/badge/catalog-HYG%20%7C%20OpenNGC%20%7C%20d3--celestial-lightgrey"></a>
</p>

MountBehave 是一个为 OnStep/LX200 兼容赤道仪开发的 Android 手控器。它面向手机和平板上的目视观测流程，提供 WiFi 连接、方向键移动、停止/GOTO、离线星图、观测地与时间同步、跟踪控制、两星/三星校准和三星极轴精调入口。

这个项目目前不是 OnStep 官方 App，也还没有经过充分的跨设备测试。真实赤道仪测试时请始终保留实体断电、控制盒急停或其他独立安全手段。

## 目录

- [当前状态](#当前状态)
- [功能概览](#功能概览)
- [安装与构建](#安装与构建)
- [使用教程](#使用教程)
- [校准流程](#校准流程)
- [已知问题](#已知问题)
- [星图数据与授权](#星图数据与授权)
- [开发信息](#开发信息)
- [版本说明](#版本说明)

## 当前状态

<p align="center">
  <img src="docs/images/clearsky_wordmark.png" alt="Clearsky ST17 test badge" width="160">
</p>

当前版本：`v0.1.0`

测试状态：

- 已在晴空谐波赤道仪 `ST17` 上完成 WiFi 连接、手动移动/停止、两星校准、GOTO 目标查找等实机验证。
- 三星校准流程已经针对 OnStep 架台侧限制做过修复，但仍建议继续夜间实测。
- 其他 OnStep 固件版本、其他品牌控制器、USB-C 有线连接、Park/Unpark、三星极轴精调和长时间跟踪仍需要更多实机验证。

适合当前尝试的场景：

- 手机直连赤道仪 WiFi 热点进行目视手控。
- 使用离线星图找目标、发起 GOTO、低倍目视寻星。
- 通过两星/三星校准改善赤道仪 GOTO 指向。

暂不建议把当前版本用于无人值守或高风险自动化控制。

## 功能概览

| 模块 | 当前能力 |
| --- | --- |
| 连接 | WiFi TCP 连接 OnStep 命令端口，默认 `192.168.0.1:9999` |
| 手控 | 八方向移动、松手停止、速度下拉选择、全局急停 |
| 星图 | 离线恒星、深空天体、星座连线、太阳系天体、近似银河带 |
| 星图交互 | 拖拽、双指缩放，最窄视场约 `1°`，恒星显示到约 `12` 等 |
| GOTO | 星图点选、目标名称搜索、RA/Dec 坐标输入，连接后发送 `:Sr...#`、`:Sd...#`、`:MS#` |
| 观测地与时间 | 默认 Boston；支持 GPS 或手动经纬度；可同步到 OnStep |
| 跟踪 | 恒星速、月球速、太阳速；三星模型后请求双轴/模型补偿，否则默认单轴 |
| 校准 | 快速同步、两星校准、三星校准、三星极轴精调入口 |
| 安全 | GOTO 状态查询、取消 GOTO、Park/Unpark、夜视模式、低空/过中天提醒 |
| 适配 | 悬浮菜单、横屏和平板宽屏布局、MountBehave 启动图标 |

## 安装与构建

### 下载 APK

推荐从 GitHub Release 下载 `MountBehave-v0.1.0.apk`。

### 本地构建

```powershell
cd D:\Android_projects\controller
.\scripts\build-debug.ps1
```

默认输出：

```text
app\build\outputs\apk\debug\app-debug.apk
```

本次发布用的重命名 APK：

```text
dist\MountBehave-v0.1.0.apk
```

### 安装到手机

```powershell
.\scripts\env.ps1
adb devices
adb install -r dist\MountBehave-v0.1.0.apk
```

电脑模拟器通常不能直接加入赤道仪自己的 WiFi 热点。真实测试时建议把 APK 安装到手机或 Android 平板上，并让设备直接连接赤道仪 WiFi。

### 电脑端预览

```powershell
.\scripts\preview-app.ps1
```

预览适合检查界面、星图和离线流程，不适合测试真实赤道仪 WiFi 热点连接。

## 使用教程

### 1. 连接赤道仪

1. 手机连接赤道仪 WiFi。
2. 打开 MountBehave，进入“设置”页。
3. 在“赤道仪连接”里确认 IP 和端口，默认是 `192.168.0.1:9999`。
4. 点击“连接”。
5. 如果连接失败，先确认赤道仪端口是否为 `9999`，再断开重连。

### 2. 同步观测地和时间

1. 在“观测地与时间”中使用 GPS，或手动输入经纬度。
2. 默认经纬度为 Boston。
3. 点击“同步观测地与时间到赤道仪”。
4. 夏令时和时区由 Android 系统规则处理。

### 3. 手动移动

1. 切换到“手控”页。
2. 选择移动速度。
3. 按住方向键移动，松开停止。
4. 中间红色“停止”和悬浮“急停”都会发送全停止命令。

### 4. 星图与 GOTO

1. 切换到“星图”页。
2. 单指拖拽改变视野方向，双指缩放视场。
3. 点击星图目标，或通过目标搜索输入名称/坐标。
4. 未连接时只能显示目标；连接后可以显示并 GOTO。
5. GOTO 后可刷新 GOTO 状态，也可以随时取消 GOTO。

## 校准流程

校准入口位于“手控”页上方的“赤道仪校准”区域。当前流程面向目视使用，不依赖相机解析。

### 快速同步

适合只想让当前区域的 GOTO 大致可用：

1. 选择“快速同步”。
2. 选择一颗亮星作为同步目标。
3. 用方向键把这颗星移动到目镜中心。
4. 点击“居中后同步”。

### 两星/三星校准

适合建立 OnStep 指向模型：

1. 选择“两星校准”或“三星校准”。
2. 点击“开始校准”。
3. 选择当前校准星。
4. 如果 App 提示架台侧不匹配，或目标跨过中天，先点击“2B. 设置架台侧 / GOTO”，等待赤道仪移动后再手动居中。
5. 用方向键把目标星居中。
6. 点击“手动居中后同步接受”。
7. 对剩余校准星重复以上步骤。
8. 完成后点击“保存模型”。

### 三星极轴精调

1. 先完成并保存三星模型。
2. 选择“三星极轴精调”。
3. GOTO 到精调星。
4. 执行 Refine PA。
5. 只调整赤道仪的方位角/高度角螺丝，把星移回目镜中心。
6. 不要在这一步使用方向键，否则极轴误差不会被物理修正。

## 已知问题

- 当前版本的 OnStep 校准流程只建议连续执行一次。完成两星或三星校准后，如果取消模型并立刻重新开始校准，校准流程可能卡住。
- 使用 `v0.1.0` 时请不要随意取消已开始/已完成的校准模型；如果确实需要重新校准，建议重启赤道仪后再从头开始。
- 三星校准遇到架台侧不匹配时，需要先使用“2B. 设置架台侧 / GOTO”，再手动居中并接受。
- 太阳系天体位置使用轻量低精度算法，适合星图参考和目视寻星，不适合高精度历书用途。

这些问题会在后续版本继续修复。

## 星图数据与授权

MountBehave 为控制 APK 体积使用筛选后的离线数据：

- 恒星：HYG Database v4.2，筛选到约 `12` 等。
- 深空天体：OpenNGC，筛选常见 Messier、NGC/IC 目标。
- 星座连线：d3-celestial 的 `constellations.lines.json` 转换而来。
- 太阳系天体：App 内置低精度算法动态计算。
- 银河带：程序绘制 J2000 银河坐标近似带，不是真实银河贴图。

授权说明：

- HYG Database 和 OpenNGC 使用 CC BY-SA 4.0。
- d3-celestial 使用 BSD 3-Clause。
- 公开仓库或发布 APK 时需要保留数据来源 attribution。

更详细说明见 [docs/catalog-data.md](docs/catalog-data.md)。

## 开发信息

项目内已配置便携工具链：

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Android Platform Tools
- Gradle Wrapper

常用命令：

```powershell
.\scripts\doctor.ps1
.\scripts\build-debug.ps1
.\scripts\preview-app.ps1
```

无赤道仪时可启动本地 Mock OnStep 服务：

```powershell
.\scripts\mock-onstep.ps1
```

然后在 Android 模拟器中连接：

```text
10.0.2.2:9999
```

## 版本说明

### v0.1.0

- 已完成基础可用版本：连接、手控、星图、GOTO、两星/三星校准、跟踪设置、观测地/时间同步。
- 修复 WiFi 连接稳定性，默认端口为 `9999`。
- 修复三星校准第一颗星因架台侧不匹配导致 OnStep 返回 `E6` 后清空校准状态的问题。
- 增加校准诊断日志，便于现场判断 OnStep 状态、架台侧、恒星时和目标指向差。
- 增加星图 12 等恒星、太阳系天体、银河带和约 `1°` 最窄视场。

完整更新记录见 [CHANGELOG.md](CHANGELOG.md)。
