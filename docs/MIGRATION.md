# 从官方 SVGAPlayer 迁移指南

本文档面向此前已经在项目中接入过官方 `com.opensource.svgaplayer`（或其历史衍生分支）的开发者。

> [!CAUTION]
> **重要前置说明：SVGA-Next 无法“无感升级”**  
> 由于包名命名空间变更、底层资源所有权模型重构、XML 自动加载机制调整以及播放回调时序修正，**任何存量项目都需要进行代码适配与回归排查**。直接替换依赖而不修改业务代码会导致编译错误或运行时异常。

---

## 为什么要迁移到 SVGA-Next？

官方 SVGAPlayer-Android 仓库（`com.opensource.svgaplayer`）已长期停更，在复杂业务场景（如直播间连击、多礼物飞屏、复杂动效列表）中存在多个长期痛点：

1. **频繁分配与 GC 卡顿**：官方版本每帧为每个精灵分配独立的 `Matrix` 与 `SVGARect` 对象，高帧率动画下产生海量短命对象，触发频繁 GC。
2. **全局文件大锁导致主线程卡死**：官方在磁盘 I/O 处使用了全局大锁（`synchronized(fileLock)`），同一时间多个动画解压或读取时相互阻塞，极易引发 ANR。
3. **缓存损坏与半截文件**：官方写磁盘缓存没有原子发布机制，下载中途杀进程或网络中断会留下损坏的半截缓存，导致后续读取永久失败。
4. **共享资源与内存泄漏两难**：
   - 多个 View 共享同一个 `SVGAVideoEntity` 时，若某个 View 退出时调用了 `videoEntity.clear()`，其他在播 View 会直接崩溃（`Canvas: trying to use a recycled bitmap`）。
   - 若不手动清理，官方的静态缓存机制又极易引发进程内存暴涨与 OOM。

**SVGA-Next 的核心收益**：
- **`FloatArray(11)` 紧凑数据帧**：消除每帧矩阵与矩形对象分配，Canvas 绘制共享变换矩阵。
- **细粒度 `KeyedFileLocks`**：不同资源解压互不阻塞，消除并发 I/O 瓶颈。
- **临时文件原子发布（`publishCacheFile`）**：杜绝半截损坏缓存文件。
- **`ResourceLease` 共享租约与字节 LRU 缓存**：在播动画受活跃引用保护，退出时引用归零自动安全释放，彻底解耦 View 与资源的生命周期。

---

## 破坏性改动清单 (Breaking Changes)

在开始迁移前，请务必了解以下行为差异：

| 变更项 | 官方 SVGAPlayer (`com.opensource`) | SVGA-Next (`dev.svganext`) | 影响与改动要求 |
| :--- | :--- | :--- | :--- |
| **Maven 坐标与包名** | `com.github.yyued:SVGAPlayer-Android` / `com.opensource.svgaplayer` | `dev.svganext:svga-runtime` / `dev.svganext:svga-player` | 必须全局替换 `import` 语句与 XML 标签。 |
| **资源清理机制** | 手动调用 `SVGAVideoEntity.clear()` 释放位图 | 严禁手动调用 `entity.clear()`，由 `ResourceLease` 自动管理 | **高危**：老业务中的 `videoItem?.clear()` 必须全部删除，否则会导致其他共享 View 崩溃。 |
| **`Drawable.clear()` 语义** | 会级联清理或重置播放资源 | 仅清除当前 Drawable 的播放帧状态，不触碰共享 Entity | 属于正常安全加固，不影响单 View 使用。 |
| **XML `app:source`** | 支持 XML 声明文件并自动异步解析 | `ManagedSvgaImageView` **强制禁用自动解析** | 若在 XML 中配置了 `app:source`，升级后**动画不会显示**，必须改用代码 `bind()`。 |
| **`onFinished` 回调时序** | 主动 `stopAnimation()` 或 Detach 取消时偶发误触发 | **仅在自然播放到最后一帧完成时触发** | 检查业务是否误依赖了主动 stop 时触发的 onFinished。 |
| **音频初始化** | 部分老版本内置或需单独初始化 | 必须显式调用 `SVGASoundManager.init()` | 若未初始化，解码器默认关闭音频解析，导致动画完全静音。 |
| **磁盘缓存目录** | `${cacheDir}/` 根目录或默认哈希目录 | 隔离在 `${cacheDir}/svga-next/` | 旧版本下载过的网络动画**无法直接被新版识别为命中**，会触发一次重新下载。 |

