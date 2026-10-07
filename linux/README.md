# Linux RPF / ASTC 工具

读取 GTA Legacy PC 的 RPF7，递归处理嵌套 RPF 和资源里的纹理字典。
提供解压、打包、格式统计与 BC → ASTC 批量转换。原始 mip、数组 / cube 层、
sRGB 标记和非纹理资源保留；BC1–5、BC7 LDR 可转换，BC6H HDR、体积贴图、
运行时渲染目标和未知格式会保留并计入报告。

## 构建与运行

配置工具链后运行以下命令。SteamOS 使用现有 `dev` Distrobox，普通 Linux 使用当前环境；详见 [构建说明](../docs/BUILDING.md)。

```bash
./linux/build.sh
./linux/gui.sh
./linux/rpf-tool --help
./linux/rpf-tool --keys /path/to/keys extract /path/to/x64c.rpf /path/to/extracted
./linux/rpf-tool pack /path/to/extracted /path/to/repacked.rpf
./linux/rpf-tool --keys /path/to/keys convert /path/to/GTA --dry-run --keep-going
./linux/rpf-tool --keys /path/to/keys convert /path/to/GTA --output /path/to/GTA-ASTC --require-no-bc
```

图形界面可选择 GTA 目录、覆盖 / 副本模式、压缩块、编码质量和并行数，也可单独
解压 / 打包 RPF。扫描按钮只读；覆盖操作需要在窗口内确认。界面显示逐包进度、
转换数量和错误，并可停止任务。停止后当前原包不替换，已完成包保留；新目录
可能是不完整副本。GUI 依赖 Python Tk（Arch 软件包 `tk`、Ubuntu 软件包 `python3-tk`）。加密包使用 `./linux/gui.sh --keys /path/to/keys`；缺少密钥会明确报错。
默认 4×4 / thorough，Linux GUI 默认要求零残留 BC；严格模式有保留 BC 或资源错误时，
当前顶层包不会发布。取消严格选项才能生成部分转换包，界面会明确显示未完整转换。

使用合成资源验证图形界面流程：

```bash
xvfb-run -a -s '-screen 0 1280x960x24' python3 linux/test_gui.py --tool build/linux-astc/bin/rpf-tool
```

`--output` 必须是不存在的新目录，并位于输入目录之外。默认处理目录下所有 RPF，
副本模式也复制其他游戏文件。`--archive x64a.rpf` 可用于小规模试转；这种模式只
输出选定包。`--jobs 4` 控制资源级并行数；`--samples DIR` 输出限量 BC / ASTC PNG
与 `.astc` 预览，并为所有转换纹理的每个 mip / layer 写 `quality.jsonl`，记录原始
RGBA 误差及黑白背景合成误差。透明纹理的原始 RGBA PSNR 会把透明像素的 RGB 也计入，
应同时检查合成指标和预览图。ASTC 属于有损编码，法线、文字和透明边缘仍需检查。
编码默认关闭 alpha-weighted RGB 优化；未知材质的 alpha 不等于透明度，不能丢弃低 alpha RGB。
BC1 保留一位透明度，BC2/3 使用独立 alpha 与固定四色规则，不预乘或反预乘通道。

覆盖模式逐个顶层 RPF 写入同目录临时文件，重新打开并校验后原子替换该包。
发生错误时当前原包保持不变；此前成功处理的包会保留。已转 ASTC 不再次压缩，
因此覆盖模式可重新运行。副本模式遇到错误可能留下已完成的包，重试须用新目录。
`--keep-going` 保留不能解析 / 转码的原始资源，写入错误清单；不指定则严格停止。
完成时在输出目录写 `rpf-astc-report.json`（工具保留文件名，新副本会重建此报告）；
`finished` 代表任务结束；真实转换有资源错误或剩余 BC 时 `complete=false`。
`--dry-run` 不改游戏目录，其 `complete` 只表示解析完成；应检查 `blockedBc` 和 `errors`
判断已知转换阻挡。`remainingBc` 是扫描输入/转换输出的当前 BC 数量，`convertibleBc` /
`blockedBc` 是源 BC 的分类，`blockedBcSamples` 给出至多 16 个定位样本。
原有非 BC 格式不妨碍 `complete=true`，所以 complete 仍不等于每张纹理都是 ASTC。
CLI 未指定 `--require-no-bc` 时允许有意保留的部分转换；自动化必须检查报告或使用严格选项。
Android 文件描述符入口提供相同的严格 / 部分转换策略、分页修复、参数、分类与完成状态报告。

RSC7 解压恢复、页表修复、动态纹理和私有 ASTC 标记的约束见 [资源处理说明](../docs/RESOURCE_PROCESSING.md)。

## 只保留游戏最低档的 ASTC mip

对已经转换的 ASTC 目录使用独立命令，不向原版 BC 目录运行：

