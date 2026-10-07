# 用户密钥接口

本仓库提供 AES-256 和 NG 通用解密、RPF 目录解析及资源处理，不提供游戏密钥、解密数据表、TFIT 对象文件或转译实现。编译不需要密钥。不要把自己的游戏程序、密钥、加密资源或签名文件提交到仓库。

## 输入格式

Android：设置 → 导入密钥目录。目录须包含下列三个二进制文件，应用在本地读取，保存到 `noBackupFilesDir`，可在设置中移除。

| 文件 | 长度 | 布局 |
| --- | ---: | --- |
| `gtav_aes_key.dat` | 32 字节 | AES-256 密钥 |
| `gtav_ng_key.dat` | 27,472 字节 | 101 个 NG 密钥，每个 272 字节，使用导出工具的标准 NG 顺序 |
| `gtav_ng_decrypt_tables.dat` | 278,528 字节 | 17 × 16 × 256 个 little-endian u32 |

这些是用户自行准备的提取结果，不是直接把任意 RPF 或游戏安装目录改名。此版本没有 EXE 提取入口，也不分发提取结果。单独的 RPF 通常不足以获得所需密钥。

Linux CLI：`./linux/rpf-tool --keys /path/to/keys extract /path/to/archive.rpf /path/to/new-directory`。
Linux GUI：`./linux/gui.sh --keys /path/to/keys`。同一进程的扫描、解包、保存、修复及转换使用同一组用户密钥。

## 对资源处理的影响

- 未加密 / OPEN 资源包、原创示例、独立纹理和资源处理不需要密钥。
- 加密顶层包和嵌套包缺少密钥时明确失败；扫描报告区分无法展开的包，不虚构包内成功数量。
- 保存修改后的 RPF7 写出 OPEN 包；原始加密文件的内容必须先用导入的密钥解密，不能原样当作明文写出。
- NG 的正常选择依据资源名称和长度。目录不满足结构校验时，尝试用户提供的最多 101 个 NG 密钥，保留特殊嵌套目录的兼容处理；条目 payload 仍按各自名称和长度解密。
- 显式 NG key index 指用户 `.dat` 中的顺序，不能直接当成其他 TFIT 实现的内部编号。
- 密钥文件长度正确不代表版本匹配。合成测试不证明与任意游戏发行版的兼容性；RPF8 / RDR2 TFIT 仍不支持。

主要入口：`rpf-archive/src/crypto/keys.rs` 读取数据，`crypto/cipher.rs` 执行通用解密，`archive.rs` 解码目录和资源，`jni-bridge/src/editor.rs` 处理流式保存。所有路径均位于 `rust-core/` 下。
