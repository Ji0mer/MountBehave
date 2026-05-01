# Changelog

## v0.2.3 - 2026-05-01

MountBehave 第五个测试版本，重点补强极区 GOTO、日志导出、星图银河背景、校准 UI 和横竖屏连接保持。

### GOTO 稳定性

- 北天极附近目标使用动态到位阈值：在 `|Dec| >= 88°` 时，阈值取 `max(1.0°, 90° - |Dec| + 0.25°)`，避免 Polaris 等目标因赤道仪机械停在天极而一直无法显示“接近目标”。
- 新增固件空闲 + 连续两次位置稳定的兜底判断。即使机械限位或极区阈值仍无法覆盖，App 也会终止本地 GOTO 状态并提示“已停，距目标 X°”，避免用户必须手动取消。
- GOTO 轮询达到最大次数后会清理 `gotoInProgress`、`activeGotoTarget`、轮询计数和静止检测状态，并写入 `DIAG GOTO_TIMEOUT`，避免后续 GOTO 被本地旧状态挡住。
- 手动 GOTO、校准 GOTO、快速同步和其他会移动赤道仪的操作会自动把星图时间恢复为现在，避免用历史/未来时刻的动态天体坐标控制赤道仪。

### 星图与数据

- 星图加入 NASA Scientific Visualization Studio `Deep Star Maps 2020` / SVS 4851 银河背景图，代替原来的简化银河轮廓；贴图以低透明度叠加，亮度在实测后上调。
- 星图工具栏的“重置视图”改为“时间”，可弹窗设置日期/时间，也可一键恢复到“现在”。
- 取消小天体下载页的视星等限制控件，用户按需输入名称或下载亮小行星；README 明确列出内置 17 颗小天体基线。
- 星图顶部状态进一步压缩：目标与 GOTO 放在第一行，目标只显示名称，“限位”统一改名为“方位”，星图内不再显示赤道仪指向。

### 日志导出

- 命令日志改为勾选后展开并开始记录，默认勾选；移除“复制 100 行”按钮。
- 导出菜单新增“保存到下载目录”，通过 Android MediaStore 写入 `Download/MountBehave`，文件名改为 `.txt`，便于华为文件管理、QQ 和电脑 MTP 识别。
- 仍保留“选择位置保存”和“分享”；分享时也向接收方暴露 `.txt` 显示名。

### 校准与 UI

- 两星/三星校准状态移动到顶部状态文本框，校准操作压缩为更少行数。
- “设置目标”和“支架GOTO”合并为同一个按钮：先设置目标，选定后按钮切换为支架 GOTO。
- 接受最后一颗校准星后自动保存模型，移除单独的“保存模型”按钮；“结束”和“3. 居中后接受”并排显示。
- 极轴精调说明重写，明确按钮 1 是 GOTO 并手动居中，按钮 2 是 Refine PA 后只调方位角/高度角螺丝。
- 连接/同步页合并“使用 GPS / 应用 GPS”为一个“使用GPS”按钮，“应用坐标”位置改为“同步时间/地点”。

### 平板旋转

- `MainActivity` 接管 `orientation` / `screenSize` 等配置变化；横竖屏旋转时只重建 UI，不再触发 `onDestroy()` 关闭 OnStep 连接。
- 夜视模式和旋转共用同一套 `rebuildContentView()` 刷新路径，确保状态栏、星图、日志、校准和跟踪状态在布局重建后恢复一致。

### 发布

- Android 版本号升至 `versionName 0.2.3` / `versionCode 5`。
- 发布说明、README、数据授权说明和小天体设计记录更新为当前行为。

## v0.2.2 - 2026-04-30

MountBehave 第四个测试版本，重点修复 Home 控件风险和连续 GOTO 状态判断。

### 安全控制

- 从“安全与夜视”中移除 Set Home / Return Home 入口，App 不再发送 OnStep Home 标记或返回命令。保留全局急停、取消 GOTO、刷新 GOTO 状态、Park/Unpark 和夜视模式。
- 移除 Home 相关本地状态、按钮、确认弹窗、字符串和命令枚举，避免用户误把 Home 当作普通 RA/Dec 目标或 Park 替代品使用。

### GOTO 状态

- GOTO 发送成功后记录当前 active target，并自动轮询 `:D#` 状态。
- 当控制器很快返回空闲时，额外读取 `:GR#` / `:GD#`，只有当前指向进入目标 `0.25°` 内才把 GOTO 释放为空闲；未到位时继续保持 GOTO 进行中并继续轮询。
- 手动移动、取消 GOTO、急停、Park、断开连接和快速同步会清理本地 GOTO 轮询状态，避免旧目标影响下一次操作。

