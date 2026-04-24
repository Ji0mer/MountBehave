# OnStep Android 手控器

这是一个面向 OnStep 赤道仪的 Android 手控器项目，目标是在手机或平板上完成赤道仪连接、手动移动、离线星图、目标 GOTO、观测地/时间同步和校准辅助。

## 本地环境

本目录已经配置了项目内便携工具链，位于 `.toolchain/`：

- JDK 17
- Android SDK 命令行工具
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Android Platform Tools
- Gradle Wrapper，基于 Gradle 9.3.1 生成

在本目录打开 PowerShell 后可运行：

```powershell
.\scripts\doctor.ps1
.\scripts\build-debug.ps1
```

调试 APK 输出位置：

```text
app\build\outputs\apk\debug\app-debug.apk
```

安装到已连接的 Android 设备：

```powershell
.\scripts\env.ps1
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

在当前电脑上用本地 Android 模拟器预览：

```powershell
.\scripts\preview-app.ps1
```

预览脚本使用 `720x1280` 的紧凑模拟器显示，并以 65% 比例显示，方便普通桌面完整看到窗口。

## 当前功能

App 目前已经实现以下核心功能：

- WiFi TCP 连接 OnStep 命令端口
- `:GVP#` 连接握手
- 最慢、1/3 最高速、2/3 最高速、最高速四档移动速度
- 八方向手动移动：上、下、左、右、东北、西北、东南、西南
- 松开方向键、点击停止、断开连接、App 进入后台时安全停止
- 离线星图，包含恒星、深空天体和星座连线
- 星图拖动、缩放、目标点选和目标搜索
- 未连接时显示目标，连接后可发送 GOTO
- 默认波士顿观测地，也支持手动输入和 GPS 获取
- 当前时间、时区和夏令时由系统规则处理
- 观测地与时间同步到 OnStep
- 侧边菜单切换设置、校准、手控和星图页面
- 快速同步校准星
- 一星、两星、三星 OnStep 校准流程
- 保存 OnStep 指向模型
- 请求模型补偿双轴跟踪
- Refine PA 极轴精调提示流程

## 无赤道仪测试

如果手边没有真实 OnStep 赤道仪，可以启动本地模拟服务：

```powershell
.\scripts\mock-onstep.ps1
```

然后在 Android 模拟器中连接：

```text
10.0.2.2:9999
```

如果使用真机连接，则把 IP 改成本电脑在局域网中的地址，端口保持 `9999`。

## 文档

- 实施计划：`plan.md`
- 星图数据来源与授权：`docs\catalog-data.md`

