# 官方来源与衍生代码

- Repository: https://github.com/svga/SVGAPlayer-Android
- Pinned commit: `662d0dc11824e35a3f3fcd247f491efce2bf5ec5`
- License: Apache-2.0
- Imported scope: `library/src/main`，以及示例中的 heartbeat、matteRect、jojo_audio 资源。
- Original file hashes: [upstream-snapshot.json](upstream-snapshot.json)

播放器内核来自这份官方快照。源文件中的作者 notices 保留；整个项目附带原始 Apache-2.0 LICENSE 和来源 NOTICE。上游仓库已经停止维护，但源码仍可以在其许可证范围内阅读和派生。

首版修改包括：

1. 将包名统一改为 `dev.readablesvga.player`，使它可与旧库并存。
2. 更新 Android 构建、namespace、Java toolchain 和 consumer 混淆配置。
3. 调整共享实体的播放状态边界，将音频 stream ID 放到 Drawable 的独立播放状态。
4. 增加资源大小估算以及安全的实体准备/释放行为。
5. 收敛解析线程数量，补流/Inflater 生命周期和准备回调释放。
6. 内嵌位图解码失败后才尝试文件名回退；读取字节头后再为音频复制数据。
7. Managed View 可关闭原 XML source 的自动解析入口，确保加载经过 lease 管理。
8. 使用独立磁盘缓存目录 `readable-svga`，避免与同应用中的旧 SVGA 库同时写同一缓存。
9. 停止或替换播放不再触发自然完成通知；每 Drawable 单独保存 antiAlias 和音频 stream 状态。
10. 动画时长使用 Double/Long，避免 60 fps 的整数截断和长序列溢出。系统动画 scale 为 0 时展示范围终帧、完成一次，不创建 Animator，也不修改宿主全局设置；正 scale 继续补偿以保持媒体的帧率时间。
11. 错误回调支持保留异常 cause 的重载；文件在工作线程打开，网络取消使用线程安全标记、超时及断开连接。

`SVGADrawable.clear()` 只清当前播放；共享实体的最终 owner/lease 才能调用 `SVGAVideoEntity.clear()`。这项所有权语义与原官方直接清实体的行为有区别，接入时优先使用 runtime。

超出命名空间搬迁的逐文件差异见 [upstream-changes.patch](upstream-changes.patch)。可用 `python3 tools/upstream_diff.py /path/to/pinned/upstream` 重新生成；新的独立资源管理代码位于 core/runtime 模块，不在这份上游 diff 中。

`svga-core` 和 `svga-runtime` 是本项目独立实现的新代码。这两个模块按本项目的资源所有权、缓存和请求契约编写。

上游代码结构及 API 改动应通过本项目测试和真实动画验证，不能假设“沿用官方”就自动正确。直接使用 player 的实体与 Drawable API 时，调用方负责实体生命周期；推荐通过 runtime 的 lease 和 ManagedSvgaImageView 接入。
