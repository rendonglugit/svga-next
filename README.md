# SVGA-Next

面向现代 Android 的下一代高性能、生命周期感知且无内存泄漏的 SVGA 播放与资源管理库。播放内核基于 Apache-2.0 官方 SVGAPlayer 深度重构；资源管理层独立实现共享引用租约（ResourceLease）、按字节计量的 LRU 缓存、并发请求合并（Request Coalescing）及 View 生命周期安全绑定。

这是独立命名空间的首版工程（`0.1.0`），可与旧版 `com.opensource.svgaplayer` 并行接入。资源管理层不依赖其他应用的代码、资源或私有组件。

## 从哪里开始读

| 模块 | 内容 | 第一条阅读路径 |
|---|---|---|
| `svga-core` | 纯 Kotlin 的资源所有权和缓存 | `ResourceLease → ByteSizedCache → GenerationGate` |
| `svga-player` | 格式解析、采样、Canvas 绘制、动态内容和音频 | `SVGAParser → SVGAVideoEntity → SVGADrawable → SVGACanvasDrawer` |
| `svga-runtime` | Android 加载、缓存复用、取消和 View 管理 | `SvgaLoader → ManagedSvgaImageView` |
| `svga-compat` | 存量项目 0 代码改动平替（保留旧包名、Safe-Clear、自动初始化） | `SVGAImageView → SVGAParser → SafeClear` |
| `sample` | 两个 View 共享资源、切换请求、遮罩和音频示例 | `MainActivity` |
| `benchmark-upstream` | 示例对照使用的官方源码快照，仅必要编译适配 | `SVGAParser → SVGADrawable` |

设计说明见 [ARCHITECTURE](docs/ARCHITECTURE.md)，官方来源及改动见 [UPSTREAM](docs/UPSTREAM.md)，旧库迁移说明见 [MIGRATION](docs/MIGRATION.md)，资源管理的设计取舍见 [DESIGN-REFERENCE](docs/DESIGN-REFERENCE.md)。

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
./gradlew :svga-core:test :svga-player:testDebugUnitTest :svga-runtime:testDebugUnitTest :svga-compat:testDebugUnitTest
./gradlew :sample:assembleDebug :svga-player:assembleRelease :svga-runtime:assembleRelease :svga-compat:assembleRelease
```

示例 APK 位于 `sample/build/outputs/apk/debug/`。它展示心跳、matte 和带音频动画，并允许在播放时清缓存或隐藏其中一个 View。

首页“性能对比 · 官方 SVGAPlayer”可在同机、相同资源与尺寸下实测解析、重复加载、绘制和内存数据，支持导出原始 JSON。缓存负载与解析器负载分开统计，失败不会填入推测数值。方法、官方基线适配及复现方式见 [PERFORMANCE](docs/PERFORMANCE.md)。

## 接入指南

首版提供源码工程与本地 Maven 发布配置，不假设任何外部 Maven Central 或私有仓库发布地址。

```sh
./gradlew :svga-core:publishToMavenLocal :svga-player:publishToMavenLocal :svga-runtime:publishToMavenLocal :svga-compat:publishToMavenLocal
```

在宿主应用的根 `build.gradle.kts` 中添加 `mavenLocal()` 仓库后，根据项目阶段选择依赖：

```kotlin
// 方式 A（新项目推荐）：完整现代化运行时（包含租约所有权管理、请求合并、字节 LRU 缓存与生命周期感知的 Managed View）
implementation("dev.svganext:svga-runtime:0.1.0")

// 方式 B（存量项目极速平替）：官方旧版完全兼容层（保留 com.opensource.svgaplayer 包名、0 行代码修改、Safe-Clear 防崩、自动初始化）
implementation("dev.svganext:svga-compat:0.1.0")
```

---

### 1. 全局初始化配置（推荐在 Application 中）

```kotlin
class MainApplication : Application() {
    companion object {
        // 全局单例 Loader，确保内存缓存与请求合并跨页面、跨 View 生效
        lateinit var svgaLoader: SvgaLoader
            private set
    }

    override fun onCreate() {
        super.onCreate()
        // 1. 若动画包含音频，必须显式初始化音频池（否则播放无声）
        SVGASoundManager.init()

        // 2. 初始化全局加载器（默认 32MB 内存缓存预算）
        val config = SvgaConfig(memoryCacheBytes = 64L * 1024 * 1024)
        svgaLoader = SvgaLoader(this, config)

        // 3. 调试日志开关（可选）
        SVGALogger.setLogEnabled(BuildConfig.DEBUG)
    }
}
```

> [!IMPORTANT]
> **关于 `SvgaLoader` 生命周期的重要原则**：  
> `SvgaLoader` 内部持有 `ByteSizedCache` 内存缓存并注册了系统内存回调（`ComponentCallbacks2`）。**请务必作为应用级全局单例或大业务域单例使用**。切勿在每次播放动画或每个 View 销毁时频繁创建并调用 `loader.close()`，否则会导致跨 View 缓存命中率降为 0、并发请求合并完全失效。

---

### 2. 全新项目接入指南（最佳实践）

#### XML 布局声明
```xml
<dev.svganext.runtime.ManagedSvgaImageView
    android:id="@+id/svgaView"
    android:layout_width="200dp"
    android:layout_height="200dp"
    app:autoPlay="true"
    app:loopCount="0"
    app:fillMode="Forward" />
