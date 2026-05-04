# MountBehave

<p align="center">
  <img src="docs/images/mountbehave_icon.png" alt="MountBehave app icon" width="150">
</p>

<p align="center">
  <strong>面向 OnStep 赤道仪的 Android 目视手控器</strong>
</p>

<p align="center">
  <a href="#当前状态"><img alt="Status" src="https://img.shields.io/badge/status-field--testing-orange"></a>
  <a href="#版本说明"><img alt="Version" src="https://img.shields.io/badge/version-v0.2.5-blue"></a>
  <a href="#功能概览"><img alt="Platform" src="https://img.shields.io/badge/platform-Android-green"></a>
  <a href="#星图数据与授权"><img alt="Catalog" src="https://img.shields.io/badge/catalog-HYG%20%7C%20OpenNGC%20%7C%20NASA%20SVS-lightgrey"></a>
</p>

MountBehave 是一个为 OnStep/LX200 兼容赤道仪开发的 Android 手控器。它面向手机和平板上的目视观测流程，提供 WiFi 连接、方向键移动、停止/GOTO、离线星图、星图同步、观测地与时间同步、跟踪控制、两星/三星校准和三星后极轴精调入口。`v0.2.x` 系列加入 OnStepX 与经纬仪模式适配。

这个项目目前不是 OnStep 官方 App，也还没有经过充分的跨设备测试。真实赤道仪测试时请始终保留实体断电、控制盒急停或其他独立安全手段。

## 目录

