# 星图数据与授权说明

本文档说明 MountBehave 离线星图数据的来源、筛选策略、精度限制和公开发布注意事项。

## 当前内置数据

| 数据层 | 当前内容 | 来源/实现 |
| --- | --- | --- |
| 恒星 | 约 12 等以内的恒星子集 | HYG Database v4.2 |
| 深空天体 | 常见 Messier、NGC、IC 目标 | OpenNGC 筛选子集 |
| 星座连线 | 简洁西方星座连线 | d3-celestial `constellations.lines.json` 转换 |
| 太阳系行星 / 太阳 / 月亮 | 8 颗行星 + 太阳 + 月亮的视位置 | VSOP87D 截断表(行星)、Meeus 47 扩展(月亮)、IAU 章动 + 周年光行差 + 光行时 |
| 小行星 / 彗星 (基线) | 17 颗著名小天体的轨道元素 | JPL SBDB,内嵌为 Java 常量 |
| 小行星 / 彗星 (扩展) | 用户按需下载 | App 内"下载亮小行星 / 添加彗星"按钮 → 同 JPL SBDB |
| 银河带 | 近似银河平面参考带 | J2000 银河坐标到赤道坐标转换后程序绘制 |

生成后的主要资产位于：

```text
app/src/main/assets/catalog/stars.tsv
app/src/main/assets/catalog/dsos.tsv
app/src/main/assets/catalog/constellation_lines.tsv
```

## 显示策略

- 星图工具栏"图层"按钮控制可切换天体的可见性。**恒星和银河带始终显示**(避免用户误关后无法定位);可切换 7 项:**星座连线 / 太阳系天体**(默认 ON)、**星团 / 星云 / 星系**(三个独立 DSO 类别)、**小行星 / 彗星**(两个独立类别)默认 OFF。设置在当前会话内有效,关闭再打开会重置回默认。
- App 顶层菜单包含**设置**、**连接/同步**、**手控**、**星图**。设置页收纳固件/架台模式、命令日志、安全与夜视、小行星 / 彗星；连接/同步页收纳 WiFi 连接、观测地与时间、跟踪。
- 广视场下只显示较亮恒星，避免星图过密。
- 缩小视场后逐步显示更暗恒星，当前最暗约 12 等。
- 深空天体按类型显示不同图标:星团、星云、星系分别使用不同符号。
- 深空天体名称只在视场中心附近显示，避免文字遮挡。
- 太阳系行星 / 太阳 / 月亮使用各自专属图标(太阳带光芒、月亮带相位明暗、土星带环)。
- 小行星画为**橙色菱形**;彗星画为**青色彗核 + 远日方向尾迹**(尾迹方向由太阳屏幕位置算出,不可见时回退到向上)。
- 太阳系天体不作为校准候选目标,因为现有算法虽然行星已达亚角秒,但月球只到 ~30",且 OnStep 校准模型本身有数据精度上限,做校准基准并不会更准。
- 银河带只用于目视寻星时的方位参考，不表示真实尘埃、暗云或亮区结构。

## 太阳系天体精度

| 天体 | 算法 | 实测精度 |
| --- | --- | --- |
| 八大行星 | IMCCE VSOP87D 截断到约 3700 项(振幅阈值 1×10⁻⁶ rad / 1×10⁻⁷ AU)→ 光行时迭代 → 周年光行差 → 章动 → 真黄赤交角变换 | Jupiter 与 JPL Horizons 视位置吻合到 **亚角秒**(实测 0.7" RA / 0.4" Dec) |
| 太阳 | 用 VSOP87 Earth helio 反向(λ=L_E+180°,β=−B_E)+ 同上视位置链路 | ~1" |
| 月亮 | Schlyter 二体 + Meeus AA 第 47 章扩展摄动项(经度 17 项 + 纬度 9 项 + 距离 3 项) | ~30-60" |
| 小行星 / 彗星 | 二体 Keplerian 传播(M = n(t-tp))→ J2000→date 黄道岁差旋转 → 光行时 → 光行差 → 章动 → 真黄赤交角 | Ceres 与 JPL 吻合 0.6" RA / 8" Dec(余下来自 N-body 摄动) |

适合用途:目视 GOTO、寻星参考、星图显示、低精度时间事件(合冲、月相)。

不适合用途:高精度天体力学研究、掩星预报、毫角秒级测量。彗星亮度尤其只是一阶估计,实际亮度可能偏差 ±2 等。

数据更新策略:VSOP87D 表 2005 年发布,数十年不需更新。小天体基线随 App 发布;新发现彗星和当年观测的暗弱小行星请用设置页"下载亮小行星 / 添加彗星"在线刷新。

## 重新生成数据

```powershell
cd MountBehave
python .\scripts\generate-sky-assets.py            # 恒星 / 深空 / 星座线
python .\scripts\generate-vsop87.py                # 行星 VSOP87D 表(下载 + 截断)
python .\scripts\generate-bundled-small-bodies.py  # 小天体基线(JPL SBDB)
python .\scripts\verify-vsop87.py                  # 行星位置数学回归对比
python .\scripts\verify-small-body.py              # 小天体位置数学回归对比
```

原始 VSOP87D 数据缓存在 `scripts/data/vsop87/`,小天体在线刷新数据保存在用户 `filesDir/small-bodies-user.tsv`。生成后的轻量资产会随 App 打包。

## 数据来源

- HYG Database v4.2: <https://astronexus.com/projects/hyg>
- OpenNGC: <https://github.com/mattiaverga/OpenNGC>
- d3-celestial: <https://github.com/ofrohn/d3-celestial>
- VSOP87D 行星理论(Bretagnon & Francou 1988),IMCCE 公开数据集: <https://ftp.imcce.fr/pub/ephem/planets/vsop87/>
- JPL Solar System Dynamics — Small-Body Database (SBDB) API: <https://ssd-api.jpl.nasa.gov/sbdb.api>(用于内嵌基线和 App 内在线刷新)
- JPL Horizons API 仅作为视位置验证参考: <https://ssd.jpl.nasa.gov/api/horizons.api>

## 授权注意事项

- HYG Database 和 OpenNGC 使用 CC BY-SA 4.0。
- d3-celestial 使用 BSD 3-Clause。
- VSOP87 数据由 IMCCE 公开发布,用于天文/教育目的不需要单独许可,但建议保留来源 attribution。
- JPL SBDB / Horizons 数据由 NASA/JPL Caltech 维护,用于业余天文目的可自由使用,App 内部已写入 attribution。
- 公开代码、提交生成后的 catalog 资产或发布 APK 时，需要保留数据来源 attribution。
- 如果未来为项目添加正式开源许可证，建议把代码许可证和第三方数据许可证分开说明，避免读者误以为所有内容都使用同一许可证。

这不是法律意见，只是当前项目的工程记录和发布提醒。