```

> [!NOTE]
> `ManagedSvgaImageView` 继承自 `SVGAImageView`，完整支持 `loopCount`、`autoPlay`、`fillMode`（Forward/Backward/Clear）、`antiAlias` 等播放控制属性。  
> **注意**：为了确保所有在播实体都严格纳入租约所有权管理，`ManagedSvgaImageView` **故意禁用了 XML 的 `app:source` 自动解析**。资源来源必须通过 Kotlin/Java 代码中的 `bind` 注入。

#### Kotlin 代码绑定与控制
```kotlin
// 1. 构造请求
// 支持 SvgaSource.Asset("path.svga")、SvgaSource.File(file)、SvgaSource.Url("https://...")
// width/height 同时为 0 表示原始解码尺寸；同时大于 0 时启用下采样解码以节省内存
val request = SvgaRequest(
    source = SvgaSource.Asset("heartbeat.svga"),
    width = 200.dp,
    height = 200.dp
)

// 2. 配置监听回调（可选）
svgaView.onLoaded = { req -> /* 解码成功并已绑定 */ }
svgaView.onLoadError = { error -> /* 解码或网络失败 */ }
svgaView.callback = object : SVGACallback {
    override fun onFinished() { /* 仅在自然播放到最后一帧完成时触发 */ }
    override fun onPause() {}
    override fun onRepeat() {}
    override fun onStep(frame: Int, percentage: Double) {}
}

// 3. 绑定并开始播放（autoPlay=true 时自动起播）
svgaView.bind(MainApplication.svgaLoader, request)
```

#### 动态内容替换（文本、头像、点击热区）
```kotlin
// 动态实体必须属于当前 View 独占，切勿跨 View 复用
val dynamic = SVGADynamicEntity().apply {
    setDynamicText("SVGA-Next", textPaint, "banner_text")
    setDynamicImage(avatarBitmap, "avatar_key")
}

svgaView.bind(MainApplication.svgaLoader, request, dynamic)
```

#### 页面销毁与列表复用
```kotlin
// 在 Activity/Fragment 销毁，或 RecyclerView ViewHolder 解绑复用时：
svgaView.clearRequest()