---

## 迁移路线选择

SVGA-Next 提供了两条迁移路径，业务可根据改动预算进行选择：

```
                    ┌───────────────────────────────┐
                    │       选择你的迁移路线        │
                    └───────────────┬───────────────┘
                                    │
           ┌────────────────────────┴────────────────────────┐
           ▼                                                 ▼
【路线 A：渐进式内核替换】                         【路线 B：完整升级 Runtime（强烈推荐）】
* 改动量：极小（仅改包名与依赖）                   * 改动量：中等（改用 Managed View 与 Loader）
* 依赖：svga-player                                * 依赖：svga-runtime
* 保留：原有 SVGAParser + SVGAImageView 代码       * 彻底解决：内存泄漏、多 View 共享安全、请求防重
* 获得：绘制零分配、分段锁防卡顿、原子文件防损坏    * 架构：现代化 Application 单例 Loader + 自动租约管理
```

---

### 路线 A：渐进式内核替换（最低改动成本）

适用于希望以最小代码改动快速解决官方库“绘制 GC 卡顿”、“主线程 I/O 死锁”和“损坏缓存”的项目。

#### 1. 替换依赖
移除旧版依赖，引入 `svga-player`：
```kotlin
// build.gradle.kts
dependencies {
    // 移除: implementation("com.github.yyued:SVGAPlayer-Android:x.x.x")
    implementation("dev.svganext:svga-player:0.1.0")
}
```

#### 2. 全局替换包名
使用 IDE 全局将 `com.opensource.svgaplayer` 替换为 `dev.svganext.player`：
- 代码中：
  - `com.opensource.svgaplayer.SVGAImageView` → `dev.svganext.player.SVGAImageView`
  - `com.opensource.svgaplayer.SVGAParser` → `dev.svganext.player.SVGAParser`
  - `com.opensource.svgaplayer.SVGACallback` → `dev.svganext.player.SVGACallback`
  - `com.opensource.svgaplayer.SVGADynamicEntity` → `dev.svganext.player.SVGADynamicEntity`
- XML 布局中：
  ```xml
  <!-- 修改前 -->
  <com.opensource.svgaplayer.SVGAImageView ... />

  <!-- 修改后 -->
  <dev.svganext.player.SVGAImageView ... />
  ```

#### 3. 路线 A 收益与局限
- **收益**：立即获得帧数据扁平化性能提升、分段读写锁优化及原子文件发布。
- **局限**：依然没有生命周期感知与租约保护，不能安全支持多个 View 共享同一个 `SVGAVideoEntity`，依然需要手动防范内存溢出。

---

### 路线 B：全面升级至 Runtime 架构（强烈推荐）

适用于希望从根本上根治 SVGA 内存泄漏、彻底实现多 View 共享动画（如直播间聊天区+公屏+全屏动效复用相同资源）的项目。

#### 1. 替换依赖
引入包含生命周期管理的 `svga-runtime`（自动传递依赖 `svga-player` 和 `svga-core`）：
```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.svganext:svga-runtime:0.1.0")
}
```

