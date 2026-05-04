# 小行星 / 彗星扩展方案

**状态**:基线已实施(用户批准后完成)。本文档现作为设计与实测记录保留。

实际方案与原方案的关键差异:
- 用户决定"基线只内嵌最亮 / 最著名的少量天体,余下由 App 内接口按需下载",不再 bundle 全 H≤12 数据集。
- 数据后端统一为 JPL SBDB(单体 + 查询两个 endpoint),不再使用 MPC `CometEls.txt`/`MPCORB.DAT`,避免后者大文件下载。
- 实际实现见 `scripts/generate-bundled-small-bodies.py`、`SmallBody.java`、`SmallBodyEphemeris.java`、`SmallBodyCatalog.java`、`BundledSmallBodies.java`,与 `MainActivity` 设置页的"小天体星历"分组。当前实际 UI 是亮小行星批量同步、彗星逐颗按名称/编号添加;已移除视星等上限滑块。

实测精度(2026-04-28 UTC,地心视位置,vs JPL Horizons quantity '2'):
- Ceres:RA 偏差 0.04s ≈ 0.6"、Dec 偏差 7.8"。残差主要来自 N-body 摄动与黄道极小幅平动,目视 GOTO 远超需求。

下面是原始方案(供参考):

## 与行星的本质差异

- **行星**:8 颗轨道极为稳定,VSOP87D 一次内嵌即可,数十年不需更新。
- **小行星**:已编号 ~140 万颗,需按星等过滤;公布元素需周期性刷新(轨道精度随观测累积)。
- **彗星**:轨道偏心率高,且受非引力力(挥发气体喷射)影响,元素变化更快;新发现彗星需要尽快入库。

## 数据来源

| 来源 | 用途 | URL | 大小 | 刷新频率 |
|---|---|---|---|---|
| MPCORB.DAT (MPC) | 全部小行星轨道元素 | `https://www.minorplanetcenter.net/iau/MPCORB/MPCORB.DAT` | ~250 MB(完整) | 每日 |
| MPCORB_extended.json (MPC) | 同上,JSON 格式带衍生量(H, G) | `https://www.minorplanetcenter.net/Extended_Files/mpcorb_extended.json.gz` | ~80 MB(压缩) | 每日 |
| CometEls.txt (MPC) | 全部彗星 | `https://www.minorplanetcenter.net/iau/MPCORB/CometEls.txt` | ~1 MB | 周/月 |
| JPL SBDB Query API | 选择性查询 | `https://ssd-api.jpl.nasa.gov/sbdb_query.api` | 自定 | 实时 |
| COBS Visual DB | 彗星实测亮度估计 | `https://cobs.si/analysis` | 可忽略 | 周 |

授权:MPC 数据明确允许业余天文使用,要求 attribution(README 已留 attribution 段位置)。

## 过滤策略(把数据压到合理大小)

目镜目视典型可见性边界:
- 200mm 反射镜:小行星 ~14 等、彗星 ~13 等
- 60mm 寻星镜:小行星 ~10 等、彗星 ~9 等

建议**捆绑基线 + 在线刷新**双层:
- 基线:H ≤ 12.0 的小行星(~3500 颗)+ 当下 q < 4 AU 的所有彗星(~200 颗)
- 估算 APK 体积增量:每颗 ~80 字节(紧凑二进制)→ ~300 KB
- 用户刷新时拉最新,缓存到 `filesDir/small-bodies/`

## 位置计算

公式来自 Meeus *Astronomical Algorithms* 第 33-35 章 + Standish 1992。

### 小行星(椭圆轨道,e<1)

输入元素:`a`(半长轴 AU)、`e`(离心率)、`i`(倾角)、`Ω`(升交点)、`ω`(近日点)、`M₀`(epoch 平近点角)、`epoch`(JD)。

```
n = k × a^(-3/2)             (k = 0.01720209895 高斯引力常数)
M = M₀ + n × (t - epoch)
solve E - e·sin E = M        (Newton-Raphson 迭代,与现有月亮代码相同)
ν = 2·atan(√((1+e)/(1-e)) × tan(E/2))   (真近点角)
r = a × (1 - e·cos E)
位置 (helio rectangular) = orbitToEcliptic(Ω, i, ω, r, ν)   (与现有 SolarSystemEphemeris.orbitalToEcliptic 同型)
```

复用 `SolarSystemEphemeris.geocentricFromHelio`(VSOP87 Earth)→ 光行时 → 光行差 → 章动 → 赤道坐标。

### 彗星(可能 e≥1)

椭圆 e<1:同上。
抛物线 e=1:用 Barker 方程(代数解,无需迭代)。
双曲线 e>1:用双曲 Kepler 方程 `M = e·sinh F - F`。

