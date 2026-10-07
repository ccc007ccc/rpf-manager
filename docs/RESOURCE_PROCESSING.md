# RPF 解密后的资源处理

公开版保留 Android 与 Linux 共用的完整资源处理实现，包括以下已存在的兼容逻辑。密钥输入边界见 [KEYS.md](KEYS.md)。

| 处理 | 主要源码（相对 `rust-core/`） |
| --- | --- |
| RPF 目录、加密边界、嵌套包 | `rpf-archive/src/archive.rs`、`jni-bridge/src/store.rs` |
| RSC7 分段、指针解析与解压恢复 | `rpf-archive/src/resource.rs` |
| YTD / DDS、BC / ASTC、数组 / cube / mip | `rpf-archive/src/ytd.rs`、`texture_utils.rs`、`jni-bridge/src/dds.rs`、`astc_preview.rs` |
| YDR / YDD / YFT、UV、YMAP / YBN | `rpf-archive/src/ydd.rs`、`ymt.rs`、`jni-bridge/src/mesh.rs`、`store.rs` |
| AWC / PCM / ADPCM | `jni-bridge/src/audio.rs` |
| 转换、分页修复、共享 GPU 数据、体积估算 | `jni-bridge/src/transcode.rs`、`transcode/` |
| 递归解包打包、逐包发布和恢复 | `jni-bridge/src/conversion.rs`、`conversion_fd.rs`、`conversion/` |
| 有界内存保存、24-bit 资源偏移、嵌套修改和撤销 | `jni-bridge/src/editor.rs` |
| 解析报告及未展开包的统计 | `jni-bridge/src/audit.rs` |

## RSC7 与纹理约束

RSC7 解包使用标准 raw-DEFLATE。除精确长度的 8 字节前缀恢复外，支持压缩体被
文本模式扩展的恢复：仅当所有 LF 都带前置 CR，才尝试 CRLF → LF；必须匹配
页标志声明的长度、完整结束标记且无尾随输入。正常二进制换行优先按原样解码，
不会对任意资源全局替换；截断或无法确定的数据仍失败，不填充假纹理。
压缩候选先于等长 raw 回退，避免合法前缀压缩体恰好等长时被误当原始段。

PC 资源每页至少 8 KiB；旧转换器可能生成512B/1KiB尾页，导致游戏页表构造退出。
仅此分页问题可用 `./linux/rpf-tool convert /path/to/GTA-ASTC --repair-pages --in-place` 修复：
同步system/graphics段长度、页图计数与metadata链，保留全部纹理payload，不重新编码ASTC。
报告 `repairPages=true`、`repairedResources` 给出修复数；`converted=0` 表示没有纹理转码，`complete`仅表示修复成功，不要求零BC。
合法资源及重复修复保持不变；这不能恢复旧解码器已丢失的颜色/透明度信息。

默认静态纹理 ASTC 4×4 thorough；更高质量档增加搜索量，不改变块字节数，
也不能消除所有 alpha/法线误差。使用者可选择更大块，按画质与体积取舍。
动态纹理不适合 ASTC：`script_rt_*` 会成为渲染目标，`pedmugshot_*` 会直接写入 BC 块。
工具继续保留并报告它们；处理剩余动态 BC 格式需要游戏运行端提供兼容支持。

ASTC 资源使用私有 `AS + 块宽 + 块高` 格式标记，只能给实现该标记的定制游戏端
使用。标准 GTA / 标准 DXVK 不能加载。GPU 纹理数据减少不等于 RPF 同比例变小；本实现保留原资源
虚拟地址与页面布局，以避免破坏模型 / 材质指针。

## 嵌套包与保存

未压缩、未加密的嵌套包共享只读映射；其他嵌套包在限制内解码。保留原始名称供 NG 选择密钥。编辑使用独立变更记录，保存把嵌套修改逐层带回顶层，写出 OPEN RPF7。流式写入按 256 KiB 缓冲处理，资源头和完整 24-bit 偏移保留。

转换保留非纹理资源、未知格式及明确不可转换的纹理，并在报告中给出原因。严格零 BC 模式与允许部分转换是两种明确的结果，不能把停止、失败或未展开的嵌套包当作成功。发布输出前重新读取验证；源文件保护、取消、恢复日志仍保留。

格式支持及预览边界见根 README；源码和合成回归是公开版本的可复现依据，历史私有环境中的游戏实测记录不作为公开版本的验收声明。
