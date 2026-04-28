# Changelog

## 未发布(开发分支)

### Codex 审查后回滚 / 修正

- **回滚 H4(OnStepClient 自动重试)**:之前对所有命令无条件重试一次会让 `:CM#`、`:MS#`、`:A+#` 等状态修改命令在"已执行 + 回包丢失"时被双发(可能把同一颗星接受两次)。改回每个命令只发一次;ESP8266 抖动场景由用户手动重试处理。
- **修正 C4(ObserverState.boston 时区)**:`boston()` 恢复 `BOSTON_ZONE`(`America/New_York`)——Boston 这个**坐标本身**对应美东时区,与手机所在地无关。`withLocation()` 仍用 `ZoneId.systemDefault()`(手动输入坐标=用户在该地附近)。GPS 路径已正确。
- **代码层修复 C2(取消校准)**:不仅是文案。`OnStepCommand` 新增 `ALIGN_ABORT(":A0#")`(OnStep 公开但少被宣传的 abort alignment 命令,固件不识别时静默忽略);`cancelAlignmentSession` 同时入队 `:Q#`(停运动) + `:A0#`(abort align);`startAlignment` 入口在 `:Q#` 之后、观测地同步之前,也补一次 `:A0#` 作为防御性 reset,确保新的 `:A2#`/`:A3#` 起手干净。
- **修正:校准全程输入框保持空白由用户从星图选星**:之前两处自动填推荐星——`startAlignment` 的 onSuccess(开始时)和 `finishAcceptedAlignmentStar` 的下一颗分支(每接受一颗星之后)——都已删掉。`fillSuggestedCalibrationTarget()` 函数本身保留,仅作为"推荐亮星"按钮的显式 handler;用户主动点该按钮才会触发推荐。
- **修正:晴空配置文案**:`mount_profile_title` 从"晴空 ST17 测试配置"改回"晴空赤道仪配置";`mount_profile_body` 从"仅覆盖 ST17 的 WiFi/手控/停止"改为"理论上能够控制晴空系列全部赤道仪。仅在晴空 ST17 上进行过实机测试。"

### 修复

- C1:`saveAlignmentModel` onSuccess 清空 `alignmentSession` + `updateCalibrationViews()`,修复"完成校准后无法重新开始"的本地 UI 卡死。
- C2(部分):取消模型时同时发 `:Q#` 停止运动 + 清本地状态。**OnStep 端 alignment 状态机能否被自动清,取决于固件;无法保证**——多数固件在收到下一个 `:A2#`/`:A3#` 时会重置,但 README 已知问题保留"必要时重启赤道仪"提示。
- C3:`onDestroy` 在 `ioExecutor.shutdown()` 后用 `awaitTermination(2s)` 等待 STOP_ALL 真正发出,降低退出时赤道仪继续移动概率。
- C4:手动输入坐标路径改用 `ZoneId.systemDefault()`;在中国/欧洲/澳洲手动输入坐标后 LST 不再被卡在初始时区。
- H1:多点触摸 / 快速换向时,在 `startMove` 入口为旧方向先入队 `:Qe#`/`:Qw#` 等停止命令,避免双方向同时运动。
- H2:`acceptAlignmentStarWithDiagnostics` 失败分支在 IO 线程内补一次 `:Q#`,清空 OnStep 端可能的部分坐标污染。
- L1:`magnitude == magnitude` 改成 `!Double.isNaN(...)`,可读性提升(原代码功能正确)。
- L2:`readUntilHashOrClose` 在 EOF 前已读到部分内容时统一抛 IOException,避免上层 parser 收到半截字符串崩溃。
- M1:`onResume` 在已连接时刷新 GOTO / mount pointing 状态。
- M2:`connected` / `busy` / `gotoInProgress` / `activeDirection` / `alignmentSession` 标 `volatile`。
- M4:`parseRightAscension` 拒绝负号和 ≥24h 输入,不再静默绕回。
- 工具脚本:`scripts/build-debug.ps1` 加 `-p $ProjectRoot` 显式锁定项目根,免受 shell CWD 异常影响。

### 新增