### 发布

- Android 版本号升至 `versionName 0.2.2` / `versionCode 4`。
- 文档更新为当前安全控制和 release 构建输出。

## v0.2.1 - 2026-04-29

MountBehave 第三个测试版本，重点是收紧星图与手控界面，并修复桌面预览/触控板输入下的星图拖拽误判。

### UI 收紧

- 设置页中的命令日志移到底部，避免高级日志控件压住常用设置。
- 主菜单展开时在底部显示当前版本号。
- 手控页“架台校准 / 模式选择”合并并更名为“支架校准”，校准状态与目标选择区域进一步压缩。
- App 可见文本统一将“架台”改为“支架”，只在 OnStep 专有概念或历史说明中保留原语义。
- 星图顶部状态区压缩为短摘要，目标、指向、GOTO 和限位信息改为短标签，并把指向/GOTO 合并到一行。

### 星图交互

- 修复星图拖拽可能被误判为缩放的问题。双指手势现在会同时跟踪触点中心与两指距离：中心移动用于平移，只有明确的两指距离变化才触发缩放。
- 星图视图显式声明为可点击/可聚焦目标，确保嵌套在滚动页内时稳定接收触摸序列。

## v0.2.0 - 2026-04-29

MountBehave 第二个测试版本，重点是现场可诊断性、OnStepX 适配、经纬仪流程和安全控制整理。

### 重新发布修正

- 极轴精调入口恢复为保存三星或更多校准模型后可用；两星模型继续用于 GOTO / 跟踪补偿，不再解锁 Refine PA。OnStepX 经纬仪模式继续隐藏极轴精调。

### OnStep / OnStepX 与架台模式

- 新增独立“设置”页，默认使用经典 OnStep；选择 OnStepX 后可切换赤道仪 / 经纬仪模式。
- OnStepX 架台模式写入改为无回包发送 + 读回校验；写入后 App 会强制断开连接，避免未重启控制器时继续发移动或跟踪命令。
- 经纬仪模式下校准流程隐藏极轴精调和架台侧判断；跟踪启动时按双轴语义处理。

### 日志系统

- 新增全应用日志系统，写入 `files/logs/mountbehave-YYYYMMDD.log`，保留最近 7 天。
- `OnStepClient` 统一记录真实 socket 边界的 TX/RX、TX_FAIL、RX_FAIL、timeout 和 post-send 异常，旧 UI 预览式 TX/RX 不再重复打印。
- UI 层补齐连接、断开、手控、GOTO、同步、跟踪、校准、Park/Home、GPS、OnStepX 切换、小天体下载等用户动作和状态快照。
- 设置页支持复制最近 100 行、导出当天日志和清空当天日志；导出前会等待异步写盘队列 flush，减少刚出错后日志缺尾的风险。
- 首次导出前显示隐私提示，说明日志可能包含 IP/端口、经纬度、校准星、时间戳和指向坐标。

### UI 压缩与说明折叠

- 将星图、校准、跟踪、安全与夜视、小行星 / 彗星、晴空配置等长期说明文本折叠到标题旁的 `?` 帮助按钮中，点击后用弹窗查看说明；校准进度、连接状态、目标状态等操作状态仍直接显示。
- 收紧主页面边距、卡片内边距、按钮高度、方向键间距和设置页分组间距，让手机竖屏下的手控/校准/设置内容更紧凑。
- 菜单顺序调整为“设置 / 连接/同步 / 手控 / 星图”；连接、观测地、跟踪和校准集中到“连接/同步”和“手控”页。
- 全局急停与夜视模式并排显示；App 全局改为暗色基础主题。

### Home 机械回位

- Home 操作改用 OnStep 原生 `:hC#` / `:hF#`：`设为 Home` 标记当前机械轴位置，`回到 Home` 直接请求固件回到 Home，不再把 App 记录的 RA/Dec 目标走普通 GOTO。
- 在“安全与夜视”中加入 Home 操作第二排，并给 Set Home / Return Home 增加确认弹窗；Return Home 会阻止已 Park 或已有 GOTO 运行时误触。

### 下载兼容性修复

