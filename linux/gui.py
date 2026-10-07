#!/usr/bin/env python3
"""Folder-based desktop interface for the isolated rpf-tool worker."""
import argparse
import json
import math
import os
from pathlib import Path
import queue
import signal
import subprocess
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk


BLOCKS = ("4x4", "5x4", "5x5", "6x5", "6x6", "8x5", "8x6", "8x8", "10x5", "10x6", "10x8", "10x10", "12x10", "12x12")
PRESETS = {
    "4×4 · 画质优先（默认）": "4x4",
    "5×5 · 适度节省": "5x5",
    "6×6 · 较小容量": "6x6",
    "8×8 · 更小容量": "8x8",
    "10×10 · 低位率": "10x10",
    "12×12 · 最低位率": "12x12",
}
CUSTOM_PRESET = "自定义压缩块"
QUALITY = {"最快": "fastest", "快速": "fast", "均衡": "medium", "精细": "thorough", "更精细": "very-thorough", "穷尽搜索": "exhaustive"}


class App:
    def __init__(self, root, executable, keys=None):
        self.root, self.executable = root, Path(executable)
        self.key_args = ["--keys", str(keys)] if keys else []
        self.process = None
        self.events = queue.Queue()
        self.running = False
        self.cancelled = False
        self.last_report = None
        self.last_error = None
        self.converted = 0
        self.archive_index = 0
        self.archive_total = 1
        self.size_estimate = None
        self.estimate_partial = False
        self.input_revision = 0
        self.active_scan = None
        self.advanced_visible = False
        self.form_labels = []
        root.title("RPF Manager · Linux 纹理转换")
        root.geometry("920x760")
        root.minsize(860, 720)
        style = ttk.Style(root)
        style.theme_use("clam")
        style.configure("TFrame", background="#f4f6f8")
        style.configure("TLabel", background="#f4f6f8", foreground="#202938")
        style.configure("Title.TLabel", font=("sans", 20, "bold"))
        style.configure("Hint.TLabel", foreground="#556273")
        style.configure("TButton", padding=(12, 5))
        style.configure("Accent.TButton", background="#2764c5", foreground="white")
        style.configure("TRadiobutton", background="#f4f6f8")
        style.configure("TCheckbutton", background="#f4f6f8")
        shell = ttk.Frame(root, padding=18)
        shell.pack(fill="both", expand=True)
        ttk.Label(shell, text="为 GTA 准备 ASTC 纹理", style="Title.TLabel").pack(anchor="w")
        ttk.Label(shell, text="选择游戏目录，一次处理 RPF 和嵌套资源。转换结果用于支持 ASTC 的定制游戏。", style="Hint.TLabel").pack(anchor="w", pady=(6, 12))
        tabs = ttk.Notebook(shell)
        tabs.pack(fill="both", expand=True)
        convert_holder = ttk.Frame(tabs)
        scroll_area = ttk.Frame(convert_holder)
        scroll_area.pack(fill="both", expand=True)
        self.convert_canvas = tk.Canvas(scroll_area, height=280, width=1, highlightthickness=0, background="#f4f6f8")
        form_scroll = ttk.Scrollbar(scroll_area, orient="vertical", command=self.convert_canvas.yview)
        self.convert_canvas.configure(yscrollcommand=form_scroll.set)
        form_scroll.pack(side="right", fill="y")
        self.convert_canvas.pack(side="left", fill="both", expand=True)
        convert = ttk.Frame(self.convert_canvas, padding=16)
        self.convert_form = self.convert_canvas.create_window((0, 0), window=convert, anchor="nw")
        convert.bind("<Configure>", lambda _event: self.convert_canvas.configure(scrollregion=self.convert_canvas.bbox("all")))
        self.convert_canvas.bind("<Configure>", self.resize_convert_form)
        archive = ttk.Frame(tabs, padding=16)
        tabs.add(convert_holder, text="  转换纹理  ")
        tabs.add(archive, text="  解压 / 打包 RPF  ")
        for frame in (convert, archive):
            frame.columnconfigure(1, weight=1)
        self.input = tk.StringVar()
        self.output = tk.StringVar()
        self.mode = tk.StringVar(value="copy")
        self.block = tk.StringVar(value="4x4")
        self.preset = tk.StringVar()
        self.capacity = tk.StringVar()
        self.estimate_text = tk.StringVar()
        self.quality = tk.StringVar(value="精细")
        self.custom_effort_enabled = tk.BooleanVar(value=False)
        self.effort = tk.StringVar(value="98")
        self.channel_weights = [tk.StringVar(value="1") for _ in "RGBA"]
        self.alpha_weight_enabled = tk.BooleanVar(value=False)
        self.alpha_radius = tk.StringVar(value="0")
        self.perceptual = tk.BooleanVar(value=False)
        self.advanced_caption = tk.StringVar()
        self.jobs = tk.IntVar(value=min(4, os.cpu_count() or 1))
        self.keep_going = tk.BooleanVar(value=False)
        self.samples = tk.BooleanVar(value=False)
        self.path_row(convert, 0, "GTA 目录", self.input, self.choose_game)
        modes = ttk.Frame(convert)
        modes.grid(row=1, column=1, columnspan=2, sticky="w", pady=6)
        ttk.Radiobutton(modes, text="输出到新目录", variable=self.mode, value="copy", command=self.refresh_mode).pack(side="left")
        ttk.Radiobutton(modes, text="覆盖原资源", variable=self.mode, value="in-place", command=self.refresh_mode).pack(side="left", padx=20)
        self.output_entry, self.output_button = self.path_row(convert, 2, "输出目录", self.output, self.choose_output)
        settings = ttk.Frame(convert)
        settings.grid(row=3, column=0, columnspan=3, sticky="ew", pady=(12, 8))
        ttk.Label(settings, text="容量档位").pack(side="left", padx=(0, 6))
        self.preset_box = ttk.Combobox(settings, textvariable=self.preset, values=tuple(PRESETS), state="readonly", width=22)
        self.preset_box.pack(side="left", padx=(0, 12))
        self.preset_box.bind("<<ComboboxSelected>>", self.choose_preset)
        ttk.Label(settings, text="压缩块").pack(side="left", padx=(0, 6))
        self.block_box = ttk.Combobox(settings, textvariable=self.block, values=BLOCKS, state="readonly", width=7)
        self.block_box.pack(side="left", padx=(0, 12))
        ttk.Label(settings, text="编码搜索").pack(side="left", padx=(0, 6))
        self.quality_box = ttk.Combobox(settings, textvariable=self.quality, values=tuple(QUALITY), state="readonly", width=8)
        self.quality_box.pack(side="left", padx=(0, 12))
        ttk.Label(settings, text="并行数").pack(side="left", padx=(0, 6))
        ttk.Spinbox(settings, from_=1, to=64, textvariable=self.jobs, width=5).pack(side="left")
        self.form_label(convert, textvariable=self.estimate_text).grid(row=4, column=0, columnspan=3, sticky="w", pady=(0, 4))
        self.form_label(convert, textvariable=self.capacity).grid(row=5, column=0, columnspan=3, sticky="w", pady=(0, 4))
        self.form_label(convert, text="块越大更易损失细节；搜索越精细通常越慢，同一块的位率不变。估算计入实际 mip / 层 / 整块补齐。\n仅统计可转换 BC，不含已有 ASTC、未压缩和动态纹理，也不等于 RPF 或全部显存；资源变化后请重新扫描。",
                        style="Hint.TLabel").grid(row=6, column=0, columnspan=3, sticky="w", pady=(0, 8))
        ttk.Button(convert, textvariable=self.advanced_caption, command=self.toggle_advanced).grid(row=7, column=0, columnspan=3, sticky="w", pady=(0, 4))
        self.advanced_frame = ttk.Frame(convert, padding=(8, 6))
        self.advanced_frame.grid(row=8, column=0, columnspan=3, sticky="ew", pady=(0, 8))
        effort_row = ttk.Frame(self.advanced_frame)
        effort_row.pack(fill="x", pady=3)
        ttk.Checkbutton(effort_row, text="自定义搜索强度（覆盖上方预设）", variable=self.custom_effort_enabled).pack(side="left")
        self.effort_entry = ttk.Spinbox(effort_row, from_=0, to=100, increment=1, textvariable=self.effort, width=8)
        self.effort_entry.pack(side="left", padx=(8, 6))
        ttk.Label(effort_row, text="0 最快 → 100 穷尽搜索", style="Hint.TLabel").pack(side="left")
        weights_row = ttk.Frame(self.advanced_frame)
        weights_row.pack(fill="x", pady=3)
        ttk.Label(weights_row, text="RGBA 通道权重乘数").pack(side="left", padx=(0, 8))
        for channel, value in zip("RGBA", self.channel_weights):
            ttk.Label(weights_row, text=channel).pack(side="left", padx=(0, 4))
            ttk.Spinbox(weights_row, from_=0.01, to=64, increment=0.1, textvariable=value, width=6).pack(side="left", padx=(0, 10))
        ttk.Label(weights_row, text="各 0.01–64", style="Hint.TLabel").pack(side="left")
        alpha_row = ttk.Frame(self.advanced_frame)
        alpha_row.pack(fill="x", pady=3)
        ttk.Checkbutton(alpha_row, text="透明颜色优化", variable=self.alpha_weight_enabled).pack(side="left")
        ttk.Label(alpha_row, text="半径").pack(side="left", padx=(10, 4))
        self.alpha_radius_entry = ttk.Spinbox(alpha_row, from_=0, to=16, textvariable=self.alpha_radius, width=5)
        self.alpha_radius_entry.pack(side="left", padx=(0, 8))
        ttk.Label(alpha_row, text="0–16；仅透明颜色，法线 / 遮罩保持关闭", style="Hint.TLabel").pack(side="left")
        ttk.Checkbutton(self.advanced_frame, text="感知通道权重（先用感知基准，再乘 RGBA 权重）", variable=self.perceptual).pack(anchor="w", pady=3)
        ttk.Label(self.advanced_frame, text="完成后可在日志查看实际搜索强度和生效的通道权重。", style="Hint.TLabel").pack(anchor="w", pady=3)
        ttk.Button(self.advanced_frame, text="重置高级项", command=self.reset_advanced).pack(anchor="e", pady=(3, 0))
        self.advanced_frame.grid_remove()
        for variable in (self.custom_effort_enabled, *self.channel_weights, self.alpha_weight_enabled, self.perceptual):
            variable.trace_add("write", self.refresh_encoder_controls)
        self.refresh_encoder_controls()
        self.block.trace_add("write", self.refresh_block)
        self.input.trace_add("write", self.invalidate_estimate)
        self.refresh_block()
        ttk.Checkbutton(convert, text="允许部分转换，保留失败资源和不支持的 BC 纹理", variable=self.keep_going).grid(row=9, column=0, columnspan=3, sticky="w", pady=4)
        ttk.Checkbutton(convert, text="输出转换前后的 PNG 画质对照和逐 mip 质量记录", variable=self.samples).grid(row=10, column=0, columnspan=3, sticky="w", pady=4)
        footer = ttk.Frame(convert_holder, padding=(16, 8, 16, 12))
        footer.pack(side="bottom", fill="x", before=scroll_area)
        ttk.Label(footer, text="默认只写入无剩余 BC 的包。", style="Hint.TLabel").pack(side="left")
        actions = ttk.Frame(footer)
        actions.pack(side="right")
        self.scan_button = ttk.Button(actions, text="扫描并估算", command=lambda: self.start_conversion(True))
        self.scan_button.pack(side="left", padx=8)
        self.convert_button = ttk.Button(actions, text="开始转换", style="Accent.TButton", command=self.start_conversion)
        self.convert_button.pack(side="left")

        self.archive_mode = tk.StringVar(value="extract")
        self.archive_input, self.archive_output = tk.StringVar(), tk.StringVar()
        archive_modes = ttk.Frame(archive)
        archive_modes.grid(row=0, column=0, columnspan=3, sticky="w", pady=(0, 8))
        ttk.Radiobutton(archive_modes, text="解压 RPF（包含嵌套包）", variable=self.archive_mode, value="extract").pack(side="left")
        ttk.Radiobutton(archive_modes, text="把目录打包为 RPF", variable=self.archive_mode, value="pack").pack(side="left", padx=20)
        self.path_row(archive, 1, "输入", self.archive_input, self.choose_archive_input)
        self.path_row(archive, 2, "输出", self.archive_output, self.choose_archive_output)
        ttk.Label(archive, text="输出必须为新的文件或目录。资源内容与空目录会保留。", style="Hint.TLabel").grid(row=3, column=0, columnspan=3, sticky="w", pady=12)
        self.archive_button = ttk.Button(archive, text="开始处理", style="Accent.TButton", command=self.start_archive)
        self.archive_button.grid(row=4, column=2, sticky="e")

        self.status = tk.StringVar(value="准备就绪")
        self.summary = tk.StringVar(value="建议先扫描，了解资源格式与不能读取的文件。")
        status_row = ttk.Frame(shell)
        status_row.pack(fill="x", pady=(18, 6))
        ttk.Label(status_row, textvariable=self.status).pack(side="left")
        self.stop_button = ttk.Button(status_row, text="停止", command=self.stop, state="disabled")
        self.stop_button.pack(side="right")
        self.progress = ttk.Progressbar(shell, mode="determinate", maximum=1)
        self.progress.pack(fill="x", pady=5)
        ttk.Label(shell, textvariable=self.summary, style="Hint.TLabel", wraplength=780).pack(anchor="w", pady=(5, 10))
        log_frame = ttk.Frame(shell)
        log_frame.pack(fill="both", expand=True)
        self.log = tk.Text(log_frame, height=4, wrap="word", relief="flat", background="white", foreground="#354154", padx=10, pady=8, state="disabled")
        scroll = ttk.Scrollbar(log_frame, command=self.log.yview)
        self.log.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.log.pack(side="left", fill="both", expand=True)
        root.protocol("WM_DELETE_WINDOW", self.close)
        for event in ("<MouseWheel>", "<Button-4>", "<Button-5>"):
            root.bind(event, self.scroll_convert_form, add="+")
        root.after(80, self.poll)

    def form_label(self, frame, **options):
        label = ttk.Label(frame, wraplength=780, justify="left", **options)
        self.form_labels.append(label)
        return label

    def resize_convert_form(self, event):
        self.convert_canvas.itemconfigure(self.convert_form, width=event.width)
        for label in self.form_labels:
            label.configure(wraplength=max(250, event.width - 32))

    def scroll_convert_form(self, event):
        canvas = self.convert_canvas
        if not canvas.winfo_ismapped() or event.widget.winfo_class() in ("TCombobox", "TSpinbox"):
            return
        if not (canvas.winfo_rootx() <= event.x_root < canvas.winfo_rootx() + canvas.winfo_width()
                and canvas.winfo_rooty() <= event.y_root < canvas.winfo_rooty() + canvas.winfo_height()):
            return
        direction = -1 if event.num == 4 else (1 if event.num == 5 else (-1 if event.delta > 0 else 1))
        canvas.yview_scroll(direction * 3, "units")
        return "break"

    def path_row(self, frame, row, text, variable, choose):
        ttk.Label(frame, text=text).grid(row=row, column=0, sticky="w", padx=(0, 12), pady=4)
        entry = ttk.Entry(frame, textvariable=variable)
        entry.grid(row=row, column=1, sticky="ew", pady=4)
        button = ttk.Button(frame, text="选择…", command=choose)
        button.grid(row=row, column=2, padx=(10, 0), pady=4)
        return entry, button

    def choose_game(self):
        path = filedialog.askdirectory(title="选择 GTA Legacy PC 游戏目录")
        if path:
            self.input.set(path)
            self.output.set(str(Path(path).with_name(Path(path).name + "-ASTC")))

    def choose_output(self):
        parent = filedialog.askdirectory(title="选择新副本的存放位置")
        if parent:
            name = Path(self.input.get()).name or "GTA"
            self.output.set(str(Path(parent) / (name + "-ASTC")))

    def refresh_mode(self):
        state = "normal" if self.mode.get() == "copy" else "disabled"
        self.output_entry.configure(state=state)
        self.output_button.configure(state=state)

    def choose_preset(self, _event=None):
        block = PRESETS.get(self.preset.get())
        if block:
            self.block.set(block)

    def refresh_block(self, *_args):
        block = self.block.get()
        self.preset.set(next((label for label, value in PRESETS.items() if value == block), CUSTOM_PRESET))
        if block not in BLOCKS:
            self.capacity.set("请选择受支持的 ASTC 压缩块。")
            self.refresh_estimate()
            return
        width, height = (int(value) for value in block.split("x"))
        bits = 128 / (width * height)
        self.capacity.set(f"{bits:.2f} bit/像素 · 理论位率比（不含补齐）：4×4 的 {bits / 8:.1%} ｜BC1 的 {bits / 4:.1%} ｜BC3 的 {bits / 8:.1%}")
        self.refresh_estimate()

    def toggle_advanced(self):
        self.advanced_visible = not self.advanced_visible
        if self.advanced_visible:
            self.advanced_frame.grid()
        else:
            self.advanced_frame.grid_remove()
        self.refresh_encoder_controls()

    def refresh_encoder_controls(self, *_args):
        custom_effort = self.custom_effort_enabled.get()
        self.quality_box.configure(state="disabled" if custom_effort else "readonly")
        self.effort_entry.configure(state="normal" if custom_effort else "disabled")
        self.alpha_radius_entry.configure(state="normal" if self.alpha_weight_enabled.get() else "disabled")
        try:
            weighted = any(float(value.get()) != 1 for value in self.channel_weights)
        except ValueError:
            weighted = True
        custom = custom_effort or weighted or self.alpha_weight_enabled.get() or self.perceptual.get()
        self.advanced_caption.set(("▼ " if self.advanced_visible else "▶ ") + "高级编码设置" + (" · 已自定义" if custom else ""))

    def reset_advanced(self):
        self.custom_effort_enabled.set(False)
        self.effort.set("98")
        for value in self.channel_weights:
            value.set("1")
        self.alpha_weight_enabled.set(False)
        self.alpha_radius.set("0")
        self.perceptual.set(False)

    @staticmethod
    def bounded_number(text, label, lower, upper):
        try:
            value = float(text)
        except ValueError:
            raise ValueError(f"{label}须为 {lower:g} 到 {upper:g} 的数值。") from None
        if not math.isfinite(value) or not lower <= value <= upper:
            raise ValueError(f"{label}须为 {lower:g} 到 {upper:g} 的有限数值。")
        return value

    def encoder_arguments(self):
        if self.custom_effort_enabled.get():
            effort = self.bounded_number(self.effort.get(), "搜索强度", 0, 100)
            args = ["--effort", str(effort)]
        else:
            quality = QUALITY.get(self.quality.get())
            if quality is None:
                raise ValueError("请选择编码搜索预设。")
            args = ["--quality", quality]
        weights = [self.bounded_number(value.get(), f"{channel} 通道权重", 0.01, 64)
                   for channel, value in zip("RGBA", self.channel_weights)]
        if any(value != 1 for value in weights):
            args += ["--channel-weights", ",".join(str(value) for value in weights)]
        if self.alpha_weight_enabled.get():
            try:
                radius = int(self.alpha_radius.get())
            except ValueError:
                raise ValueError("透明颜色优化半径须为 0 到 16 的整数。") from None
            if not 0 <= radius <= 16:
                raise ValueError("透明颜色优化半径须为 0 到 16 的整数。")
            args += ["--alpha-weight-radius", str(radius)]
        if self.perceptual.get():
            args.append("--perceptual")
        return args

    def clear_estimate(self):
        self.size_estimate = None
        self.estimate_partial = False
        self.refresh_estimate()

    def invalidate_estimate(self, *_args):
        self.input_revision += 1
        self.clear_estimate()

    def cache_estimate(self, report):
        if not report.get("dryRun") or self.active_scan is None:
            return
        source, revision = self.active_scan
        try:
            current = str(Path(self.input.get()).expanduser().resolve())
            reported = str(Path(report.get("input", "")).resolve())
        except (OSError, ValueError):
            return
        estimate = report["summary"].get("sizeEstimate")
        if source != current or reported != current or revision != self.input_revision:
            return
        if not isinstance(estimate, dict) or estimate.get("scope") != "convertible-bc-payloads":
            self.estimate_text.set("当前工具未提供实际体积估算，请更新转换器后重新扫描。")
            return
        self.size_estimate = estimate
        self.estimate_partial = not estimate.get("complete", False) or bool(report["summary"].get("errors"))
        self.refresh_estimate()

    @staticmethod
    def data_size(value):
        if value >= 1024 * 1024:
            return f"{value / (1024 * 1024):,.2f} MiB"
        if value >= 1024:
            return f"{value / 1024:,.2f} KiB"
        return f"{value:,} B"

    def refresh_estimate(self):
        if self.size_estimate is None:
            self.estimate_text.set("先扫描并估算，再按实际纹理的 mip / 层数比较各档位；切换压缩块无需重扫。")
            return
        source = self.size_estimate.get("sourceBytes")
        target = self.size_estimate.get("byBlock", {}).get(self.block.get())
        if not isinstance(source, int) or not isinstance(target, int) or source < 0 or target < 0:
            self.estimate_text.set("扫描结果缺少当前压缩块的体积，请重新扫描。")
            return
        prefix = "部分估计 · " if self.estimate_partial else ""
        if source == 0:
            self.estimate_text.set(prefix + "已解析资源中没有可转换的 BC 纹理数据。")
            return
        delta = (target - source) / source
        change = "不变" if delta == 0 else f"{'增加' if delta > 0 else '减少'} {abs(delta):.1%}"
        self.estimate_text.set(f"{prefix}可转换 BC 的纹理块数据：源 {self.data_size(source)} → 预计 {self.data_size(target)}（{change}）")

    def choose_archive_input(self):
        path = (filedialog.askopenfilename(filetypes=[("RPF", "*.rpf"), ("所有文件", "*")])
                if self.archive_mode.get() == "extract" else filedialog.askdirectory())
        if path:
            self.archive_input.set(path)
            self.archive_output.set(path + ("-extracted" if self.archive_mode.get() == "extract" else ".rpf"))

    def choose_archive_output(self):
        if self.archive_mode.get() == "pack":
            path = filedialog.asksaveasfilename(defaultextension=".rpf", filetypes=[("RPF", "*.rpf")])
        else:
            parent = filedialog.askdirectory(title="选择解压目标的父目录")
            path = str(Path(parent) / (Path(self.archive_input.get()).stem + "-extracted")) if parent else ""
        if path:
            self.archive_output.set(path)

    def start_conversion(self, dry_run=False):
        if self.running:
            return
        try:
            source = Path(self.input.get()).expanduser().resolve()
            if not self.input.get().strip() or not source.is_dir():
                raise ValueError("请选择存在的 GTA 游戏目录。")
            jobs = self.jobs.get()
            if not 1 <= jobs <= 64:
                raise ValueError("并行数应为 1 到 64。")
            if self.block.get() not in BLOCKS:
                raise ValueError("请选择列表中的合法 ASTC 压缩块。")
            args = ["convert", str(source), "--block", self.block.get(), "--jobs", str(jobs), *self.encoder_arguments()]
            if dry_run:
                args.append("--dry-run")
            elif self.mode.get() == "in-place":
                if not messagebox.askyesno("覆盖原资源", f"将逐包替换以下目录中的 RPF：\n{source}\n\nBC 转 ASTC 是有损转换，不能恢复原 BC。是否开始？", parent=self.root):
                    return
                args.append("--in-place")
            else:
                output = Path(self.output.get()).expanduser().resolve()
                if not self.output.get().strip() or output.exists():
                    raise ValueError("请填写尚不存在的新输出目录。")
                if output == source or output.is_relative_to(source):
                    raise ValueError("输出目录必须位于 GTA 输入目录之外。")
                args += ["--output", str(output)]
            if self.keep_going.get():
                args.append("--keep-going")
            else:
                args.append("--require-no-bc")
            if self.samples.get() and not dry_run:
                sample_root = source if self.mode.get() == "in-place" else Path(self.output.get()).expanduser().resolve()
                args += ["--samples", str(sample_root / "astc-comparison")]
            self.launch(args, "扫描并估算" if dry_run else "转换纹理")
        except (ValueError, tk.TclError) as error:
            messagebox.showerror("检查目录或参数", str(error), parent=self.root)

    def start_archive(self):
        if self.running:
            return
        source, output = self.archive_input.get().strip(), self.archive_output.get().strip()
        if not source or not output:
            messagebox.showerror("选择路径", "请选择输入与输出。", parent=self.root)
            return
        self.launch([self.archive_mode.get(), str(Path(source).expanduser().resolve()), str(Path(output).expanduser().resolve())], "处理 RPF")

    def launch(self, args, label):
        if not self.executable.is_file():
            messagebox.showerror("工具未构建", "请先运行 linux/build.sh。", parent=self.root)
            return
        is_scan = args[0] == "convert" and "--dry-run" in args
        self.active_scan = (str(Path(args[1]).resolve()), self.input_revision) if is_scan else None
        if is_scan or (args[0] == "convert" and "--in-place" in args):
            self.clear_estimate()
        self.last_report, self.last_error = None, None
        self.converted, self.archive_index, self.archive_total = 0, 0, 1
        self.cancelled = False
        self.running = True
        self.status.set(label + "中…")
        self.summary.set("正在读取资源。可停止任务并保留此前完成的包。")
        self.progress.configure(value=0, maximum=1)
        for button in (self.scan_button, self.convert_button, self.archive_button):
            button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.append_log(label + "：" + args[1])
        threading.Thread(target=self.worker, args=(args,), daemon=True).start()

    def worker(self, args):
        try:
            process = subprocess.Popen([str(self.executable), *self.key_args, *args], stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)
            self.process = process
            if self.cancelled:
                process.terminate()
            for line in process.stdout:
                try:
                    self.events.put(json.loads(line))
                except json.JSONDecodeError:
                    self.events.put({"event": "log", "message": line.rstrip()})
            self.events.put({"event": "exited", "code": process.wait()})
        except OSError as error:
            self.events.put({"event": "error", "message": str(error)})
            self.events.put({"event": "exited", "code": 1})

    def append_log(self, text):
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n")
        if int(self.log.index("end-1c").split(".")[0]) > 1000:
            self.log.delete("1.0", "200.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def log_encoder_settings(self, report):
        settings = report.get("encoderSettings", {})
        parts = []
        effort = settings.get("effectiveEffort")
        if isinstance(effort, (int, float)):
            parts.append(f"搜索强度 {effort:g}")
        weights = settings.get("effectiveChannelWeights")
        if isinstance(weights, list) and len(weights) == 4 and all(isinstance(value, (int, float)) for value in weights):
            parts.append("有效 RGBA 权重 " + ", ".join(f"{value:g}" for value in weights))
        if settings.get("alphaWeightEnabled"):
            parts.append(f"透明颜色优化半径 {settings.get('alphaWeightRadius')}")
        if settings.get("perceptual"):
            parts.append("感知权重")
        if parts:
            self.append_log("本次编码设置：" + " · ".join(parts))

    def poll(self):
        for _ in range(150):
            try:
                event = self.events.get_nowait()
            except queue.Empty:
                break
            kind = event.get("event")
            if kind == "archive-start":
                self.archive_index, self.archive_total = event["index"], event["total"]
                self.status.set(f"{self.archive_index} / {self.archive_total} · {event['path']}")
                self.progress.configure(maximum=self.archive_total, value=self.archive_index - 1)
                self.append_log("读取 " + event["path"])
            elif kind == "resource":
                self.converted += event["converted"]
                self.summary.set(f"已转换 {self.converted:,} 张纹理 · {event['path']}")
            elif kind == "archive-done":
                self.progress.configure(value=self.archive_index)
                self.append_log("完成 " + event["path"])
            elif kind in ("retained-error", "error", "log"):
                self.append_log(("保留原资源：" + event.get("path", "") + " · " if kind == "retained-error" else "") + event.get("message", ""))
                if kind == "error":
                    self.last_error = event.get("message", "处理失败")
            elif kind == "complete":
                self.last_report = event.get("report", event)
            elif kind == "exited":
                self.running = False
                self.process = None
                for button in (self.scan_button, self.convert_button, self.archive_button):
                    button.configure(state="normal")
                self.stop_button.configure(state="disabled")
                if self.cancelled:
                    self.status.set("已停止")
                    self.summary.set("已完成的包会保留。未完成副本需使用新目录重新转换；覆盖模式可重新运行。")
                elif event["code"] != 0 or self.last_report is None:
                    self.status.set("处理未完成")
                    self.summary.set(self.last_error or "请查看日志中的错误。此前完成的包会保留。")
                elif "summary" in self.last_report:
                    report = self.last_report
                    counts = report["summary"]
                    errors = len(counts.get("errors", []))
                    remaining = counts.get("remainingBc")
                    if report.get("dryRun"):
                        self.cache_estimate(report)
                        self.status.set("扫描结束 · 有无法读取的资源" if errors else "扫描完成")
                        detail = f"可转 BC {counts.get('convertibleBc', 0):,} 张 · 受阻 BC {counts.get('blockedBc', 0):,} 张"
                    else:
                        complete = report.get("complete", False) and counts.get("bcFree", False)
                        self.status.set("转换完成" if complete else "部分转换 · 尚未全部转为 ASTC")
                        detail = f"转换 {counts['converted']:,} 张 · 剩余 BC {remaining:,} 张" if remaining is not None else "BC 剩余数量未知"
                    self.summary.set(f"识别 {counts['textures']:,} 张 · {detail} · 失败资源 {errors} 个 · {report['seconds']:.1f} 秒")
                    for sample in counts.get("blockedBcSamples", []):
                        self.append_log(f"保留 BC：{sample.get('path', '')} · {sample.get('texture', '')} · {sample.get('reason', '')}")
                    if not report.get("dryRun"):
                        self.log_encoder_settings(report)
                        self.append_log("报告：" + str(Path(report["output"]) / "rpf-astc-report.json"))
                else:
                    self.status.set("处理完成")
                    self.summary.set("输出已重新校验。")
                    self.progress.configure(value=1, maximum=1)
                self.active_scan = None
        self.root.after(80, self.poll)

    def stop(self):
        if not self.running:
            return
        self.cancelled = True
        self.stop_button.configure(state="disabled")
        self.status.set("正在停止…")
        if self.process is not None:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass

    def close(self):
        if self.running:
            if not messagebox.askyesno("停止处理", "任务仍在运行。停止后保留已完成的包并关闭窗口？", parent=self.root):
                return
            self.stop()
        self.root.destroy()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tool", type=Path, default=Path(__file__).resolve().parents[1] / "build/linux-astc/bin/rpf-tool")
    parser.add_argument("--keys", type=Path, help="Directory containing user-provided AES/NG key files")
    args = parser.parse_args()
    root = tk.Tk()
    App(root, args.tool, args.keys)
    root.mainloop()


if __name__ == "__main__":
    main()
