# 星图数据策略

生成日期：2026-04-24

## 当前内置数据

- 恒星：15,598 颗，来自 HYG v4.2，筛选条件为视星等 `mag <= 7.0`，去除太阳。
- 深空天体：1,352 个，来自 OpenNGC，包含 Messier/addendum 目标，并筛选亮度约 `mag <= 11.5` 的 NGC/IC 目标。
- 星座连线：743 段，来自 d3-celestial 的 `constellations.lines.json`，转换为轻量 TSV 资产。
- 行星：暂未内置。长期行星星历建议后续单独实现低精度算法或压缩星历，不先把 JPL DE/VSOP 大数据文件塞进 APK。

## 天球显示

星图页当前使用本地地平坐标显示：

- 默认观测地：Boston，纬度 `42.36010`，经度 `-71.05890`。
- 观测地、当前时间和 OnStep 连接配置统一放在 App 的“设置”页，便于后续把经纬度/时间同步给赤道仪。
- 用户可以在“设置”页手动输入纬度/经度，或通过 Android 系统定位获取 GPS/网络位置。
- 天体位置计算使用当前 UTC 时刻和观测地经度求本地恒星时，再把 J2000 RA/Dec 转为 Alt/Az。
- 本地时间显示使用 Java `ZoneId`/`ZonedDateTime`，默认 Boston 使用 `America/New_York`，夏令时由系统时区规则自动处理。
- GPS 获取的位置使用设备系统时区；如果设备时区和实际观测地不同，天体位置仍按 UTC 与经纬度计算，界面时间显示则跟随设备时区。
- 星图使用虚拟天文馆式透视投影：维护当前朝向、仰角和视场角，把 Alt/Az 天球投影到一个矩形视窗中，地平线和高度/方位网格会随视角自然弯曲。
- 星图支持单指拖动改变朝向/仰角、双指捏合改变视场角。渲染层按视场角过滤目标：广角只显示较亮恒星和少量代表性深空天体，缩小视场后逐步显示更暗的恒星和更多 NGC/IC 目标。
- 深空天体按类型显示不同图标：星团为带十字的黄色圆，星云为绿色菱形，星系为蓝色倾斜椭圆。深空天体名称只在视野中心附近显示，避免边缘文字污染视图。
- 星图支持点选恒星或深空天体作为 GOTO 目标。连接 OnStep 后，App 发送目标坐标命令 `:Sr...#`、`:Sd...#`，再发送 `:MS#` 开始 GOTO。
- “查找/Goto 目标”按钮打开目标弹窗：未连接时只解析并显示目标；连接后可选择“仅显示”或“显示并 GOTO”。输入支持目标名称（如 `M31`、`Rigel`、`NGC 1976`）或赤道坐标（如 `05:35:17 -05:23:28`）。
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

HYG 与 OpenNGC 均为 CC BY-SA 4.0。d3-celestial 为 BSD 3-Clause。发布 App 时需要在关于页或设置页保留 attribution，并确认最终分发形态满足 ShareAlike 要求。
