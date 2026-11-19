# SVGA-Next

面向现代 Android 的下一代高性能、生命周期感知且无内存泄漏的 SVGA 播放与资源管理库。播放内核基于 Apache-2.0 官方 SVGAPlayer 深度重构；资源管理层独立实现共享引用租约（ResourceLease）、按字节计量的 LRU 缓存、并发请求合并（Request Coalescing）及 View 生命周期安全绑定。

这是独立命名空间的首版工程（`0.1.0`），可与旧版 `com.opensource.svgaplayer` 并行接入。资源管理层不依赖其他应用的代码、资源或私有组件。

## 从哪里开始读

| 模块 | 内容 | 第一条阅读路径 |
|---|---|---|
| `svga-core` | 纯 Kotlin 的资源所有权和缓存 | `ResourceLease → ByteSizedCache → GenerationGate` |
| `svga-player` | 格式解析、采样、Canvas 绘制、动态内容和音频 | `SVGAParser → SVGAVideoEntity → SVGADrawable → SVGACanvasDrawer` |
| `svga-runtime` | Android 加载、缓存复用、取消和 View 管理 | `SvgaLoader → ManagedSvgaImageView` |
| `sample` | 两个 View 共享资源、切换请求、遮罩和音频示例 | `MainActivity` |
| `benchmark-upstream` | 示例对照使用的官方源码快照，仅必要编译适配 | `SVGAParser → SVGADrawable` |

设计说明见 [ARCHITECTURE](docs/ARCHITECTURE.md)，官方来源及改动见 [UPSTREAM](docs/UPSTREAM.md)，资源管理的设计取舍见 [DESIGN-REFERENCE](docs/DESIGN-REFERENCE.md)。

## 首版能力

- 同一资源及相同目标尺寸的请求合并，缓存命中返回独立 client lease。
- 缓存以估算资源字节计量，支持活跃引用保护（在播资源绝不因预算被强制逐出）与闲置条目按时效过期回收。
- 帧数据扁平化：使用紧凑 `FloatArray(11)` 打包仿射参数与布局尺寸，消除解析阶段海量 `Matrix` 与 `SVGARect` 对象分配，绘制时复用共享变换矩阵。
- 细粒度并发与可靠 I/O：引入分段读写锁 `KeyedFileLocks` 替代全局大锁，不同资源解压与读取互不阻塞；采用临时文件原子发布（`publishCacheFile`），杜绝半截损坏缓存。
- 纯净的 URL 磁盘预取与在途请求合并：`prefetchToDisk` 仅预热磁盘，不预先分配位图或占用声音采样；同 URL 并发共享单次传输并支持独立取消。
- View 重绑立即释放旧引用；隐藏或离开窗口时，已显示资源延后到主线程队列清理，快速恢复可零重载保留原播放对象。旧结果不能覆盖新绑定。
- Drawable 的音频 stream ID 分开存放，两个 View 共享资源时可独立停止声音。
- 保留官方的目标尺寸采样、matte、动态图片/文字/绘制能力和声音管理接口。
- JVM 单测验证引用和缓存行为，Android 单测验证加载/生命周期与播放内核；示例工程可生成 APK。

缓存预算只约束缓存持有的资源，不是进程内存上限。正在播放的已淘汰资源仍然占用内存，直到最后一个 client lease 关闭。字节估算也不是 Java 对象、GPU 和 SoundPool native 内存的完整测量。

## 构建与示例

需要 JDK 17、Android SDK 35。配置 Android Studio 的 SDK 路径或本地 `local.properties`，使用仓库自带 wrapper：

```sh
./gradlew :svga-core:test :svga-player:testDebugUnitTest :svga-runtime:testDebugUnitTest
./gradlew :sample:assembleDebug :svga-player:assembleRelease :svga-runtime:assembleRelease
```

示例 APK 位于 `sample/build/outputs/apk/debug/`。它展示心跳、matte 和带音频动画，并允许在播放时清缓存或隐藏其中一个 View。

首页“性能对比 · 官方 SVGAPlayer”可在同机、相同资源与尺寸下实测解析、重复加载、绘制和内存数据，支持导出原始 JSON。缓存负载与解析器负载分开统计，失败不会填入推测数值。方法、官方基线适配及复现方式见 [PERFORMANCE](docs/PERFORMANCE.md)。

## 接入方式

首版提供源码工程与本地 Maven 发布配置，不假设任何 Maven Central 或 GitHub 发布地址。

```sh
./gradlew :svga-core:publishToMavenLocal :svga-player:publishToMavenLocal :svga-runtime:publishToMavenLocal
```

应用的仓库配置添加 `mavenLocal()` 后，可依赖：

```kotlin
implementation("dev.readablesvga:svga-runtime:0.1.0")
```

建议通过 runtime 接入：

```kotlin
val loader = SvgaLoader(context.applicationContext)
val view = ManagedSvgaImageView(context)
view.bind(
    loader,
    SvgaRequest(SvgaSource.Asset("heartbeat.svga"), width = 240, height = 240)
)

// 页面或业务拥有 loader；销毁时先释放 View，再关闭 loader。
view.clearRequest()
loader.close()
```

`SvgaSource.File(file)` 支持本地文件，`SvgaSource.Url("https://...")` 支持网络动画。使用网络来源时，应用应声明 `android.permission.INTERNET`。`width/height` 同时为 0 表示原始解码尺寸，同时大于 0 时启用官方采样逻辑。

`ManagedSvgaImageView` 应通过 `bind` 指定来源；它不会自动解析继承的 XML `source` 属性，以免绕过资源所有权管理。其余播放属性可以照常设置。

如果不使用 View 而自行调用 `loader.load`，成功结果里的 `ResourceLease` 属于调用方。使用期间保持 lease 打开，结束时调用 `close()`；不要手动清除共享 `SVGAVideoEntity`。`cancel()` 取消尚未交付的结果，不会代替关闭已经交付的 lease。

### URL 磁盘预取

使用 `SVGAParser(context.applicationContext).prefetchToDisk(url) { result -> … }` 可在播放前预取。回调在主线程交付，返回值是取消函数。成功结果是缓存目录或原始文件，不是播放实体；不要修改该产物，也不要把它视为永久文件。正常 URL 播放会自动查找同一缓存，不需要手动传递文件。预取不初始化位图或音频。

## 源码边界与验证

官方基线固定为 `662d0dc11824e35a3f3fcd247f491efce2bf5ec5`，其原始 notices 保留在源码中，LICENSE 为 Apache-2.0。新增模块也使用 Apache-2.0。资源管理模块按明确的所有权和请求契约独立实现。性能结论应以本项目的实际验证为准。

当前实现使用 FloatArray(11) 打包帧数据并复用绘制矩阵，不代表整条绘制路径零分配。

验证结果见 [VALIDATION](docs/VALIDATION.md)。首次公开发布之前应补充目标设备上的真实动画、耗时和内存测量；首版测试不等于所有历史 SVGA 内容都已验证。

贡献方式见 [CONTRIBUTING](CONTRIBUTING.md)。
