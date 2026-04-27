# MountBehave

<p align="center">
  <img src="docs/images/mountbehave_icon.png" alt="MountBehave app icon" width="150">
</p>

<h3 align="center">面向 OnStep 赤道仪的 Android 手控器</h3>

<p align="center">
  <a href="#当前状态"><img alt="Status" src="https://img.shields.io/badge/status-development-orange"></a>
  <a href="#功能概览"><img alt="Platform" src="https://img.shields.io/badge/platform-Android-green"></a>
  <a href="#星图数据与授权"><img alt="Catalog data" src="https://img.shields.io/badge/catalog-HYG%20%7C%20OpenNGC%20%7C%20d3--celestial-blue"></a>
</p>

MountBehave 是一个为 OnStep 兼容赤道仪开发的 Android 手控器项目，目标是在手机或平板上完成 WiFi 连接、手动移动、离线星图、目标 GOTO、观测地与时间同步、跟踪控制和目视校准辅助。

本项目目前偏向目视观测场景，尤其是谐波赤道仪、便携望远镜和不想携带电脑的观测流程。它不是 OnStep 官方 App，也还没有完成充分实机验证。

## 当前状态

<p align="center">
  <img src="docs/images/clearsky_wordmark.png" alt="Clearsky ST17 test badge" width="180">
</p>

> 测试状态：目前大部分功能仍处于开发验证阶段，尚未经过完整实机测试。到目前为止，只使用晴空谐波赤道仪 ST17 完成过 WiFi 连接和手动移动/停止测试；GOTO、两星/三星校准、跟踪、Park/Unpark、限位/过中天提醒和三星极轴精调仍需要继续夜间实机验证。

适合现在尝试的内容：

- 连接 OnStep WiFi 命令端口
- 手动移动和停止
- 离线星图浏览、拖拽和缩放
- 目标搜索与离线显示
- 在可控环境中测试 GOTO、校准和跟踪流程

暂时不建议把它作为无人值守或高风险设备控制工具使用。真实赤道仪上测试时，请保持手边有实体电源开关或独立急停手段。

## 目录