// 注意：此处切勿调用 MainApplication.svgaLoader.close()！
```

#### 高级：不使用 View，纯代码加载与租约持有
```kotlin
// 自行调用 loader.load 拿到的 ResourceLease 属于调用方
val cancelHandle = MainApplication.svgaLoader.load(request) { result ->
    result.fold(
        onSuccess = { lease ->
            try {
                val entity = lease.value // 获取 SVGAVideoEntity
                // 使用期间保持 lease 打开...
            } finally {
                // 使用完毕必须关闭 lease！严禁直接调用 entity.clear()！
                lease.close()
            }
        },
        onFailure = { error -> /* 处理错误 */ }
    )
}
// 取消尚未交付的任务（已交付的 lease 仍需自行 close）
cancelHandle.cancel()
```

#### URL 磁盘预取
```kotlin
// 在列表滚动或动画展示前预热磁盘（不初始化 Bitmap 与音频，主线程回调）
val cancelPrefetch = SVGAParser(context).prefetchToDisk(url) { result ->
    result.onSuccess { fileOrDir -> /* 磁盘预热就绪 */ }
}
```

---

### 3. 从官方 SVGAPlayer (`com.opensource.svgaplayer`) 迁移

存量项目有两个迁移选择：**0 代码改动极速平替（`svga-compat`）** 或 **彻底现代化重构（`svga-runtime`）**。

#### 路线 0：极速无感平替（强烈推荐，0 行代码与 XML 修改）
* **依赖**：`dev.svganext:svga-compat:0.1.0`
* **适用场景**：希望在几个小时内快速上线止血、不想重构几十个布局与业务类、或者项目集成了硬编码旧包名的**闭源第三方 SDK**。
* **接入方式**：
  在工程根 `build.gradle.kts` 中配置 Gradle 依赖替换规则（Dependency Substitution）：
  ```kotlin
  configurations.all {
      resolutionStrategy.dependencySubstitution {
          substitute(module("com.github.yyued:SVGAPlayer-Android"))
              .using(module("dev.svganext:svga-compat:0.1.0"))
              .because("一键无感升级到 SVGA-Next 高性能内核")
      }
  }
  ```
* **核心保障**：
  1. **完全保留旧包名**：保留 `com.opensource.svgaplayer.*`，所有 Java/Kotlin 的 `import` 与 XML 布局标签原封不动；
  2. **XML `app:source` 完美兼容**：老布局文件中的 `app:source="xxx.svga"` 照常自动解析并起播；
  3. **Safe-Clear 防崩拦截**：老代码中在 `onDestroy` 里调用的 `videoItem.clear()`，若该实体仍被其他活跃 View 共享在播，将自动被安全拦截，彻底杜绝并发 View 黑屏；
  4. **冷启动自动初始化**：内置 `SvgaInitProvider`，应用启动时自动初始化 `SVGACache` 与音频池，`Application` 代码零侵入；
  5. **享受新内核性能红利**：底层完全跑在 SVGA-Next 的 `FloatArray(11)` 零对象分配绘制、`KeyedFileLocks` 分段读写锁与原子发布机制上。

---

#### 路线 A：源码级渐进替换（仅替换底层内核）
* 依赖 `dev.svganext:svga-player:0.1.0`。
* 将代码中的 `com.opensource.svgaplayer` 全局批量替换为 `dev.svganext.player`。
* 保留原有的 `SVGAImageView` 与 `SVGAParser` 调用方式。
* **局限**：依然保留手动的资源释放逻辑，无法自动享受多 View 共享安全租约与请求并发合并。

#### 路线 B：全面升级 Runtime 架构（推荐用于新模块与深度重构）
* 依赖 `dev.svganext:svga-runtime:0.1.0`。
* 将 XML 中的 `SVGAImageView` 替换为 `ManagedSvgaImageView`，**并移除 XML 中的 `app:source`**，改用 `svgaView.bind(loader, request)`。
* 引入全局 `SvgaLoader` 单例，彻底移除业务层原本调用的 `videoItem?.clear()`，所有权交给租约管理。
* **收益**：从架构根源上消灭内存泄漏，支持直播间复杂动效多 View 共享相同解码资源。

完整迁移对比、Before & After 代码示例与详细避坑指南请查阅 [从官方 SVGAPlayer 迁移指南](docs/MIGRATION.md)。

---

### 4. 常用 API 与配置速查

| 类名 / 模块 | 核心 API / 属性 | 用途与说明 |
| :--- | :--- | :--- |
| `SvgaLoader` | `load(request, callback)` | 提交动画加载请求，命中缓存或完成解码后主线程交付 `ResourceLease` |
| `SvgaLoader` | `clearMemoryCache()` / `stats()` | 手动清空内存缓存；获取当前缓存条目数、已用字节与命中统计 |
| `SvgaLoader` | `close()` | 销毁加载器，取消在途任务并注销系统内存监听（仅在模块彻底销毁时调用） |
| `ManagedSvgaImageView` | `bind(loader, request, dynamic)` | 绑定加载器与请求，生命周期内自动安全持有与释放资源租约 |
| `ManagedSvgaImageView` | `clearRequest()` | 解除当前绑定并排队释放资源，用于页面退出或列表项复用 |
| `ManagedSvgaImageView` | `onLoaded` / `onLoadError` | 加载成功/失败的 Kotlin 函数式回调监听 |
| `SVGADynamicEntity` | `setDynamicText` / `setDynamicImage` | 动态替换文本与图片，每个 View 须使用独占实例 |
| `SVGASoundManager` | `init()` / `setVolume(volume)` | 初始化音频底层 SoundPool（有声动画必调）；设置全局音量 [0, 1] |
| `SVGACache` | `clearCache()` | 异步清空磁盘缓存目录（默认位于 `${cacheDir}/svga-next/`） |
| `SVGALogger` | `setLogEnabled(boolean)` | 控制底层日志输出开关 |
| `SVGAParser` | `prefetchToDisk(url, callback)` | 纯磁盘预热，并发共享单次下载传输，返回取消函数 |

## 源码边界与验证

官方基线固定为 `662d0dc11824e35a3f3fcd247f491efce2bf5ec5`，其原始 notices 保留在源码中，LICENSE 为 Apache-2.0。新增模块也使用 Apache-2.0。资源管理模块按明确的所有权和请求契约独立实现。性能结论应以本项目的实际验证为准。

当前实现使用 FloatArray(11) 打包帧数据并复用绘制矩阵，不代表整条绘制路径零分配。

验证结果见 [VALIDATION](docs/VALIDATION.md)。首次公开发布之前应补充目标设备上的真实动画、耗时和内存测量；首版测试不等于所有历史 SVGA 内容都已验证。

贡献方式见 [CONTRIBUTING](CONTRIBUTING.md)。
