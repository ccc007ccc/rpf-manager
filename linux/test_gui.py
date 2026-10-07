#!/usr/bin/env python3
"""Headless end-to-end GUI check using a procedural BC1 resource, never GTA data."""
import argparse
import copy
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import tkinter as tk
from unittest.mock import patch
import zlib

from gui import App, BLOCKS, CUSTOM_PRESET, PRESETS


def fixture(texture_format=0x31545844):
    system, graphics = bytearray(8192), bytearray(8192)
    for offset, pointer in ((8, 0x50000400), (0x20, 0x50000100), (0x30, 0x50000180),
                            (0x180, 0x50000200), (0x228, 0x50000300), (0x270, 0x60000000)):
        struct.pack_into("<Q", system, offset, pointer)
    for offset in (0x28, 0x2a, 0x38, 0x3a):
        struct.pack_into("<H", system, offset, 1)
    struct.pack_into("<4H", system, 0x250, 8, 8, 1, 4)
    struct.pack_into("<I", system, 0x258, texture_format)
    system[0x25d] = 1
    system[0x300:0x305] = b"test\0"
    system[0x408:0x40a] = b"\x01\x01"
    graphics[:32] = struct.pack("<HHI", 0xf800, 0, 0) * 4
    encoder = zlib.compressobj(wbits=-15)
    return struct.pack("<4I", 0x37435352, 13, 0x20000, 0xd0020000) + encoder.compress(system + graphics) + encoder.flush()


def finish(root, app, expected_error=None):
    deadline = time.monotonic() + 45
    while app.running and time.monotonic() < deadline:
        root.update()
        time.sleep(0.01)
    assert not app.running, "GUI worker did not finish"
    if expected_error:
        assert expected_error in (app.last_error or ""), app.last_error
        assert app.last_report is None
    else:
        assert app.last_error is None, app.last_error
        assert app.last_report is not None


def choose_preset(root, app, block):
    app.preset_box.set(next(label for label, value in PRESETS.items() if value == block))
    app.preset_box.event_generate("<<ComboboxSelected>>")
    root.update()


def check_advanced_controls(root, app):
    app.toggle_advanced()
    root.update()
    assert app.advanced_frame.winfo_ismapped()
    app.custom_effort_enabled.set(True)
    app.effort.set("37.5")
    for variable, value in zip(app.channel_weights, ("2", "1", "0.5", "4")):
        variable.set(value)
    app.alpha_weight_enabled.set(True)
    app.alpha_radius.set("2")
    app.perceptual.set(True)
    assert str(app.quality_box.cget("state")) == "disabled"
    assert app.encoder_arguments() == ["--effort", "37.5", "--channel-weights", "2.0,1.0,0.5,4.0",
                                       "--alpha-weight-radius", "2", "--perceptual"]
    for variable, invalid in ((app.effort, "nan"), (app.effort, "101"),
                              (app.channel_weights[0], "inf"), (app.channel_weights[0], "0"),
                              (app.alpha_radius, "1.5"), (app.alpha_radius, "17")):
        original = variable.get()
        variable.set(invalid)
        with patch.object(app, "launch") as launch, patch("gui.messagebox.showerror") as error:
            app.start_conversion(dry_run=True)
        launch.assert_not_called()
        error.assert_called_once()
        variable.set(original)
    app.toggle_advanced()
    assert "已自定义" in app.advanced_caption.get()
    assert "--effort" in app.encoder_arguments(), "collapsed settings must remain active"


