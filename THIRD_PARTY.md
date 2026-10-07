# 第三方来源与许可

本项目新增代码采用根目录 [MIT](LICENSE)。第三方代码保留各自来源、版权与许可；根许可证不替代第三方权利。

## 归档库

`rust-core/rpf-archive` 基于 [VIRUXE/rpf-rs v0.7.1](https://github.com/VIRUXE/rpf-rs/tree/42a35da47d75406d9bd7914eb80e7eecd84e9615)，固定提交 `42a35da47d75406d9bd7914eb80e7eecd84e9615`。该快照的 Cargo 元数据与 README 声明 MIT，没有独立 LICENSE 文件。本仓库保留这两处声明，并在 [归档库许可](rust-core/rpf-archive/LICENSE) 附 MIT 标准条款与来源，不把主项目作者当作上游作者。

本地修复包括 RSC7 边界和解压恢复、YTD mip / 层布局、纹理解码颜色顺序、Drawable 数据访问、RPF7 资源偏移、用户密钥目录重试。JNI 中的资源遍历、编辑、流式保存、音频、转换和预览实现位于本仓库。修改后的完整源码直接随项目发布，不依赖嵌套私有仓库。

## 解密模块与格式参考

公开版保留通用 AES / NG 解密和用户密钥文件接口。早期开发版本含有从本地 GTA 项目移植、再分发授权未确认的 TFIT 对象代码转译实现及生成的数据；这些实现、数据表和内置游戏密钥均不在此公开版本中。未使用的固定哈希提取器也已移除。

历史开发参考过本地 GTA 项目的碰撞 / 地图格式定义和 Vulkan 验证代码，用于理解坐标、压缩顶点和层级。查看器和着色器在本项目实现，公开版不链接或分发 GTA 引擎源码、对象文件或游戏资产。这一来源记录不构成对第三方材料的重新授权。

[CodeWalker](https://github.com/dexyfex/CodeWalker) 的 `Bounds.cs`、`Frag.cs`、`Drawable.cs`、`VertexType.cs`、`MetaTypes.cs`、`MetaNames.cs` 和 `AwcFile.cs` 用于交叉核对 PC 格式布局、顶点、UV、Meta 与音频分块。相关参考文件的 MIT 许可保留在本文末尾。

## 编码器与构建依赖

- `astcenc-sys = 0.2.0` 包含 Arm astc-encoder（Apache-2.0），通过公开 C API 调用；完整许可见 [ASTCENC.txt](licenses/ASTCENC.txt)。`astcenc-rs = 0.2.0` 仅用于兼容性测试。
- AndroidX 依赖采用 Apache-2.0；Kotlin、Gradle、Android SDK / NDK 及共享 C++ 运行库按其自身许可使用。原生运行库由用户安装的 NDK 提供，公开仓库不分发预编译 `.so`。
- Rust 依赖的固定版本见两个 Cargo.lock，完整许可随各依赖源码提供；发布二进制时须保留对应的版权与许可通知。
- `studio.rpf` 为本仓库程序生成的原创演示内容，生成器和测试不包含游戏资产、真实游戏密钥或解密数据表。

## CodeWalker 格式参考文件许可

Copyright (c) 2015 Neodymium

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