#### 2. 初始化 Application 单例
在应用的 `Application` 中声明全局 `SvgaLoader` 单例，并初始化音频管理器：
```kotlin
class MainApplication : Application() {
    companion object {
        lateinit var svgaLoader: SvgaLoader
            private set
    }

    override fun onCreate() {
        super.onCreate()
        // 1. 初始化音频池（若有音频动画必配）
        SVGASoundManager.init()

        // 2. 初始化全局加载器（默认分配 32MB 内存预算）
        val config = SvgaConfig(memoryCacheBytes = 64L * 1024 * 1024)
        svgaLoader = SvgaLoader(this, config)
    }
}
```

#### 3. 修改 XML 布局
将 `SVGAImageView` 改为 `ManagedSvgaImageView`，**并移除 `app:source` 属性**：
```xml
<!-- 修改前 -->
<com.opensource.svgaplayer.SVGAImageView
    android:id="@+id/svgaView"
    android:layout_width="200dp"
    android:layout_height="200dp"
    app:autoPlay="true"
    app:source="gift.svga" />

<!-- 修改后 -->
<dev.svganext.runtime.ManagedSvgaImageView
    android:id="@+id/svgaView"
    android:layout_width="200dp"
    android:layout_height="200dp"
    app:autoPlay="true"
    app:loopCount="0"
    app:fillMode="Forward" />
```

#### 4. 代码加载与清理对比

##### 🔴 旧版官方写法（改动前）：
```kotlin
// 旧版：自己创建解析器，手动管理解码与清理
val parser = SVGAParser(context)
parser.decodeFromAssets("gift.svga", object : SVGAParser.ParseCompletion {
    override fun onComplete(videoItem: SVGAVideoEntity) {
        svgaView.setVideoItem(videoItem)
        svgaView.startAnimation()
    }
    override fun onError() {}
})

// 旧版在退出页面时往往手动清理，极易导致并发 crash
override fun onDestroy() {
    svgaView.stopAnimation(true)
    currentVideoItem?.clear() // 危险！若其他 View 正在播放相同资源会直接崩溃
    super.onDestroy()
}
```

##### 🟢 新版 Runtime 写法（改动后）：
```kotlin
// 新版：通过 SvgaRequest 绑定全局 loader
val request = SvgaRequest(
    source = SvgaSource.Asset("gift.svga"),
    width = 200.dp,  // 明确采样尺寸；传 0 为原始尺寸
    height = 200.dp
)

// 动态图文替换（可选）
val dynamic = SVGADynamicEntity().apply {
    setDynamicText("张三 送出火箭", textPaint, "nickname_key")
}

// 绑定并自动触发异步加载与播放
svgaView.bind(MainApplication.svgaLoader, request, dynamic)

// 页面销毁或列表复用时清理绑定：
override fun onDestroy() {
    // 释放 View 对当前资源的租约；底层由 Loader 引用计数自动决定何时释放 Bitmap
    svgaView.clearRequest()
    super.onDestroy()
    // 严禁在此处调用 currentVideoItem?.clear()！
    // 严禁在此处调用 MainApplication.svgaLoader.close()！
}
```

---

## 存量代码排查避坑 Checklist

迁移过程中，请对照以下核对清单进行全项目扫描：

- [ ] **全局搜查 `com.opensource.svgaplayer`**：确保代码 import 和 XML 中无任何残留。
- [ ] **全局搜查 `.clear()` 重点排查 `SVGAVideoEntity`**：将业务层所有手动调用的 `videoItem?.clear()` 全部删除。
- [ ] **排查 XML 中的 `app:source`**：检查所有布局文件，若使用了 `ManagedSvgaImageView`，必须删除 `app:source` 并改在 Kotlin/Java 中调用 `view.bind()`。
- [ ] **检查 `onFinished()` 业务逻辑**：确认业务回调中没有依赖“调用 `stopAnimation()` 时也会触发 `onFinished`”的旧版 Bug。
- [ ] **确认音频初始化**：在 `Application.onCreate` 中已添加 `SVGASoundManager.init()`。
- [ ] **检查采样尺寸一致性**：确认相同动画在不同页面请求的 `width/height` 是否尽量统一，避免因采样尺寸差异造成内存缓存重复。