- [当前状态](#当前状态)
- [功能概览](#功能概览)
- [安装与构建](#安装与构建)
- [测试人员教学](#测试人员教学)
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

当前版本：`v0.2.5`

测试状态：

- 已在晴空谐波赤道仪 `ST17` 上完成 WiFi 连接、手动移动/停止、两星校准、GOTO 目标查找等实机验证。
- 新增 OnStepX 固件选择、赤道仪/经纬仪模式切换、经纬仪校准流程和双轴跟踪语义；OnStepX 真机仍需要更多设备覆盖。
- 新增可导出的全应用日志，覆盖 TX/RX、用户动作、状态快照、校准诊断和小天体下载错误，方便现场排查。
- `v0.2.4` 增加星图同步与渐进式指向修正模型，修复同步时 OnStep `E8` 拒绝、手控移动时 WiFi 假断开等实机问题。
- `v0.2.5` 完成设置页控制台式 UI 优化：顶部设备状态卡、安全控制降噪、夜视开关强化辨识度，并把银河背景透明度调到更适合目视参考的中低强度。
- 其他 OnStep 固件版本、其他品牌控制器、USB-C 有线连接、Park/Unpark、三星后极轴精调和长时间跟踪仍需要更多实机验证。

适合当前尝试的场景：

- 手机直连赤道仪 WiFi 热点进行目视手控。
- 使用离线星图找目标、发起 GOTO、低倍目视寻星。
- 通过两星/三星校准改善赤道仪 GOTO 指向。

暂不建议把当前版本用于无人值守或高风险自动化控制。

## 功能概览

| 模块 | 当前能力 |
| --- | --- |
| 设置 | 顶部设备状态卡显示连接、流程、跟踪、GOTO、停放和安全状态；默认经典 OnStep，可切换 OnStepX 赤道仪 / 经纬仪语义 |
| 连接/同步 | WiFi TCP 连接 OnStep 命令端口，默认 `192.168.0.1:9999`；观测地、时间和跟踪控制集中在同一页 |
| 手控 | 八方向移动、松手停止、速度下拉选择、全局急停 |
| 星图 | 离线恒星、深空天体、星座连线、太阳系天体(VSOP87D 亚角秒精度)、NASA SVS 银河背景、可手动设置星图时间 |
| 小天体 | 内置 17 颗著名小行星 / 彗星基线;可在设置页“小天体星历”中按需同步亮小行星或逐颗添加彗星(JPL SBDB)。小行星橙色菱形,彗星带远日方向尾迹 |
| 图层切换 | 星图"图层"按钮 → 7 项独立开关(星座连线 / 太阳系 / 星团 / 星云 / 星系 / 小行星 / 彗星);恒星与银河背景固定常显 |
| 星图交互 | 单指拖拽、双指缩放，最窄视场约 `1°`，恒星显示到约 `12` 等；顶部状态区已压缩 |
| GOTO | 星图点选、目标名称搜索、RA/Dec 坐标输入，连接后发送 `:Sr...#`、`:Sd...#`、`:MS#`；星图 GOTO 按钮在移动中变为“取消”；极区和机械停止场景有到位兜底，连续限位/硬件错误后会暂停新 GOTO 并要求手控恢复 |
| 星图同步 | GOTO 后手动居中并点击“同步”，App 发送 `:CM#`；保存两星/三星模型后会追加多点残差修正，后续 GOTO 前自动预修正目标坐标 |
| 观测地与时间 | 默认 Boston；支持 GPS 或手动经纬度；可同步到 OnStep |
| 跟踪 | 恒星速、月球速、太阳速；保存两星/三星模型后请求双轴/模型补偿，否则默认单轴 |
| 校准 | 两星校准、三星校准、三星后极轴精调入口；经纬仪模式隐藏极轴相关流程 |
| 安全 | 紧急停止、中止 GOTO、同步 GOTO 状态、停放/解除停放、夜视模式、低空/过中天提醒 |
| 日志 | 设置页底部内置命令日志，可勾选记录、保存到 `Download/MountBehave`、选择位置保存、分享或清空当天日志 |
| 适配 | 紧凑暗色控制台界面、轻量说明按钮、悬浮菜单、横屏和平板宽屏布局、旋转屏幕后保持连接、MountBehave 启动图标 |

## 安装与构建

### 下载 APK

推荐从 GitHub Release 下载 `MountBehave-v0.2.5.apk`：

<https://github.com/Ji0mer/MountBehave/releases/tag/v0.2.5>

APK 不放在仓库代码目录里。仓库里的 `dist/` 仅作为本地构建输出目录，发布包请以 GitHub Release 附件为准。

### 本地构建

```powershell
cd MountBehave
powershell -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

默认输出：

```text
app\build\outputs\apk\debug\app-debug.apk
```

Release 构建并生成可安装 APK：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-release.ps1
```

如果未传入正式 keystore，脚本会使用本机 Android debug keystore 签名，适合现场安装测试；正式发布请传入专用 release keystore。

本次发布用的安装 APK：

```text
dist\MountBehave-v0.2.5.apk
```

### 安装到手机

```powershell
powershell -ExecutionPolicy Bypass -NoProfile -Command ". .\scripts\env.ps1; adb devices; adb install -r dist\MountBehave-v0.2.5.apk"
```

电脑模拟器通常不能直接加入赤道仪自己的 WiFi 热点。真实测试时建议把 APK 安装到手机或 Android 平板上，并让设备直接连接赤道仪 WiFi。

### 电脑端预览

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\preview-app.ps1
```

预览适合检查界面、星图和离线流程，不适合测试真实赤道仪 WiFi 热点连接。

## 测试人员教学

给实机测试人员的完整使用教程见 [docs/tester-guide.md](docs/tester-guide.md)。建议新测试人员先按该文档完成安全检查、安装、连接、手控、GOTO、校准和日志导出。

## 使用教程

### 1. 连接赤道仪

1. 手机连接赤道仪 WiFi。
2. 打开 MountBehave，先在“设置”页确认固件为 OnStep 或 OnStepX。
3. 进入“连接/同步”页，在“支架连接”里确认 IP 和端口，默认是 `192.168.0.1:9999`。
4. 点击晴空角标连接按钮。
5. 如果连接失败，先确认赤道仪端口是否为 `9999`，再断开重连。

界面中的长说明默认折叠在标题右侧的小 `i` 按钮里。实际操作状态、连接状态、校准进度和命令日志仍然直接显示在页面上。命令日志位于“设置”页底部，可导出分享。

### 2. 同步观测地和时间

1. 在“观测地与时间”中使用 GPS，或手动输入经纬度。
2. 默认经纬度为 Boston。
3. 点击“同步时间/地点”。
4. 夏令时和时区由 Android 系统规则处理。

### 3. 手动移动

1. 切换到“手控”页。
2. 选择移动速度。
3. 按住方向键移动，松开停止。
4. 中间红色“停止”和悬浮“急停”都会发送全停止命令。

### 4. 星图与 GOTO

1. 切换到“星图”页。
2. 单指拖拽改变视野方向，双指缩放视场。
3. 需要检查其他时间的天区时，点击“时间”设置星图日期/时间，或点“现在”回到当前时刻。
4. 点击星图目标，或通过目标搜索输入名称/坐标。
5. 未连接时只能显示目标；连接后可以显示并 GOTO。GOTO 进行中同一个按钮会变为“取消”。
6. GOTO 到位后，如果目镜中仍有小偏差，用手控把目标居中，然后点击“同步”。App 会发送 `:CM#`；保存过两星/三星模型后，App 会把同步后的残差加入多点修正模型，后续 GOTO 会自动参考这些点。
7. GOTO、校准、同步等会移动赤道仪的操作会自动把星图时间恢复为现在，避免把历史/未来时刻的行星或月亮位置发给赤道仪。
8. 低空目标只做遮挡/限位提示，不会被 App 硬性阻止。
9. GOTO 后 App 会自动轮询状态；控制器报告空闲时还会读取当前 RA/Dec，确认接近目标后才释放为可发起下一次 GOTO。极区目标和机械限位/停止场景会用更宽的极区阈值和静止检测兜底；如果停住但离目标很远，日志会记录 OnStep `:GE#` 与架台侧诊断。连续两次出现限位/硬件类错误且离目标仍很远时，App 会暂停新的 GOTO，提示先用手控把赤道仪移出危险位置。也可以手动刷新或随时取消 GOTO。

### 5. 安全控制与 GOTO 状态

“设置”页顶部先显示“设备状态”卡片；“安全控制”区域保留紧急停止、夜视模式、中止 GOTO、同步 GOTO 状态、停放赤道仪和解除停放。停放/解除停放只显示当前合理操作，避免两个相反动作同时抢注意力。

自 `v0.2.2` 起已移除 Set Home / Return Home。OnStep 的 Home 是机械轴参考，不同固件和安装姿态下语义差异较大；在当前实机测试里它会引入误操作风险，因此不再从 App 暴露 Home 标记或返回入口。

## 校准流程

校准入口位于“手控”页上方的“架台校准”区域。当前流程面向目视使用，不依赖相机解析。OnStepX 经纬仪模式不需要对极轴，App 会隐藏极轴精调流程。

### 两星/三星校准

适合建立 OnStep 指向模型：

默认模式为“两星校准”。如果需要三星模型或后续极轴精调，请先切换到“三星校准”。

1. 选择“两星校准”或“三星校准”。
2. 点击“开始校准”。
3. 点击“设置目标”选择当前校准星。
4. 用方向键把目标星居中。
5. 点击“居中后接受”。
6. 如果第一颗星需要赤道仪先转到另一侧，App 会弹窗提示；确认空间安全后点“开始转动”，等待 GOTO 接近目标，再用方向键精确居中并再次点击“居中后接受”。
7. 对剩余校准星重复以上步骤。
8. 接受最后一颗星后 App 会自动请求保存模型。

如果接受后 App 检测到该星仍偏离目标超过 `1°`，会提示这颗星可能没有完全居中，但不会阻止继续校准或保存模型。若现场判断这颗星确实没居中，可点击“结束”后重新开始本轮校准。

### 极轴精调

1. 先完成并保存三星模型；两星模型只用于 GOTO / 跟踪补偿，不解锁 Refine PA。
2. 选择“极轴精调”。
3. GOTO 到精调星。
4. 执行 Refine PA。
5. 只调整赤道仪的方位角/高度角螺丝，把星移回目镜中心。
6. 不要在这一步使用方向键，否则极轴误差不会被物理修正。

## 已知问题

- 完成两星/三星校准并保存模型后，本地 UI 已能直接重新开始下一次校准。**取消校准**走另一路径:app 端依次发送 `:Q#`(停止运动)+ `:A0#`(OnStep abort alignment,固件不识别时静默忽略),并清掉本地状态;启动新校准时入口也再补一次 `:Q#` + `:A0#` 作为防御性 reset。在多数固件下这就够了;极少数固件可能仍需手动重启赤道仪。
- 赤道仪模式下校准第一颗星遇到架台侧不匹配时,App 会在“居中后接受”前提示并自动执行必要的 GOTO；OnStepX 经纬仪模式不需要架台侧处理，也不会显示极轴精调。
- 行星位置精度已升级至亚角秒(VSOP87D);月亮 ~30-60";小行星/彗星基线 ~1′(可通过设置页"同步亮小行星 / 添加彗星"在线刷新更新元素)。
- 命令传输不做自动重试:若发送过程中遇到 WiFi 抖动断链,会显示一次失败;再点一次按钮即可重试(避免对 `:CM#` 等状态命令双发的风险)。

这些问题会在后续版本继续改善。

## 星图数据与授权

MountBehave 为控制 APK 体积使用筛选后的离线数据：

- 恒星：HYG Database v4.2，筛选到约 `12` 等。
- 深空天体：OpenNGC，筛选常见 Messier、NGC/IC 目标。
- 星座连线：d3-celestial 的 `constellations.lines.json` 转换而来。
- 行星 / 太阳：IMCCE VSOP87D 截断表(约 3700 项,光行时 + 光行差 + 章动)。视位置与 JPL Horizons 吻合到亚角秒。
- 月亮：扩展的 Schlyter + Meeus 第 47 章摄动项,精度 ~30-60"。
- 小行星 / 彗星基线:JPL SBDB 内嵌 17 颗著名天体的 Keplerian 元素。视位置链路与行星一致 + 黄道 J2000→date 岁差旋转。Ceres 与 JPL 吻合 0.6" / 8"。
  - 小行星: 1 谷神星 (Ceres)、2 智神星 (Pallas)、3 婚神星 (Juno)、4 灶神星 (Vesta)、6 韶神星 (Hebe)、7 虹神星 (Iris)、8 花神星 (Flora)、9 海女星 (Metis)、10 健神星 (Hygiea)、11 海妖星 (Parthenope)、15 和神星 (Eunomia)、433 爱神星 (Eros)。
  - 彗星: 1P 哈雷彗星 (1P/Halley)、2P 恩克彗星 (2P/Encke)、9P 坦普尔1号彗星 (9P/Tempel 1)、67P 丘留莫夫-格拉西缅科彗星 (67P/Churyumov-Gerasimenko)、109P 斯威夫特-塔特尔彗星 (109P/Swift-Tuttle)。
- 小行星 / 彗星扩展:用户在设置页按需调用 JPL SBDB Query API 在线同步或逐颗添加。
- 银河背景：NASA Scientific Visualization Studio `Deep Star Maps 2020` / SVS 4851 的 `milkyway_2020_4k_print.jpg`，天赤道坐标系，中心为 RA 0h / Dec 0°，RA 向左增加，已去除亮星；App 以中低透明度叠加，用于目视方位参考。来源：<https://svs.gsfc.nasa.gov/4851/>。

授权说明：

- HYG Database 和 OpenNGC 使用 CC BY-SA 4.0。
- d3-celestial 使用 BSD 3-Clause。
- VSOP87 数据由 IMCCE 公开,无单独许可限制(教育/业余天文)。
- JPL Horizons / SBDB 数据由 NASA/JPL Caltech 维护,业余天文应用可自由使用。
- NASA SVS 银河背景按 NASA Images and Media Usage Guidelines 使用：NASA 内容通常不受美国版权限制,可用于教育/信息用途；需要注明 NASA/Goddard Space Flight Center Scientific Visualization Studio,不能暗示 NASA 背书,NASA 标识/Logo 另受限制。若页面单独标记第三方版权,以该标记为准。指南：<https://www.nasa.gov/nasa-brand-center/images-and-media/>。
- 公开仓库或发布 APK 时需要保留所有上述数据来源 attribution。

更详细说明见 [docs/catalog-data.md](docs/catalog-data.md)。

## 开发信息

本项目的代码实现、调试分析、文档整理和发布准备主要由 OpenAI Codex 与 Anthropic Claude Code 辅助完成，并结合实机日志和人工测试反馈迭代。项目仍由维护者负责最终验证、发布和赤道仪实机安全判断。

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
powershell -ExecutionPolicy Bypass -File .\scripts\mock-onstep.ps1
```

然后在 Android 模拟器中连接：

```text
10.0.2.2:9999
```

## 版本说明

### v0.2.5

- 设置页重排为设备状态优先：顶部卡片集中显示连接、流程、跟踪、GOTO、停放和安全状态。
- 安全控制改为更克制的暗色控制台布局：紧急停止独立突出，夜视模式改为可识别的低亮 chip，普通按钮恢复细描边和更清楚的禁用态。
- 小天体星历与命令日志区域进一步降噪，日志固定在设置页底部。
- 星图银河背景透明度调整为 `85/255`，比原始低透明度更容易辨认，但不压过恒星和目标标记。
- Android 版本号升至 `versionName 0.2.5` / `versionCode 7`。

### v0.2.4

- 星图新增“同步”按钮：GOTO 到位后手控居中并同步当前位置，App 会发送 `:CM#`。
- 保存两星/三星模型后，星图同步会记录 `:CM#` 之后 OnStep 仍未吸收的残差，并用最多 30 个局部校正点在后续 GOTO 前渐进式修正目标坐标。
- 星图 GOTO 和取消合并为同一个按钮：移动中显示“取消”，到位或停止后恢复为“Goto 目标”。
- 修复星图同步太快发送 `:CM#` 导致 OnStep 返回 `E8` 的问题；同步前现在会等待 `:D#` 空闲且 RA/Dec 稳定。
- 修复手控移动/停止过程中部分 WiFi 抖动被误判为断开的情况，停止命令加入短重试和兜底全停止。
- 校准残差超过 `1°` 时只提示风险，不再强制用户重开本轮校准。
- Android 版本号升至 `versionName 0.2.4` / `versionCode 6`。

### v0.2.3

- 修复北天极附近 GOTO 到位后仍卡在“移动中”的问题：极区目标使用动态到位阈值，固件空闲且位置稳定时会释放本地 GOTO 状态。
- 修复 GOTO 轮询达到上限后本地状态不清理的问题，避免极端情况下后续 GOTO 被“正在移动”挡住。
- 连续两次 GOTO 停止但离目标仍超过 `2°`，且 OnStep 报告 `GE=20/21` 或 `E6/E7` 时，暂停新的 GOTO 并提示先手控恢复；低空/地平线下错误不触发这层保护。
- 命令日志导出新增“保存到下载目录”，写入 `Download/MountBehave` 并使用 `.txt` 文件名，便于华为文件管理、QQ 和电脑 MTP 识别。
- 星图加入 NASA SVS 银河背景图，替代简化银河轮廓；亮度略增，仍以低透明度显示。
- 星图“重置视图”改为“时间”弹窗，可设置星图时间或一键回到现在；会移动赤道仪的操作自动回到当前时刻。
- 压缩两星/三星校准 UI，默认进入两星校准；最后一颗星接受后自动保存模型；极轴精调说明与按钮关系更明确。
- 校准接受后会检查残差，超过 `1°` 时提示可能未居中，但仍继续后续校准和自动保存；WiFi 短暂断开后会尽量保留未完成的校准会话。
- 平板横竖屏旋转时不再销毁 Activity 和断开 OnStep 连接，只重建界面布局。
- Android 版本号升至 `versionName 0.2.3` / `versionCode 5`。

### v0.2.2

- 移除“安全与夜视”中的 Set Home / Return Home 入口，App 不再发送 OnStep Home 标记或返回命令。
- 修复连续 GOTO 状态判断：控制器提前报告空闲时，App 会用当前 RA/Dec 和目标角距复核，未到位则继续保持 GOTO 进行中。
- 版本号升至 `0.2.2`，发布包使用 release 构建输出并签名为可安装 APK。

### v0.2.1

- 压缩星图顶部状态区，把目标、指向、GOTO 和限位信息改成更短的观测状态文本。
- 修复桌面预览/触控板场景下星图拖拽可能被误判为缩放的问题；单指拖拽和双指缩放分支更稳定。
- 设置页命令日志移到底部；主菜单展开时显示当前版本号；手控页“支架校准”布局进一步收紧。

### v0.2.0

- 新增设置页、连接/同步页和 OnStepX 赤道仪/经纬仪模式。
- 新增可导出命令日志、安全控制重排和暗色界面。
- 改进小天体下载、彗星逐颗添加、太阳系/小天体星图精度和三星后极轴精调流程。

### v0.1.0

- 已完成基础可用版本：连接、手控、星图、GOTO、两星/三星校准、跟踪设置、观测地/时间同步。
- 修复 WiFi 连接稳定性，默认端口为 `9999`。
- 修复三星校准第一颗星因架台侧不匹配导致 OnStep 返回 `E6` 后清空校准状态的问题。
- 增加校准诊断日志，便于现场判断 OnStep 状态、架台侧、恒星时和目标指向差。
- 增加星图 12 等恒星、太阳系天体、银河带和约 `1°` 最窄视场。

完整更新记录见 [CHANGELOG.md](CHANGELOG.md)。