```bash
./linux/rpf-tool trim-mips /path/to/GTA-ASTC --dry-run --archive x64a.rpf --report /path/to/plan.json
./linux/rpf-tool trim-mips /path/to/GTA-ASTC --in-place --profile gta-minimum-v1 --jobs 4 --report /path/to/trim.json
```

`gta-minimum-v1` 对应游戏菜单最低纹理档（内部 MEDIUM）：静态 ASTC 完整 mip 链
乘层数至少 4096 字节、宽高可被 2 整除、下一层宽高至少 4 且仍有 mip 时，移除
最高一层。逐层直接复制现有 ASTC 块，不重新编码；保留 cube / array 层与 sRGB。
非 ASTC、动态 / RT、体积或特殊纹理、单 mip、小图及固定坐标颜色表
`mp_eye_colour.ytd` 保留。脚本、音频、地图模型和非纹理条目不删除。

已裁纹理改为私有 `AL + 块宽 + 块高` 标记，重复运行不会继续降级；游戏端须识别
AL 并停止再次按纹理档裁剪。每包发布前写入 `GTA-ASTC/rpf-mip-trim/`，记录规则、
原 / 新 SHA-256、资源路径及每张纹理尺寸、mip、指针、字节数变化；拷贝资源时保留
此目录。中断重跑据 SHA 判断已发布或待发布包；出现第三种 SHA 则拒绝覆盖。
汇总 `totals` 包含选中包的已有 journal，`currentRunTotals` 只统计本次实际处理，
因此续跑不会丢失前半批的条数；`alreadyAppliedArchives` 标明本次跳过的已完成包。
独占旧纹理范围清零后原址压缩；同布局且全部所有者都可裁的共享数据只写一次，
其他共享或未知 GPU 引用的纹理保留。RSC 段长度、页标志、页图和全部对象地址
保持不变。`reclaimedBytes` 是已清除的纹理字节，真实包收益看
`sourceBytes/outputBytes`，不等于总显存或整机画质验收。

## 自定义与体积预估

块尺寸支持全部 14 种合法 ASTC 2D 格式，包含 8×8、10×10、12×12；7×7 不合法。
GUI 提供常用档位、完整块尺寸下拉框及折叠高级设置。默认高级项关闭。
质量有 fastest 至 exhaustive 六个档位；自定义搜索强度 0–100 可覆盖命名档位。
RGBA 通道权重各支持 0.01–64；感知权重改变 RGB 误差分配。
透明颜色优化半径支持 0–16，仅适用于已确认 alpha 表示透明度的颜色资源，
法线、遮罩等数据资源保持关闭；选项作用于本次全部可转换纹理，不自动分类材质。

例如使用 8×8 和自定义搜索强度（其余高级项保持默认）：

```bash
./linux/rpf-tool --keys /path/to/keys convert /path/to/GTA --output /path/to/GTA-ASTC --block 8x8 --effort 75 --require-no-bc
```

`--channel-weights R,G,B,A`、`--alpha-weight-radius N`、`--perceptual` 见 `--help`。
报告 `encoderSettings` 同时记录请求参数和实际强度/权重，包括编码库的权重下限。
提高强度或调整权重不改变相同块尺寸的纹理字节数，也不保证每个通道都改善。

先扫描用户自己的资源，界面即显示所选块对应的原 BC / 预计 ASTC 字节与增减比例，
切换块无需重扫。路径变化会清除估算；目录内容被外部修改后须重新扫描。
CLI 扫描结果的 `summary.sizeEstimate.byBlock` 提供所有档位，按实际尺寸、全部 mip、
层数和完整块边界计算；只统计可转换 BC，同资源中相同布局的共享数据去重。
有资源解析错误时 `sizeEstimate.complete=false`，界面显示部分估算。
保留 BC、原有非 BC、页面填充和外层压缩不计入，因此不是 RPF 或总显存大小预测。
大图 8×8 相对 4×4 约少 75%，相对 BC1/4 约少 50%；12×12 分别约少 89% / 78%。
小图和尾 mip 按完整块计费，实际采用扫描数字。

## Android / Linux 共享核心

Linux 构建直接使用 `rust-core/jni-bridge`，通过 `desktop` feature 添加 CLI；默认的
`conversion` feature 也供 Android 使用。递归解包、RSC7 解压修复、纹理字典检查、
BC / ASTC 解码、转换、分页修复、体积估算和验证只有一份实现。

可用 `RPF_MANAGER_SOURCE`、`RPF_ASTC_BUILD_DIR` 覆盖源与构建目录。更新源码后运行 `./linux/build.sh` 重建。

AES／NG 数据通过 `--keys DIR` 提供，三个文件的格式见 [密钥接口](../docs/KEYS.md)。公开构建不包含内置游戏密钥或专有解密器。
