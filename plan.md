# OnStep 谐波赤道仪 Android 手控器可行性与实施计划

生成日期：2026-04-24

## 结论

项目可行。OnStep 官方仓库说明它支持 USB、Bluetooth、ESP8266 WiFi 等串行命令通道，并兼容 LX200 协议；这意味着 Android 端可以把“连接层”抽象成 TCP Socket 或 USB 串口，再发送同一套 `:...#` 命令。已有 OnStep Controller2 Android 应用也证明了手机/平板手控器路线成立。

最大风险不在“能不能控制电机”，而在三处工程细节：

1. USB-C 有线连接需要手机支持 USB Host/OTG，并需要兼容控制器实际使用的 USB 串口芯片，例如 CDC ACM、CH340、CP210x 或 FTDI。
2. 星图需要处理数据授权、离线体积、渲染性能和行星星历精度，不能只做静态图片。
3. “1/2/3 星对极轴”要在建立指向模型后，把模型误差转化成用户可执行的调节提示，明确告诉用户如何调整赤道仪的方位角和高度角。

## 功能可行性

### 1. 赤道仪连接

首选实现顺序：

1. WiFi TCP：用户输入 IP 和端口，默认提供 OnStep 生态常用端口预设，并允许自定义。连接成功后发送 `:GVP#` 或状态查询命令做握手。
2. USB-C 有线：使用 Android USB Host API 枚举 USB 设备、申请权限、打开端点；具体串口协议建议接入成熟 USB serial 库，覆盖 CDC/CH340/CP210x/FTDI。
3. 蓝牙串口：用户原文写的是 “wii”，我按 WiFi 理解；如果实际是 Wii 手柄或 Bluetooth，则可以作为第二阶段加入。

Android 13+ 如果只连接用户已经加入的 OnStep WiFi 热点并打开 TCP Socket，通常主要需要 `INTERNET`。如果 App 自己扫描/发现附近 WiFi 或 WiFi Direct，则需要处理 `NEARBY_WIFI_DEVICES` 运行时权限。

### 2. 上下左右移动和停止

OnStep/LX200 命令层可直接支持：

- 北/南/东/西连续移动：`:Mn#`、`:Ms#`、`:Me#`、`:Mw#`
- 停止：`:Q#`，后续再实测按方向停止命令
- 速度档：先支持 Guide/Center/Find/Slew 档位，映射 OnStep/LX200 常见移动速率命令
- 安全策略：按键按下发送移动，松开立即停止；连接中断、App 进入后台、屏幕锁定时强制发送停止并清空本地移动状态

第一版必须做一个 Mock OnStep 服务器/串口模拟器，用来验证按钮不会遗漏 stop 命令。

### 3. 内置星图

建议离线优先，在线更新作为可选：

- 恒星：第一版用 Yale Bright Star Catalog 或 HYG 的亮星子集，覆盖肉眼和寻星镜常用星。HYG v4.2 是 CC BY-SA 4.0，需要在 App 内做 attribution，并评估 ShareAlike 对发布形态的影响。
- 星座连线：可参考 Stellarium skycultures 的 western 数据格式；但不同 sky culture 的许可证不同，必须逐项确认。若要规避授权复杂度，可以自建一份仅含 HIP 编号连线的最小西方星座线表。
- 深空天体：第一版放 Messier、Caldwell、常见 NGC/IC 子集，字段至少包含名称、RA/Dec、类型、星等、角大小、别名。
- 行星/月亮/太阳：第一版采用离线低精度算法或预置短期星历；需要高精度时接 JPL Horizons API 做数据生成或更新，但夜间野外使用不能依赖网络。
- 渲染：先用 Android Canvas/OpenGL 自绘，模型层统一 J2000 RA/Dec，显示层按观测时间和地理位置转换为 Alt/Az 或赤道坐标投影。

### 4. 1/2/3 星人工校准/极轴流程

OnStep `Command.ino` 注释给出的人工校准序列可作为主流程：

1. 用户把赤道仪放到极轴 home/CWD 起点。
2. App 设置时间、日期、时区、经纬度。
3. App 发送 `:A1#`、`:A2#` 或 `:A3#` 开始校准。
4. App 从亮星列表中筛选高度合适、避开极区、分布合理的校准星。
5. 对每颗星设置目标 RA/Dec，发送 GOTO。
6. 用户用方向键把星居中。
7. App 发送 `:A+#` 接受该星。
8. 多星流程重复，结束后提示保存模型，例如 `:AW#`。

如果需求确实是“物理对极轴”而不是“星点校准”，第二阶段增加：

