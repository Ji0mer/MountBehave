# 星图数据与授权说明

本文档说明 MountBehave 离线星图数据的来源、筛选策略、精度限制和公开发布注意事项。

## 当前内置数据

| 数据层 | 当前内容 | 来源/实现 |
| --- | --- | --- |
| 恒星 | 约 12 等以内的恒星子集 | HYG Database v4.2 |
| 深空天体 | 常见 Messier、NGC、IC 目标 | OpenNGC 筛选子集 |
| 星座连线 | 简洁西方星座连线 | d3-celestial `constellations.lines.json` 转换 |
| 太阳系天体 | 太阳、月亮、主要行星 | App 内置低精度算法动态计算 |
| 银河带 | 近似银河平面参考带 | J2000 银河坐标到赤道坐标转换后程序绘制 |

生成后的主要资产位于：

```text
app/src/main/assets/catalog/stars.tsv
app/src/main/assets/catalog/dsos.tsv
app/src/main/assets/catalog/constellation_lines.tsv
```

## 显示策略

- 广视场下只显示较亮恒星，避免星图过密。
- 缩小视场后逐步显示更暗恒星，当前最暗约 12 等。
- 深空天体按类型显示不同图标：星团、星云、星系分别使用不同符号。
- 深空天体名称只在视场中心附近显示，避免文字遮挡。
- 太阳系天体不作为校准候选目标，因为当前算法精度不足以承担校准基准。
- 银河带只用于目视寻星时的方位参考，不表示真实尘埃、暗云或亮区结构。

## 太阳系天体精度限制

当前太阳系天体位置使用轻量离线算法，目标是控制 APK 体积并在无网络环境下提供大致位置。

适合用途：

- 目视寻星参考
- 判断目标大致方位和高度
- 星图展示

不适合用途：

- 高精度历书
- 摄影级精确构图
- 掩星、凌日、合月等高精度事件判断
- 校准赤道仪指向模型

后续如果需要更高精度，可以改为截断 VSOP87、ELP 月球模型，或加入压缩预计算星历。

## 重新生成数据

```powershell
cd D:\Android_projects\controller
python .\scripts\generate-sky-assets.py
```

原始数据通常不提交到仓库；生成后的轻量资产会随 App 打包。

## 数据来源

- HYG Database v4.2: <https://astronexus.com/projects/hyg>
- OpenNGC: <https://github.com/mattiaverga/OpenNGC>
- d3-celestial: <https://github.com/ofrohn/d3-celestial>
- JPL Horizons / Planetary Ephemerides 仅作为太阳系天体验证参考：<https://ssd.jpl.nasa.gov/planets/orbits.html>

## 授权注意事项

- HYG Database 和 OpenNGC 使用 CC BY-SA 4.0。
- d3-celestial 使用 BSD 3-Clause。
- 公开代码、提交生成后的 catalog 资产或发布 APK 时，需要保留数据来源 attribution。
- 如果未来为项目添加正式开源许可证，建议把代码许可证和第三方数据许可证分开说明，避免读者误以为所有内容都使用同一许可证。

这不是法律意见，只是当前项目的工程记录和发布提醒。