def check_estimate_cache(app, game):
    report = app.last_report
    estimate = app.size_estimate
    assert estimate["complete"]
    assert estimate["sourceBytes"] == 32
    assert set(estimate["byBlock"]) == set(BLOCKS)
    for block in BLOCKS:
        width, height = map(int, block.split("x"))
        expected = ((8 + width - 1) // width) * ((8 + height - 1) // height) * 16
        assert estimate["byBlock"][block] == expected
        app.block.set(block)
        assert f"源 32 B → 预计 {expected} B" in app.estimate_text.get()
    app.active_scan = (str(game.resolve()), app.input_revision)
    partial = copy.deepcopy(report)
    partial["summary"]["sizeEstimate"]["complete"] = False
    app.cache_estimate(partial)
    assert "部分估计" in app.estimate_text.get()
    app.cache_estimate(report)
    assert "部分估计" not in app.estimate_text.get()
    # Even changing away and back while a worker runs invalidates its old result.
    app.input.set(str(game.parent))
    app.input.set(str(game))
    app.cache_estimate(report)
    assert app.size_estimate is None
    app.active_scan = (str(game.resolve()), app.input_revision)
    app.cache_estimate(report)
    assert app.size_estimate == estimate
    app.active_scan = None


def check_written_8x8(tool, output, extracted):
    subprocess.run([str(tool), "extract", str(output / "sample.rpf"), str(extracted)], check=True)
    resource = (extracted / "test.ytd").read_bytes()
    flags = struct.unpack_from("<I", resource, 8)[0]
    page_weights = ((4, 1, 256), (5, 3, 128), (7, 15, 64), (11, 63, 32), (17, 127, 16),
                    (24, 1, 8), (25, 1, 4), (26, 1, 2), (27, 1, 1))
    system_size = (512 << (flags & 15)) * sum(((flags >> shift) & mask) * weight for shift, mask, weight in page_weights)
    sections = zlib.decompress(resource[16:], -15)
    assert sections[0x258:0x25c] == b"AS\x08\x08", "RPF resource did not store ASTC 8x8"
    data_offset = struct.unpack_from("<Q", sections, 0x270)[0] - 0x60000000
    sample = next((output / "astc-comparison").rglob("*.astc")).read_bytes()
    assert sample[:4] == bytes.fromhex("13 ab a1 5c")
    assert sample[4:7] == bytes((8, 8, 1))
    assert len(sample) - 16 == 16, "8x8 one-mip fixture should use one 16-byte ASTC block"
    assert sections[system_size + data_offset:system_size + data_offset + 16] == sample[16:]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tool", required=True, type=Path)
    parser.add_argument("--screenshot", type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="rpf-astc-gui-") as directory:
        temp = Path(directory)
        tree, game = temp / "unpacked", temp / "GTA 中文"
        tree.mkdir(); game.mkdir()
        (tree / "test.ytd").write_bytes(fixture())
        (game / "other.txt").write_text("preserve this")
        subprocess.run([str(args.tool), "pack", str(tree), str(game / "sample.rpf")], check=True)
        original = hashlib.sha256((game / "sample.rpf").read_bytes()).digest()
        root = tk.Tk()
        try:
            app = App(root, args.tool)
            assert app.block.get() == "4x4"
            assert PRESETS[app.preset.get()] == "4x4"
            original_quality = app.quality.get()
            app.block_box.set("6x5")
            root.update()
            assert app.preset.get() == CUSTOM_PRESET
            choose_preset(root, app, "12x12")
            assert "0.89 bit/像素" in app.capacity.get()
            assert "BC1 的 22.2%" in app.capacity.get()
            app.block_box.set("4x4")
            assert PRESETS[app.preset.get()] == "4x4"
            app.input.set(str(game))
            app.output.set(str(temp / "GTA ASTC"))
            check_advanced_controls(root, app)
            app.start_conversion(dry_run=True)
            finish(root, app)
            assert app.last_report["dryRun"]
            assert app.last_report["summary"]["convertibleBc"] == 1
            assert app.last_report["summary"]["blockedBc"] == 0
            assert not (temp / "GTA ASTC").exists()
            scan_report = app.last_report
            check_estimate_cache(app, game)
            with patch("gui.messagebox.askyesno", return_value=True) as extra_confirmation:
                choose_preset(root, app, "8x8")
                assert app.block.get() == "8x8"
                assert app.quality.get() == original_quality
                assert "2.00 bit/像素" in app.capacity.get()
                assert "4×4 的 25.0%" in app.capacity.get()
                assert "BC1 的 50.0%" in app.capacity.get()
                assert "BC3 的 25.0%" in app.capacity.get()
                app.samples.set(True)
                app.start_conversion()
            extra_confirmation.assert_not_called()
            finish(root, app)
            assert app.last_report["complete"]
            assert app.last_report["requireNoBc"]
            assert app.last_report["summary"]["bcFree"]
            assert app.last_report["summary"]["converted"] == 1
            assert app.last_report["block"] == [8, 8]
            assert app.last_report["summary"]["astcTextureBytes"] == 16
            settings = app.last_report["encoderSettings"]
            assert settings["effectiveEffort"] == 37.5
            assert settings["channelWeights"] == [2, 1, 0.5, 4]
            assert settings["alphaWeightEnabled"] and settings["alphaWeightRadius"] == 2
            assert settings["perceptual"]
            assert "搜索强度 37.5" in app.log.get("1.0", "end")
            assert "有效 RGBA 权重" in app.log.get("1.0", "end")
            app.reset_advanced()
            assert app.encoder_arguments() == ["--quality", "thorough"]
            assert str(app.quality_box.cget("state")) == "readonly"
            assert "已自定义" not in app.advanced_caption.get()
            check_written_8x8(args.tool, temp / "GTA ASTC", temp / "readback")
            assert (temp / "GTA ASTC/other.txt").read_text() == "preserve this"
            assert hashlib.sha256((game / "sample.rpf").read_bytes()).digest() == original
            app.input.set(str(temp / "GTA ASTC"))
            assert app.size_estimate is None
            app.samples.set(False)
            app.mode.set("in-place")
            app.refresh_mode()
            with patch("gui.messagebox.askyesno", return_value=True) as confirmation:
                app.start_conversion()
            confirmation.assert_called_once()
            finish(root, app)
            assert app.last_report["summary"]["converted"] == 0
            assert app.last_report["summary"]["alreadyAstc"] == 1
            blocked_tree, blocked_game = temp / "blocked-tree", temp / "blocked-game"
            blocked_tree.mkdir(); blocked_game.mkdir()
            (blocked_tree / "hdr.ytd").write_bytes(fixture(0x20364342))
            subprocess.run([str(args.tool), "pack", str(blocked_tree), str(blocked_game / "blocked.rpf")], check=True)
            blocked_original = hashlib.sha256((blocked_game / "blocked.rpf").read_bytes()).digest()
            app.input.set(str(blocked_game))
            app.output.set(str(temp / "blocked-output"))
            app.mode.set("copy"); app.refresh_mode()
            app.start_conversion()
            finish(root, app, expected_error="1 retained BC textures")
            assert not (temp / "blocked-output/blocked.rpf").exists()
            assert hashlib.sha256((blocked_game / "blocked.rpf").read_bytes()).digest() == blocked_original
            app.output.set(str(temp / "partial-output"))
            app.keep_going.set(True)
            app.start_conversion()
            finish(root, app)
            assert not app.last_report["complete"]
            assert not app.last_report["summary"]["bcFree"]
            assert app.last_report["summary"]["remainingBc"] == 1
            assert "部分转换" in app.status.get()
            assert "剩余 BC 1 张" in app.summary.get()
            if args.screenshot:
                from PIL import ImageGrab
                app.input.set(str(game))
                app.output.set(str(temp / "preview-output"))
                app.mode.set("copy"); app.refresh_mode()
                app.keep_going.set(False)
                app.active_scan = (str(game.resolve()), app.input_revision)
                app.cache_estimate(scan_report)
                app.active_scan = None
                if not app.advanced_visible:
                    app.toggle_advanced()
                app.status.set("扫描完成")
                app.summary.set("合成 BC1 测试资源：8×8、单 mip；上方显示此次扫描的实际纹理块体积。")
                app.log.configure(state="normal"); app.log.delete("1.0", "end"); app.log.configure(state="disabled")
                app.root.geometry("860x720")
                root.update()
                assert app.log.winfo_rooty() + app.log.winfo_height() <= root.winfo_rooty() + root.winfo_height(), "minimum window must keep the log visible"
                app.convert_canvas.yview_moveto(0.3)
                root.update()
                args.screenshot.parent.mkdir(parents=True, exist_ok=True)
                ImageGrab.grab(bbox=(root.winfo_rootx(), root.winfo_rooty(), root.winfo_rootx()+root.winfo_width(), root.winfo_rooty()+root.winfo_height())).save(args.screenshot)
        finally:
            root.destroy()
    print("GUI PASS: 14-block payload estimates, partial/stale scan cache, advanced parameter validation and encoder report, reset/collapse, ASTC 8x8 readback, strict/partial results, copy/in-place, source preservation")


if __name__ == "__main__":
    main()
