# 资源管理设计与独立实现

本项目保留官方播放内核，将资源管理与业务接入分开。新增代码按明确的资源所有权和请求契约独立实现。

| 设计机制 | 本项目中的实现 | 首版边界 |
|---|---|---|
| 缓存引用与客户端引用分离 | ResourceLease + ByteSizedCache | 使用按字节计量的 LRU，并保护活跃客户端 |
| 按 Bitmap 实际字节计量 | estimatedMemoryBytes | 加入保留的 encoded bytes；仍非完整进程内存 |
| 缓存淘汰不能破坏播放客户端 | 缓存仅关闭 owner lease | 活跃 client 可超过缓存预算 |
| 请求身份和活动状态检查 | GenerationGate + Managed View 的绑定代次 | 旧结果释放，不安装到新绑定 |
| 同资源请求复用 | loader in-flight key | key 包含采样尺寸；不是业务下载队列 |
| attach / visibility 控制 | ManagedSvgaImageView | 释放自己的播放和资源引用 |
| 核心库与业务接入分开 | core / player / runtime / sample | 不依赖具体应用的业务代码或私有组件 |
| 按帧数组和原 protobuf 释放 | 首版保留官方数据结构 | 需要渲染与内存实测后再作为独立变更 |

官方已具备 matte、动态内容、sprite pool、共享 Paint/Matrix 和绘制缓存，首版保留这些能力。位图采样和音频兼容行为继续由播放内核负责，资源管理模块集中处理所有权、请求和生命周期。

本项目的性能声明应来自自身的基准和验证。缓存结构和请求合并机制的存在，不等于已经完成实际设备的性能测量。
