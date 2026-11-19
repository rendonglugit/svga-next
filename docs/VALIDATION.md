# 首版验证

验证日期：2026-11-19。构建基线为 Gradle 8.11.1、AGP 8.10.1、Kotlin 2.1.0、Java toolchain 17、compileSdk 35。

| 检查 | 结果 |
|---|---|
| core JVM tests | 23 项通过 |
| player Robolectric tests | 16 项通过，包含 SDK 28 NATIVE 位图解码 |
| runtime Robolectric tests | 25 项通过，SDK 35 |
| 合计 | 64 项测试，0 failures / 0 errors |
| player / runtime Android lint | 0 errors；player 留有 8 项 warning，runtime 无 warning |
| 示例 APK | assembleDebug 通过 |
| 两个 Android 库 AAR | assembleRelease 通过 |
| Maven 发布模型 | core、player、runtime 已发布到工程内 build/repository 验证目录 |
| 独立 Java 消费项目 | 从发布产物解析依赖并 assembleDebug 通过；旧形态 ParseCompletion 只实现 onError() 仍可编译 |
| 分发版权说明 | AAR classes.jar 包含对应 LICENSE/NOTICE；POM 带 Apache-2.0 许可信息 |
| 源码边界检查 | 68 个 source/resource 文件检查通过，保留上游 attribution comments |

这次的完整构建运行 213 个 Gradle task，其中 159 执行、54 up-to-date，BUILD SUCCESSFUL。独立消费项目执行 22 个 task，BUILD SUCCESSFUL。任务数只是本次工作目录的执行记录，不是性能指标。

## 重点回归

- 活跃资源经过缓存清理、LRU 淘汰、替换后仍有效；最后一个 lease 才清理。
- 多线程 fork/close、取消与完成的竞态，超大字节预算与计量不会溢出。
- 过期加载结果、取消后的排队结果、关闭时的身份错误回调不会交付。
- native 清理异常不会破坏已经安装的新缓存条目。
- View 重绑、可见性、detach/reattach、临时 detach、XML autoPlay/source 管理。
- 真正的 PNG 内联/外部文件解码及采样，采样尺寸不会跨请求串用。
- 两个 Drawable 的声音 stream 独立；准备回调释放后全局/实体音量仍生效。
- 坏 metadata、坏图片、截断压缩流、缺失/失效音频的终态回调和清理。
- 停止/替换与自然完成语义；帧时间、范围边界和系统禁用动画设置。
- 有限阻塞网络读取的取消、超时及 disconnect。

## 适用范围

上述首版 Robolectric 验证运行在主机 JVM。NATIVE 图形模式验证真实位图解码；声音行为部分使用测试替身。首版验证没有设备性能测量。后续增加的性能对比页面已在 Pixel 8 / API 37 运行，记录解析、重复加载、绘制 CPU 和进程内存快照；详见 [PERFORMANCE](PERFORMANCE.md)。没有完整集成构建宿主业务应用，也没有 GPU、内存峰值或真实音频播放质量基准。

Lint 的 8 项 warning 包括较旧的绘制反射、旧 SDK 条件和静态解析器上下文检测。静态解析器实际保存 applicationContext；保留告警记录，没有用全局 lint baseline 或关闭检查制造零告警。

默认工作线程数限制并不限制排队任务总数，缓存字节预算也不等同于所有活跃资源的总内存。按帧数组转置、完整磁盘 LRU/过期策略及性能基准没有列为首版已完成能力。

结构化测试、lint 和产物摘要见 [validation-summary.json](validation-summary.json)。构建产生的完整 JUnit XML 和 HTML 位于各模块 `build/test-results/`、`build/reports/`，本地 Maven 验证产物位于 `build/repository/`。

## 性能页面增量验证

统计/单位、无数据不补值、失败侧结果导出、取消后的迟到资源释放共 6 项 sample 回归通过。sample APK 构建、lint 检查通过，lint 没有 error，仍有示例文字国际化和旧 target 等 warning。真实城堡/心跳对比数据、页面截图与当前 APK 哈希放在 [benchmarks](benchmarks/)；这些短测不是首版 64 项单测的组成部分。