- 修复部分 Android 设备访问 JPL SBDB 时出现 `Trust anchor for certification path not found`，导致彗星 / 小行星元素下载失败的问题。下载层仍优先使用系统 HTTPS 校验；只有在 `ssd-api.jpl.nasa.gov/sbdb.api` 发生证书链信任失败时，才启用仅限该 JPL API 的 TLS 兼容兜底，不影响 OnStep WiFi/TCP 控制连接。

### Codex 审查后回滚 / 修正

- **回滚 H4(OnStepClient 自动重试)**:之前对所有命令无条件重试一次会让 `:CM#`、`:MS#`、`:A+#` 等状态修改命令在"已执行 + 回包丢失"时被双发(可能把同一颗星接受两次)。改回每个命令只发一次;ESP8266 抖动场景由用户手动重试处理。
- **修正 C4(ObserverState.boston 时区)**:`boston()` 恢复 `BOSTON_ZONE`(`America/New_York`)——Boston 这个**坐标本身**对应美东时区,与手机所在地无关。`withLocation()` 仍用 `ZoneId.systemDefault()`(手动输入坐标=用户在该地附近)。GPS 路径已正确。
- **代码层修复 C2(取消校准)**:不仅是文案。`OnStepCommand` 新增 `ALIGN_ABORT(":A0#")`(OnStep 公开但少被宣传的 abort alignment 命令,固件不识别时静默忽略);`cancelAlignmentSession` 同时入队 `:Q#`(停运动) + `:A0#`(abort align);`startAlignment` 入口在 `:Q#` 之后、观测地同步之前,也补一次 `:A0#` 作为防御性 reset,确保新的 `:A2#`/`:A3#` 起手干净。
- **修正:校准全程输入框保持空白由用户从星图选星**:之前两处自动填推荐星——`startAlignment` 的 onSuccess(开始时)和 `finishAcceptedAlignmentStar` 的下一颗分支(每接受一颗星之后)——都已删掉。`fillSuggestedCalibrationTarget()` 函数本身保留,仅作为"推荐亮星"按钮的显式 handler;用户主动点该按钮才会触发推荐。
- **修正:校准按钮恢复紧凑布局**:`alignActionsOne`(包含"设为当前校准星" + "设置架台侧 / GOTO")从 VERTICAL 改回 HORIZONTAL 双列;主操作"手动居中后同步接受"和"保存模型 / 结束"已是 2 列。整组校准操作从 5 行收紧到 3 行。
- **重写彗星导入流程,修复 "下载彗星元素" 后所有彗星堆叠在天图同一位置** 的 bug:删除 `startCometDownload`(批量调用 SBDB Query API,因高 e 长周期彗星元素精度/解析问题导致位置异常)。新增 `showAddCometDialog` 弹出输入框,用户输入彗星编号或名称 → 单条 `sbdb.api?...&full-prec=true` 请求 → `parseSbdbSingleRecord` 解析单体 JSON → `SmallBodyCatalog.addUserBody` 累加(同名去重)→ 立即在星图出现。仿 Stellarium "Solar System Editor"流程,逐颗按需下载。

### Codex 第三轮反馈修正(彗星添加)

- **HTTP 300 多重匹配处理**:JPL 对歧义查询(如 `1P/Halley`、`Halley`)返回 HTTP 300 + JSON `list` 候选数组,而非错误。新加 `httpGetAllowMultiChoice` 同时接受 200/300;`fetchSbdbResolvingMultiMatch` 检测到 `code==300` 时取 `list[0].pdes` 用 `des=` 重查;输入像编号(数字、C/PD 前缀)时优先走 `des=`。
- **支持抛物 / 双曲轨道**(让 C/2023 A3 等近抛物彗星可用):`SmallBodyEphemeris.heliocentric` 按 e 三段分支:
  - e &lt; 0.999:经典椭圆 Kepler
  - 0.999 ≤ e ≤ 1.001:抛物 Barker 闭式解 `s = 2 sinh(asinh(3W/2)/3)`
  - e &gt; 1.001:双曲 Kepler `M = e·sinh F − F`,Newton-Raphson
  - 拒绝条件从 `e >= 1.0` 放宽到 `e > 4.0`(只挡深度双曲星际过客)
- **用户元素覆盖内嵌**(`SmallBodyCatalog.combined()`):之前 user body 与 bundled 同 designation 时被跳过,导致用户刷新 1P/Halley 看不到效果。改为 user 覆盖 bundled,以"designation+kind"为合并键。
- **空输入校验**(`showAddCometDialog`):用 `setOnShowListener` 重接管 positive button 的 click,空输入时 `setError` + 不 dismiss;非空才查询并关闭对话框。
- **示例文案更新**:对话框说明改为"支持椭圆/抛物/双曲三类轨道,深度双曲(e&gt;4)的星际过客除外。歧义名称(如 Halley)JPL 会返回候选列表,本程序自动取第一个。"