实践:绝大多数活动彗星 e<1.0 或 e≈1,Marsden-style 元素直接给 `q`(近日距)、`T`(近日时刻)。
转换:`a = q / (1-e)` (椭圆) → 套用上式;e=1 走抛物分支。

非引力力(A₁、A₂、A₃)在元素表里有时给出,目视精度可忽略。

### 视星等

小行星:`m = H + 5·log₁₀(r·Δ) + phase_function(α)`
- `H` = 绝对星等(MPC 给)
- `r` = 日心距,`Δ` = 地心距
- 相位函数 G:`-2.5·log₁₀((1-G)·Φ₁(α) + G·Φ₂(α))`(Bowell 1989,Meeus Ch 41)

彗星:`m = H + 5·log₁₀(Δ) + 2.5·n·log₁₀(r)`
- `n` = 活动指数(MPC 给,典型 4-10)

## 架构(分阶段)

```
Vsop87Tables.java          (已有,不变)
SolarSystemEphemeris.java  (已有,小补 - 暴露 earthCoordinates 和 nutation 给小天体复用)
SmallBodyCatalog.java      (新增,加载二进制资产)
SmallBodyEphemeris.java    (新增,Keplerian 传播 + 同样的光行时/光行差/章动链路)
SkyCatalog.java            (扩展,把小天体 Body 注入 sky chart 渲染)
MainActivity 搜索/UI       (扩展,搜索框可匹配 "1 Ceres"、"Vesta"、"C/2023 A3" 等)
scripts/generate-small-bodies.py  (新增,从 MPC 下载、过滤、序列化为二进制)
```

## 二进制格式建议

以小端紧凑结构存到 `app/src/main/assets/small-bodies.bin`:

```
header: magic "MBSB" + version u16 + epoch_jd f64 + asteroid_count u32 + comet_count u32
asteroid records (each 64 bytes):
  name[16] (UTF-8, null-padded)
  H f32, G f32
  a f64, e f64, i f64, omega_caps f64, omega_lower f64, M0 f64
comet records (each 80 bytes):
  designation[24]
  H f32, n_activity f32
  q f64, e f64, i f64, omega_caps f64, omega_lower f64, T_peri_jd f64
```

3500 行星 × 64 + 200 彗星 × 80 = 240 KB(原始),gzip 后 ~150 KB。可接受。

## UI 改动

设置页(`v0.2.3` 实际界面已经落地,下面保留原方案作历史记录):
- 新分组"小天体"
- 开关:启用/禁用小天体显示
- 星等上限滑块(默认 11.0)
- 按钮"刷新小天体元素"+ 上次刷新时间
- 显示包内基线版本号

星图页:
- 与深空天体同样的描点 + label
- 小行星:小圆点(类似恒星但不同色,例如 #FFA500)
- 彗星:十字符号 + 朝向太阳的尾迹方向短线

搜索:
- 输入 "1" → 1 Ceres
- 输入 "Vesta"、"vesta" → 4 Vesta
- 输入 "C/2023 A3" → Tsuchinshan-ATLAS

## 在线刷新(可选阶段 2)

最小实现:
- "刷新"按钮触发 `OkHttp` 或原生 `HttpURLConnection` 拉两个文件
- MPCORB 下载到 `filesDir`,本地解析 + 截断 + 转二进制覆盖
- 仅在用户主动点击时拉(避免后台流量)

注意:MPC ORB 每行格式固定列宽(类似 VSOP87),解析直接读 ASCII 字段。

## 实施工作量估计

- Python 生成器:~200 行 + 调试
- Java Keplerian 模块:~250 行
- SkyCatalog/View 集成:~100 行  
- UI 设置面板:~80 行
- 在线刷新(可选):额外 ~150 行
- 测试 + 与 JPL 小天体库对比:~1 天
- **总计:基础 ~2 天,含在线刷新 ~3 天**

## 已知风险

1. **彗星轨道过期**:彗星亮度预报误差可达 ±2 等。要在 UI 里加"亮度为预测值,实际可能偏差"提示。
2. **新发现天体**:基线刷新前后窗口 1-2 个月。在线刷新解决。
3. **小行星编号变更**:极罕见,可忽略。
4. **MPC URL 变化**:历史上稳定,但可在 settings 里允许自定义 URL。

## 推荐实施顺序

1. **Phase 1**(基础):捆绑基线 + Java 计算 + 基础 UI;**~2 天**;能离线显示和 GOTO 主流小行星/彗星
2. **Phase 2**(刷新):在线刷新按钮 + 缓存管理;**~1 天**
3. **Phase 3**(精修):彗星尾绘制、亮度修正模型、相位曲线;**~1 天**

每个 phase 都可独立交付。

---

需要继续往下做的话,告诉我从哪个 phase 开始(我建议从 Phase 1)。
