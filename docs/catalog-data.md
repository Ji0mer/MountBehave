# 星图数据策略

生成日期：2026-04-27

## 当前内置数据

- 恒星：117,930 颗，来自 HYG v4.2，筛选条件为视星等 `mag <= 12.0`，去除太阳。
- 深空天体：1,352 个，来自 OpenNGC，包含 Messier/addendum 目标，并筛选亮度约 `mag <= 11.5` 的 NGC/IC 目标。
- 星座连线：743 段，来自 d3-celestial 的 `constellations.lines.json`，转换为轻量 TSV 资产。
- 太阳系天体：太阳、月亮、水星、金星、火星、木星、土星、天王星、海王星使用 App 内置低精度离线算法动态计算，不写入静态星表。
- 银河：不引入图片或额外星表，使用 J2000 银河坐标系到赤道坐标系的标准旋转矩阵，程序绘制银河赤道附近的近似宽带和边界参考线。

## 天球显示

星图页当前使用本地地平坐标显示：

- 默认观测地：Boston，纬度 `42.36010`，经度 `-71.05890`。
- 观测地、当前时间和 OnStep 连接配置统一放在 App 的“设置”页，便于后续把经纬度/时间同步给赤道仪。
- 用户可以在“设置”页手动输入纬度/经度，或通过 Android 系统定位获取 GPS/网络位置。
- 恒星/深空天体位置计算使用当前 UTC 时刻和观测地经度求本地恒星时，再把 J2000 RA/Dec 转为 Alt/Az；太阳系天体先按当前 UTC 时刻计算当日 RA/Dec，再投影到本地 Alt/Az。
- 本地时间显示使用 Java `ZoneId`/`ZonedDateTime`，默认 Boston 使用 `America/New_York`，夏令时由系统时区规则自动处理。
- GPS 获取的位置使用设备系统时区；如果设备时区和实际观测地不同，天体位置仍按 UTC 与经纬度计算，界面时间显示则跟随设备时区。
- 星图使用虚拟天文馆式透视投影：维护当前朝向、仰角和视场角，把 Alt/Az 天球投影到一个矩形视窗中，地平线和高度/方位网格会随视角自然弯曲。
- 星图支持单指拖动改变朝向/仰角、双指捏合改变视场角。渲染层按视场角过滤目标：广角只显示较亮恒星和少量代表性深空天体，缩小视场后逐步显示更暗的恒星，最窄视场约 1°，可显示到约 12 等。
- 深空天体按类型显示不同图标：星团为带十字的黄色圆，星云为绿色菱形，星系为蓝色倾斜椭圆。深空天体名称只在视野中心附近显示，避免边缘文字污染视图。
- 太阳系天体作为动态目标层加入星图：太阳为黄色圆盘加光芒，月亮为带粗略盈亏的灰白圆盘，土星带环，其他行星为不同颜色的小圆点。太阳系天体名称在视野较窄或靠近视野中心时显示。
- 银河作为背景参考层显示在网格之后、星座线和星点之前。它只表示银河平面附近的大致位置，适合目视寻星时判断星野是否靠近银河带，不等同于真实银道尘埃、暗云或亮区贴图。
- 星图支持点选恒星、深空天体或太阳系天体作为 GOTO 目标。连接 OnStep 后，App 发送目标坐标命令 `:Sr...#`、`:Sd...#`，再发送 `:MS#` 开始 GOTO。
- “查找/Goto 目标”按钮打开目标弹窗：未连接时只解析并显示目标；连接后可选择“仅显示”或“显示并 GOTO”。输入支持目标名称（如 `M31`、`Rigel`、`NGC 1976`、`Jupiter`、`太阳`）或赤道坐标（如 `05:35:17 -05:23:28`）。
- 星图会显示赤道仪当前指向：连接后周期性读取 `:GR#`/`:GD#` 并转换为本地 Alt/Az；未连接时默认显示在正东方向。
- 设置页支持把观测地与当前时间同步到 OnStep：发送 `:St...#` 纬度、`:Sg...#` 经度、`:SG...#` UTC 偏移、`:SL...#` 本地时间和 `:SC...#` 日期。经度/UTC 偏移按 OnStep/LX200 约定使用西经为正号。

生成后的资产：

- `app/src/main/assets/catalog/stars.tsv`
- `app/src/main/assets/catalog/dsos.tsv`
- `app/src/main/assets/catalog/constellation_lines.tsv`

重新生成：

```powershell
cd D:\Android_projects\controller
python .\scripts\generate-sky-assets.py
```

## 数据来源与授权

- HYG Database v4.2: https://astronexus.com/projects/hyg
- OpenNGC: https://github.com/mattiaverga/OpenNGC
- d3-celestial constellation lines: https://github.com/ofrohn/d3-celestial
- JPL Horizons / Planetary Ephemerides 作为太阳系天体验证参考：https://ssd.jpl.nasa.gov/planets/orbits.html

当前太阳系算法采用轻量轨道根数加月亮主要摄动项，适合离线星图、寻星和普通目视辅助。未引入完整 JPL DE/VSOP 星历，因此不应视为高精度历书；预计太阳与主要行星通常在角分到数十角分量级，月亮和水星/外行星在不利位置可能达到约 0.5° 或更大。后续若需要更高精度，可替换为截断 VSOP87/月球 ELP 项或压缩预计算星历。

HYG 与 OpenNGC 均为 CC BY-SA 4.0。d3-celestial 为 BSD 3-Clause。发布 App 时需要在关于页或设置页保留 attribution，并确认最终分发形态满足 ShareAlike 要求。

公开到 GitHub 时的建议做法：

- 代码、图标和文档可以使用项目自己的许可证，但 `stars.tsv`、`dsos.tsv` 这类由 HYG/OpenNGC 派生的星表资产应明确标注来源和 CC BY-SA 4.0 约束。
- d3-celestial 派生的星座连线需要保留 BSD 3-Clause 的版权与许可证说明。
- 如果发布 APK 或把生成后的 catalog 资产提交到仓库，应在 README、关于页或单独的 `NOTICE`/`DATA_LICENSES` 文档中保留数据来源链接、许可证名称和修改说明。
- 如果后续想把 App 代码改成更宽松或闭源的分发方式，最好把第三方数据资产与代码许可证分开写清楚；这不是法律意见，但能避免读者误以为全部内容都是同一种许可证。