- 太阳系视位置全面升级:行星走 IMCCE VSOP87D 截断表(约 3700 项)+ 光行时迭代 + 周年光行差 + IAU 章动 + 真黄赤交角变换。Jupiter 与 JPL Horizons 视位置吻合到亚角秒(实测 0.7" RA / 0.4" Dec)。
- 月球摄动从 12 项扩展到 25 项(增加 evection 复合项、parallactic 等次级项);精度从 ~5' 提升到 ~30-60"。
- 内置 17 颗著名小行星 / 彗星(Ceres、Vesta、Eros、Halley、Encke、67P 等),基线 Keplerian 元素从 JPL SBDB 全精度抓取并捆绑。
- 设置页新增"小行星 / 彗星"分组:启用开关 + 视星等上限滑块(5-15 mag)+ 三个动作(下载亮小行星 / 下载彗星元素 / 清空已下载),数据通过 JPL SBDB Query API 拉取并缓存到 `filesDir/small-bodies-user.tsv`。
- 星图新增小天体描点:小行星用**橙色菱形**,彗星用**带远日方向尾迹的青色彗核 + 暗色光晕**(尾迹方向通过太阳屏幕位置计算,不可见时回退到向上)。磁吸 GOTO,目标搜索按编号 / 名称(中英文)/ 设计号匹配。
- 星图工具栏新增**图层**按钮 + 多选对话框。**恒星和银河带始终显示,无开关**。可切换的 7 个图层:
  - 星座连线(默认 ON)、太阳系天体(默认 ON)
  - 星团 / 星云 / 星系(三个独立开关,DSO 按类型分,默认 OFF)
  - 小行星 / 彗星(两个独立开关,默认 OFF)
- 小行星 / 彗星图标重新设计:小行星为**橙色实心菱形**,彗星为**青色亮核 + 暗光晕 + 远日方向尾迹**(尾迹用太阳屏幕坐标算,不可见时回退向上)。
- 设置页布局收紧:**命令日志、安全与夜视、小行星 / 彗星** 收进可折叠的 **"更多设置 ▼"** 分组,默认折叠。展开后展示 3 个子面板,符号变为"▲"。
- 移除小天体面板的"在星图中显示小天体"复选框(可见性归图层对话框),`SmallBodyCatalog` 不再持有 `enabled` 状态。
- 工具链新增 `scripts/generate-vsop87.py`(下载 + 截断 + 生成 Java)、`scripts/generate-bundled-small-bodies.py`(SBDB 抓取生成 Java)、`scripts/verify-vsop87.py` 与 `scripts/verify-small-body.py` 用于回归对比。

## v0.1.0 - 2026-04-28

MountBehave 的第一个可用测试版本。

### 已验证

- 在晴空谐波赤道仪 ST17 上完成 WiFi 连接测试。
- 完成手动移动、停止、两星校准和 GOTO 流程的实机测试。
- 三星校准流程已针对 OnStep 架台侧限制进行修复，等待更多夜间测试。

### 新增

- WiFi TCP 连接 OnStep，默认端口 `9999`。
- 八方向手控、速度选择、停止和悬浮急停。
- 离线星图：恒星、深空天体、星座连线、太阳系天体和近似银河带。
- 星图拖拽和缩放，最窄视场约 `1°`，恒星显示到约 12 等。
- 目标搜索和星图点选 GOTO。
- 观测地与时间设置，并支持同步到 OnStep。
- 恒星速、月球速、太阳速跟踪控制。
- 快速同步、两星校准、三星校准和三星极轴精调入口。
- 低空、地平线下和过中天提醒。
- Android 手机和平板横屏布局适配。

### 修复

- 改善 WiFi 连接稳定性，避免移动命令频繁导致连接断开。
- 修复校准和快速同步对 Home 位置的干扰。
- 修复星图拖拽、缩放和低于地平线浏览限制。
- 修复两星/三星校准中目标选择和接受状态不同步的问题。
- 修复三星校准第一颗星因架台侧不匹配导致 OnStep 返回 `E6` 后清空校准状态的问题。

### 已知问题

- 当前版本的 OnStep 校准流程只建议连续执行一次。完成两星或三星校准后，如果取消模型并立刻重新开始校准，校准流程可能卡住。
- 使用 `v0.1.0` 时请不要随意取消已开始/已完成的校准模型；如果确实需要重新校准，建议重启赤道仪后再从头开始。
- 三星极轴精调、Park/Unpark、USB-C 连接和长时间跟踪仍需要更多实机测试。
- 太阳系天体使用低精度算法，不适合作为校准目标或高精度历书。
