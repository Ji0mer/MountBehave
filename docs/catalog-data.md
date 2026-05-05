# 星图数据与授权说明

本文说明 ClearskyGoto 的离线星图、太阳系星历、小天体数据来源和发布注意事项。ClearskyGoto 面向晴空谐波赤道仪现场控制，星图数据的目标是可靠目视寻星和 GOTO 辅助，而不是天体力学研究或掩星预报。

## 内置数据

| 数据层 | 内容 | 来源/实现 |
| --- | --- | --- |
| 恒星 | 筛选后的恒星子集 | HYG Database v4.2 |
| 深空天体 | 常见 Messier、NGC、IC 目标 | OpenNGC 筛选子集 |
| 星座线 | 简化西方星座连线 | d3-celestial 数据转换 |
| 银河背景 | 全天天赤道坐标银河图 | NASA SVS Deep Star Maps 2020 |
| 主要太阳系天体 | 太阳、月球和八大行星 | JPL DE440s 抽样表，覆盖 2025-01-01 至 2050-01-01 |
| 小天体基线 | 常见小行星和彗星 | JPL SBDB 元素 |
| 用户小天体 | 按名称添加的小行星/彗星 | App 内调用 JPL SBDB API |

主要资产位置：

```text
app/src/main/assets/catalog/
app/src/main/assets/ephemeris/solar_major_vectors.bin
app/src/main/res/drawable-nodpi/milkyway.jpg
```

## 太阳系星历

主要太阳系天体采用 JPL DE440s 生成的抽样向量表：

- 时间覆盖：2025-01-01 至 2050-01-01。
- 步长：3 小时。
- 参考系：apparent geocentric，mean equator/equinox of date。
- App 端对月球做观测者拓扑视差校正。
- 资源加载失败或超出覆盖范围时，自动回退解析模型并记录一次警告。

当前精度定位：

- 太阳、行星：适合望远镜 GOTO 和星图显示。
- 月球：3 小时线性插值下可能有十几角秒级误差，适合目视寻月。
- 行星亮度：使用距离和相位角近似，不含土星环倾角等细节。

重新生成主要太阳系星历：

```powershell
python .\scripts\generate-major-body-ephemeris.py
```

脚本依赖 `skyfield`，并使用 `de440s.bsp`。输出为小端 float32 二进制，Java 端会校验 magic、payload size 和首个太阳距离。

## 小天体数据

内置小天体只作为基线，真实观测前建议按需在线添加或刷新目标。用户添加的数据保存到 App 私有目录。

实现原则：

- 使用 JPL SBDB 单体查询。
- 支持小行星、周期彗星、非编号彗星和近抛物/双曲轨道。
- 同名候选会按小行星/彗星类型过滤，避免 Halley、Encke 等名称误命中同名小行星。
- 用户添加的同名目标覆盖内置基线。

详细小天体方案见 [small-bodies-plan.md](small-bodies-plan.md)。

## 显示策略

- 恒星和银河背景始终显示。
- 星座线、太阳系、小行星、彗星等图层可切换。
- 太阳系天体使用专属图标。
- 小行星使用橙色菱形。
- 彗星使用青色彗核和远日方向尾迹。
- 银河背景使用低亮度叠加，仅作为目视方位参考。

## 数据来源

- HYG Database v4.2: <https://astronexus.com/projects/hyg>
- OpenNGC: <https://github.com/mattiaverga/OpenNGC>
- d3-celestial: <https://github.com/ofrohn/d3-celestial>
- JPL Solar System Dynamics SBDB API: <https://ssd-api.jpl.nasa.gov/sbdb.api>
- JPL DE440/DE440s ephemeris: <https://ssd.jpl.nasa.gov/planets/eph_export.html>
- NASA Scientific Visualization Studio Deep Star Maps 2020 / SVS 4851: <https://svs.gsfc.nasa.gov/4851/>
- NASA Images and Media Usage Guidelines: <https://www.nasa.gov/nasa-brand-center/images-and-media/>

## 授权注意事项

- HYG Database 和 OpenNGC 使用 CC BY-SA 4.0。
- d3-celestial 使用 BSD 3-Clause。
- JPL 数据由 NASA/JPL Caltech 维护，适合天文应用引用并应保留来源说明。
- NASA SVS 图像按 NASA Images and Media Usage Guidelines 使用，需要注明 NASA/Goddard Space Flight Center Scientific Visualization Studio，不能暗示 NASA 背书。

发布 APK、公开源码或复制生成后的数据资产时，请保留这些 attribution。本文不是法律意见，只是当前项目的数据来源和发布记录。