### Codex 第四轮反馈修正(同名小行星污染 + C/ designation 截断)

- **HTTP 300 候选过滤按 `isComet` 匹配**:JPL 对 `Halley` 返回的 `list` 第一项是 2688 Halley(同名小行星),`Encke` 同样第一项是 9134 Encke,导致之前直接取 `list[0].pdes` 把小行星当彗星持久化。新加 `pdesLooksLikeComet(pdes)` 用正则 `\d+[PDXAI]$` 或 `[CPDXAI]/.*` 判定彗星格式;`fetchSbdbResolvingMultiMatch(query, wantComet)` 遍历候选只取与 `wantComet` 匹配的第一项。
- **`parseSbdbSingleRecord` 用 `object.des` 作 designation,不再切斜杠**:之前的 `extractDesignation` 对 `C/2023 A3 (Tsuchinshan-ATLAS)` 会取斜杠前 = `"C"`,导致所有 C 类长周期彗星撞到同一 designation,持久化/覆盖/搜索/图层去重全部互相污染。改用 JPL 已标准化好的 `object.des`("1"、"1P"、"C/2023 A3" 都直接拿来用),斜杠是 designation 自身的一部分,不再人工切割。
- **加 `object.kind` 防御校验**:即使 multi-match 过滤误放过一颗类型不符的天体,`parseSbdbSingleRecord` 在解析时会查 `object.kind`(`an`/`au` = 小行星,`cn`/`cu` = 彗星)与 `isComet` 期望对比;不一致则返回 null,调用方走 "未找到该类型" 路径,绝不会写入用户列表。

### Codex 第五轮反馈修正(非编号彗星 designation 与候选过滤)

- **非编号彗星拼回 IAU 前缀**:JPL 单体响应把 `C/2023 A3` 拆成 `des="2023 A3"` + `prefix="C"`(prefix 单独一字段),前一轮直接用 `object.des` 会丢掉 `C/`。`parseSbdbSingleRecord` 现在按以下规则组装 designation:
  - `desRaw` 已含斜杠 → 用 desRaw(已经是完整 IAU)
  - isComet + prefix 非空 + desRaw 形如 `\d{4}.*`(年份开头) → `prefix + "/" + desRaw` → `"C/2023 A3"`
  - 其他(`1P`、`1`)→ desRaw 原样
- **300 候选过滤同时看 `name` 字段**:JPL 在 list 里给非编号彗星的 pdes 是裸 `"2023 A3"`(无前缀),只有 `name` 字段含 `C/2023 A3 (...)`。新加 `candidateLooksLikeComet(JSONObject)` 同时检查 pdes(`pdesLooksLikeComet`)和 name(`nameLooksLikeComet`,正则 `^[CPDXAI]/.*`),命中任一即视为彗星候选,避免漏掉 `C/...`/`P/...` 非编号彗星。
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
- 设置页新增"小行星 / 彗星"分组:启用开关 + 视星等上限滑块(5-15 mag)+ 动作按钮(下载亮小行星 / 添加彗星 / 清空已下载),数据通过 JPL SBDB API 拉取并缓存到 `filesDir/small-bodies-user.tsv`。
- 星图新增小天体描点:小行星用**橙色菱形**,彗星用**带远日方向尾迹的青色彗核 + 暗色光晕**(尾迹方向通过太阳屏幕位置计算,不可见时回退到向上)。磁吸 GOTO,目标搜索按编号 / 名称(中英文)/ 设计号匹配。
- 星图工具栏新增**图层**按钮 + 多选对话框。**恒星和银河带始终显示,无开关**。可切换的 7 个图层:
  - 星座连线(默认 ON)、太阳系天体(默认 ON)
  - 星团 / 星云 / 星系(三个独立开关,DSO 按类型分,默认 OFF)
  - 小行星 / 彗星(两个独立开关,默认 OFF)
- 小行星 / 彗星图标重新设计:小行星为**橙色实心菱形**,彗星为**青色亮核 + 暗光晕 + 远日方向尾迹**(尾迹用太阳屏幕坐标算,不可见时回退向上)。
- 设置页拆分为顶层“设置”页和“连接/同步”页；命令日志、安全与夜视、小行星 / 彗星保留在“设置”页，连接与观测地同步移动到“连接/同步”页。
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