- 根据 2/3 星校准残差估计极轴高度/方位误差。
- 给出“调节高度螺丝/方位螺丝”的图形提示。
- 调节期间区分“用手控器移动望远镜”和“机械调极轴”，避免把两者混在一个步骤里。

## 推荐架构

模块边界：

- `transport`：WiFi TCP、USB serial、Bluetooth serial，共用 `send(command): reply`。
- `onstep-protocol`：LX200/OnStep 命令封装、回复解析、错误码、超时重试。
- `mount-control`：方向键、速率、停车/归位、状态机和安全停止。
- `catalog`：恒星、星座线、DSO、行星星历数据导入和查询。
- `sky-renderer`：坐标转换、投影、缩放、选择目标、星图图层。
- `alignment`：1/2/3 星流程、候选星筛选、步骤引导、校准状态恢复。
- `app-ui`：连接页、手控页、星图页、校准向导、设置页。

技术选型：

- Android 原生项目，JDK 17，Android Gradle Plugin 9.1，compileSdk 36。
- UI 第一版可以用原生 View/Canvas 快速验证；正式版建议 Kotlin + Compose + 自绘 Canvas 或 OpenGL 层。
- 数据文件使用 JSON/SQLite。星表超过几万条后优先 SQLite + 空间索引或分区文件。
- USB 串口优先使用成熟库，避免自己从零适配每种芯片。

## 里程碑

### M0 环境与原型壳

- 完成本目录 Android 工具链配置。
- 生成可构建的最小 Android App。
- 加入 Mock OnStep 连接器和协议单元测试。

### M1 连接与手动移动

- WiFi TCP 连接、断线重连、握手。
- 方向键移动/停止、速率切换、紧急停止。
- USB-C 串口连接原型。

### M2 OnStep 状态与安全

- 查询版本、时间、经纬度、RA/Dec、跟踪状态。
- 设置观测地、时间、时区。
- App 后台/断线/异常时安全停止。

### M3 星图 MVP

- 离线亮星、星座线、Messier/常见 DSO。
- 当前天空投影、缩放、平移、目标搜索。
- 从星图选择目标并发送 GOTO。

### M4 1/2/3 星人工校准

- 校准星推荐。
- GOTO 到校准星、手动居中、接受校准点。
- 校准进度恢复、失败重试、保存模型。

### M5 极轴辅助

- 基于多星残差估计极轴误差。
- 机械调节引导界面。
- 实测不同 OnStep 固件版本的行为差异。

## 当前环境配置

已在 `D:\Android_projects\controller` 下配置项目内便携工具链：

- JDK：`.toolchain\jdk\jdk-17.0.18+8`
- Android SDK：`.toolchain\android-sdk`
- Android platform：`platforms;android-36`
- Android build-tools：`36.0.0`
- Android platform-tools：已安装，包含 `adb.exe`
- Gradle：`.toolchain\gradle\gradle-9.3.1`，并生成 Gradle wrapper

常用命令：

```powershell
cd D:\Android_projects\controller
.\scripts\doctor.ps1
.\scripts\build-debug.ps1
```

## 资料来源

- [OnStep GitHub README](https://github.com/hjd1964/OnStep)：连接方式、LX200 兼容性、Android 生态说明。
- [OnStep Command.ino](https://raw.githubusercontent.com/hjd1964/OnStep/release-4.24/Command.ino)：`A` 类校准命令和人工校准流程注释。
- [OnStep Controller2 on Google Play](https://play.google.com/store/apps/details?id=com.onstepcontroller2)：现有 Android 手控器功能边界参考。
- [Android USB Host overview](https://developer.android.com/develop/connectivity/usb/host)：Android USB Host 枚举、权限和端点通信。
- [Android WiFi permissions](https://developer.android.com/guide/topics/connectivity/wifi-permissions)：Android 13+ 附近 WiFi 设备权限。
- [Android sdkmanager docs](https://developer.android.com/tools/sdkmanager)：命令行 SDK 安装和目录结构。
- [Android Gradle Plugin 9.1 release notes](https://developer.android.com/build/releases/gradle-plugin)：AGP、Gradle、JDK、Build Tools 兼容矩阵。
- [JPL Horizons](https://ssd.jpl.nasa.gov/planets/orbits.html)：太阳系天体星历来源。
- [Yale Bright Star Catalog](https://tdc-www.harvard.edu/catalogs/bsc5.html)：亮星数据来源候选。
- [HYG Database](https://astronexus.com/projects/hyg)：亮星/近星综合数据来源和许可证。
- [Stellarium skycultures](https://github.com/Stellarium/stellarium-skycultures)：星座连线数据格式和授权注意事项。