- [当前状态](#当前状态)
- [功能概览](#功能概览)
- [快速开始](#快速开始)
- [使用流程](#使用流程)
- [校准流程](#校准流程)
- [开发与构建](#开发与构建)
- [星图数据与授权](#星图数据与授权)
- [更新说明](#更新说明)
- [项目文档](#项目文档)

## 功能概览

| 模块 | 当前能力 |
| --- | --- |
| 连接 | WiFi TCP 连接 OnStep 命令端口，默认 `192.168.0.1:9999`，使用 `:GVP#` 握手 |
| 手控 | 八方向移动、松手停止、速度下拉选择、全局急停 |
| 星图 | 离线恒星、深空天体、星座连线、太阳系天体、近似银河带 |
| GOTO | 星图点选、目标搜索、坐标输入、连接后发送 `:Sr...#`、`:Sd...#`、`:MS#` |
| 观测地与时间 | 默认 Boston，经纬度手动输入或 GPS 获取，支持同步到 OnStep |
| 跟踪 | 恒星速、月球速、太阳速；三星校准后请求模型补偿双轴，否则默认单轴 |
| 校准 | 快速同步、两星校准、三星校准、三星极轴精调提示流程 |
| 安全 | GOTO 状态查询、取消 GOTO、Park/Unpark、夜视红色界面、低空/过中天提醒 |
| 适配 | 悬浮菜单、横屏和平板宽屏布局、MountBehave 启动器图标 |

## 快速开始

### 构建 APK

```powershell
cd D:\Android_projects\controller
.\scripts\build-debug.ps1
```

调试 APK 输出位置：

```text
app\build\outputs\apk\debug\app-debug.apk
```

### 安装到手机

```powershell
.\scripts\env.ps1
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

电脑模拟器通常不能直接扫描或加入赤道仪自己的 WiFi 热点。真实观测时建议把 APK 安装到手机或平板上，并让手机直接连接赤道仪 WiFi。

### 电脑端预览

```powershell
.\scripts\preview-app.ps1
```

预览脚本会启动本地 Android 模拟器并安装最新 debug APK。模拟器适合看界面、星图和离线流程，不适合测试赤道仪热点连接。

## 使用流程

### 1. 连接赤道仪

1. 让手机连接到赤道仪 WiFi。
2. 打开 MountBehave，默认进入“设置”页。
3. 在“赤道仪连接”中确认 IP 和端口，默认是 `192.168.0.1:9999`。
4. 点击“连接”。连接成功后会显示 OnStep 握手信息或“无握手返回”提示。
5. 如果连接异常，先确认赤道仪实际端口是否为 `9999`，再尝试断开后重新连接。

### 2. 设置观测地与时间

1. 在“观测地与时间”中使用 GPS，或手动输入经纬度。
2. 默认经纬度为 Boston：`42.36010, -71.05890`。
3. 点击“同步观测地与时间到赤道仪”，把当前地点、时间和时区发送给 OnStep。
4. 夏令时和时区由 Android 系统规则处理。

### 3. 手控移动

1. 从左上角菜单切换到“手控”页。
2. 选择移动速度：`1x` 适合精细居中，`8x/20x/48x` 适合找目标，半最高速和最高速适合大范围移动。
3. 按住方向键移动，松开即停止。
4. 中间红色“停止”和悬浮“急停”都会发送全停止命令。

### 4. 星图和 GOTO

1. 切换到“星图”页。
2. 单指拖动改变视野方向，双指缩放视场，最窄视场约 1°。
3. 点击星图目标，或点击“Goto 目标/查找目标”输入天体名称或 RA/Dec 坐标。
4. 未连接时只在星图显示目标；已连接时可以选择显示并 GOTO。
5. GOTO 后可用“刷新 GOTO 状态”查看赤道仪是否仍在移动，也可以随时“取消 GOTO”。
6. 星图会显示目标高度、方位，并在低空、地平线下或接近中天时给出提醒。

## 校准流程

校准入口在“手控”页上方的“赤道仪校准”区域。当前流程面向目视使用，不依赖相机解析。

### 快速同步

适合只想让当前区域的 GOTO 大致可用：

1. 选择“快速同步”。
2. 选择一颗亮星作为同步目标。
3. 用手控方向键把这颗星移动到目镜中心。
4. 点击“居中后同步”。

### 两星/三星校准

适合建立 OnStep 指向模型：

1. 选择“两星校准”或“三星校准”。
2. 点击“开始校准”。
3. 选择第 1 颗校准星。
4. 手动移动赤道仪，让目标星进入目镜中心。
5. 点击“同步接受校准星”。
6. 对剩余校准星重复选择、手动居中和接受。
7. 完成后点击“保存模型”。

当前实现已改为“用户先手动居中，再同步/接受”，可以兼容初始指向偏差非常大的情况。

### 三星极轴精调

1. 先完成并保存三星模型。
2. 选择“三星极轴精调”。
3. GOTO 到精调星。
4. 执行 Refine PA。
5. 只调赤道仪的方位角/高度角螺丝，把星移回目镜中心。
6. 不要用方向键完成这一步，否则极轴误差不会被物理修正。

## 开发与构建

本目录已经配置了项目内便携工具链，位于 `.toolchain/`：

- JDK 17
- Android SDK 命令行工具
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Android Platform Tools
- Gradle Wrapper，基于 Gradle 9.3.1 生成

常用命令：

```powershell
.\scripts\doctor.ps1
.\scripts\build-debug.ps1
.\scripts\preview-app.ps1
```

无赤道仪时可以启动本地模拟 OnStep 服务：

```powershell
.\scripts\mock-onstep.ps1
```

然后在 Android 模拟器中连接：

```text
10.0.2.2:9999
```

如果使用真机连接模拟服务，则把 IP 改成本电脑在局域网中的地址，端口保持 `9999`。

## 星图数据与授权

MountBehave 的星图为了控制 APK 体积，使用筛选后的离线数据和少量程序计算：

- 恒星数据来自 [HYG Database v4.2](https://astronexus.com/projects/hyg)，当前内置到约 12 等。
- 深空天体数据来自 [OpenNGC](https://github.com/mattiaverga/OpenNGC)，当前筛选常见 Messier/addendum 与较亮 NGC/IC 目标。
- 星座连线来自 [d3-celestial](https://github.com/ofrohn/d3-celestial) 的 `constellations.lines.json`。
- 太阳系天体使用 App 内置低精度算法动态计算，不写入大体积静态星历。
- 银河带使用 J2000 银河坐标变换程序绘制，只表示大致位置，不是真实银道贴图。

授权注意事项：

- HYG 与 OpenNGC 使用 CC BY-SA 4.0。
- d3-celestial 使用 BSD 3-Clause。
- 公开仓库或发布 APK 时，需要保留数据来源 attribution。
- 生成后的 catalog 数据资产应明确标注来源和许可证约束。
- 更详细说明见 [docs/catalog-data.md](docs/catalog-data.md)。

## 更新说明

- 星图支持约 1° 最窄视场，并可显示到约 12 等恒星。
- 星图新增太阳、月亮、主要行星和近似银河带。
- 校准流程已改为更适合大偏差目视初始状态的方式：先选择目标星，再由用户手动移动赤道仪把目标星居中，最后同步或接受当前位置。
- 快速同步不再先执行 GOTO，而是“设为同步目标 → 手动居中 → 居中后同步”。
- 两星/三星校准不再先执行 GOTO，而是“开始模型 → 选择校准星 → 手动居中 → 同步接受校准星”。
- 两星/三星接受校准星时使用 OnStep Align 模式下的同步命令 `:CM#`，避免纯手动居中时误用依赖 GOTO 目标的接受命令。
- 三星极轴精调流程调整为：完成并保存三星模型后，先 GOTO 精调星，再执行 Refine PA，随后只调赤道仪方位角/高度角把星移回中心。

## 项目文档

- [实施计划](plan.md)
- [星图数据来源与授权](docs/catalog-data.md)
