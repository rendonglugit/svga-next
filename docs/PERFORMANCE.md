# 性能对比页面

示例 App 首页的“性能对比 · 官方 SVGAPlayer”进入本页。对比对象为 SVGA-Next 0.1.0 和官方源码固定提交 `662d0dc11824e35a3f3fcd247f491efce2bf5ec5`。

页面可以选择 heartbeat、matteRect、Castle，执行 3/5/10 轮采样，并导出包含每轮原始值、设备、资源 SHA-256、尺寸、测试顺序和构建状态的 JSON。结果仅在真实运行后显示；超时/失败侧显示缺失值和错误，不能被当作零耗时。

## 负载分开比较

| 负载 | 相同条件 | SVGA-Next | 官方 |
|---|---|---|---|
| 播放内核解析 | 相同 asset、480×270 px、每项预热一次；从调用到主线程收到 prepared entity | 直接 SVGAParser，不经过实体缓存 | 直接官方 SVGAParser |
| 重复加载 | 同一资源和尺寸，预热一次后重复调用 | SvgaLoader 的实体内存缓存 | 官方 Parser 重新解析，原源码没有实体内存缓存 |
| 播放 | 每库单独播放，FIT_CENTER、相同视口，开始后等待 250 ms 再采样 3 秒 | 相同资源的 SVGA-Next View | 相同资源的官方 View |

两个实现都通过其公开 API 初始化共享声音管理器，音量设为 0，仍计入音频准备工作。这个共同配置避免把声音管理使用方式的差异误认为图像解析性能差异。没有改变官方的音频加载/错误处理算法。

每个负载只在开始时更新进度文字，采样过程中不反复刷新 UI。页面内再次测试会交换先后顺序；记录中的 `engine_order` 是实际顺序。OS 文件缓存、JIT、GC、温度和共享进程状态没有被完全隔离，不应将“预热后解析”称为冷启动。

## 指标定义

- **内核解析中位/P95**：asset 打开、解压、模型/图片和声音准备，到 prepared 主线程回调的总耗时。它包括异步交付等待。
- **重复加载中位/P95**：运行时用户可观察的再次加载耗时。缓存收益单独呈现，不算作解析器加速。
- **实际绘制 FPS**：在测量窗口内实际 `View.onDraw` 调用次数除以持续时间。素材自身为 30 fps 时，约 30 是合理结果，不应把它误判为“没达到 60”。
- **onDraw P95**：CPU 在 View 绘制记录内的耗时，不包括 GPU 执行/最终屏幕呈现。
- **主线程回调间隔 P95**：Choreographer 连续回调的时间间隔，是主线程节奏观测，不是官方 FrameMetrics/JankStats 卡顿判定。
- **位图+编码估算**：两边都使用 Bitmap allocation bytes 加仍保留的 encoded image/media bytes，不统计完整对象图、GPU 和 native 声音内存。
- **重复加载缓存**：该负载结束前缓存拥有的字节。官方无此缓存，因此为 0；这代表缓存常驻成本，不代表官方播放不占内存。
- **Java 堆/PSS 快照变化**：预热前与播放后的同一进程观测值。保留负值，不强制 GC，不能当作精确的 retained allocation、峰值或泄漏结论。

P95 使用 nearest-rank；5 次采样时 P95 就是本轮最大值。严谨性能结论应增加样本数、交换顺序、多设备并使用 release/profileable APK 和 Macrobenchmark/Perfetto。

## 官方对照的来源边界

`benchmark-upstream` 只供示例对比，不加入生产 `svga-runtime` 的依赖或 Maven 发布。保留官方 Apache-2.0 notices，命名空间改为 `dev.svganext.upstream`。

必要适配为 SDK 35 非空 override 签名、Kotlin 2.1 捕获变量/nullable Canvas 类型，以及禁止同步 primitive 的编译限制（仍为同一个全局 unzip monitor）。没有移植 SVGA-Next 的缓存、线程数、音频状态或解析优化。逐文件适配见 [benchmark-upstream-compat.patch](benchmark-upstream-compat.patch)，原文件哈希见 [benchmark-upstream-snapshot.json](benchmark-upstream-snapshot.json)。观测桥只读取字节估算。

## 复现与结果

```sh
./gradlew :sample:testDebugUnitTest :sample:assembleDebug :sample:lintDebug
adb install -r sample/build/outputs/apk/debug/sample-debug.apk
adb shell am start -S -W -n dev.svganext.sample/.PerformanceActivity \
  --ez benchmark_auto true --es benchmark_asset Castle.svga --ei benchmark_samples 5
```

上述 `-S` 只重启示例 App，确保命令实际执行指定资源，不复用旧页面结果。App 私有目录 `files/benchmarks/status.json` 标识运行、完成、失败或取消；`latest.json` 存最后一份结果。页面的导出按钮使用系统文件选择器保存同样的 JSON。真实设备记录放在本项目 [benchmarks](benchmarks/) 下；它们不是启动页面的预填分数。

当前 APK 为 debug 构建，实测设备为 Pixel 8 / API 37。记录描述本次场景，不表示普遍更快或适用于所有设备。示例能运行短测，不替代生产环境长期基准。
