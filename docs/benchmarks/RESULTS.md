# Pixel 8 实测记录

2026-11-19，在 Pixel 8 / API 37 上使用同一个 debug APK、480×270 px、每负载预热一次、5 轮加载、3 秒播放窗口完成两侧测试。双方使用公开共享声音管理器，静音播放，仍准备音频。

| 资源 | 实现 | 内核解析中位 ms | 重复加载中位 ms | 绘制 / 素材 FPS | onDraw P95 ms |
|---|---|---:|---:|---:|---:|
| Castle.svga | Readable SVGA | 70.68 | 0.145 | 19.66 / 20 | 2.31 |
| Castle.svga | 官方 SVGAPlayer | 57.71 | 55.802 | 20.00 / 20 | 4.13 |
| heartbeat.svga | Readable SVGA | 641.94 | 0.087 | 29.98 / 30 | 0.47 |
| heartbeat.svga | 官方 SVGAPlayer | 687.73 | 705.364 | 29.98 / 30 | 0.46 |

重复加载的差异主要体现实体缓存复用；不是内核解析器同倍数提速。城堡素材的内核解析本轮 Readable 更慢，带音频心跳的结果不同；没有“所有负载都更快”的结论。两边位图+编码字节估算相同，Readable 的重复加载缓存增加常驻成本。

Java 堆和 PSS 为共享进程快照，GC 会导致负值，不能从前后差值证明内存泄漏或精确占用。onDraw 只计 CPU 记录，不含 GPU。实测系统为 API 37，单机 debug 短测不代表发布版/其他设备。

原始数据：[城堡](2026-11-19-pixel8-castle.json)、[心跳](2026-11-19-pixel8-heartbeat.json)。

`heartbeat-preliminary.json` 是较早的诊断采样，尚未去除逐轮进度 UI 刷新干扰，不用于上表结论。首次未统一声音配置时遇到准备超时；最终配置与复现方式见 [测量说明](../PERFORMANCE.md)。

页面截图：[完整截图](pixel8-performance-castle.png)。本表由原始 JSON 生成，没有预填性能分数。
