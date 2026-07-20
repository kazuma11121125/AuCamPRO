#!/usr/bin/env python3
"""録画したMP4/WAVを解析して、映像のカクつき(フレームドロップ)とオーディオの
音割れ(クリッピング)・レベルを調べる診断ツール。

使い方:
    python3 tools/rec_diagnose.py take001.mp4 take001.wav
    python3 tools/rec_diagnose.py --out out_dir take001.mp4
    python3 tools/rec_diagnose.py take001.wav --clip-threshold-db -0.3

依存: ffmpeg/ffprobe (PATH上), numpy, matplotlib
    pip install numpy matplotlib

WAVはこのアプリの WavFileWriter が書く 32-bit float RIFF/RF64 形式を主に想定しているが、
16/24/32-bit 整数PCMの一般的なWAVも読める。
"""
from __future__ import annotations

import argparse
import json
import shutil
import struct
import subprocess
import sys
import warnings
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

# 日本語ラベルが文字化けしないよう、入っていればNoto Sans CJK系フォントを使う。候補のうち
# 実際にインストールされていないフォント名ごとにmatplotlibがUserWarningを出すため抑制する。
_installed = {f.name for f in matplotlib.font_manager.fontManager.ttflist}
_cjk_candidates = ["Noto Sans CJK JP", "Noto Sans JP", "IPAexGothic", "Yu Gothic", "Hiragino Sans"]
_cjk_font = next((name for name in _cjk_candidates if name in _installed), None)
matplotlib.rcParams["font.family"] = [_cjk_font, "DejaVu Sans"] if _cjk_font else ["DejaVu Sans"]
warnings.filterwarnings("ignore", message="Glyph .* missing from font")

VIDEO_EXTS = {".mp4", ".mov", ".mkv", ".m4v"}
AUDIO_EXTS = {".wav"}


# ============================== 映像: カクつき検出 ==============================


def ffprobe_frame_times(path: Path) -> list[float]:
    """先頭の映像ストリームについて、各フレームの表示タイムスタンプ(秒)を返す。

    パケット単位のpts_timeを使う(=デコード不要)。frame=best_effort_timestamp_timeだと
    全フレームをデコードする必要があり、長尺の4K素材などでは大幅に遅くなる。
    """
    cmd = [
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "packet=pts_time",
        "-of", "csv=p=0", str(path),
    ]
    out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    times = []
    for line in out.splitlines():
        line = line.strip()
        if not line or line == "N/A":
            continue
        try:
            times.append(float(line))
        except ValueError:
            continue
    times.sort()
    return times


def ffprobe_stream_fps(path: Path) -> dict:
    cmd = [
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=r_frame_rate,avg_frame_rate,nb_frames,duration",
        "-of", "default=noprint_wrappers=1", str(path),
    ]
    out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    info = {}
    for line in out.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            info[k] = v

    def parse_rate(s: str | None) -> float | None:
        if not s or s == "0/0" or s == "N/A":
            return None
        num, _, den = s.partition("/")
        try:
            num_f, den_f = float(num), float(den or 1)
            return num_f / den_f if den_f else None
        except ValueError:
            return None

    return {
        "r_frame_rate": parse_rate(info.get("r_frame_rate")),
        "avg_frame_rate": parse_rate(info.get("avg_frame_rate")),
    }


def rolling_mean(x: np.ndarray, window: int) -> np.ndarray:
    """window点の中心移動平均(端は縮小窓)。len(x)と同じ長さを返す。"""
    window = max(1, window)
    cumsum = np.cumsum(np.insert(x, 0, 0.0))
    n = len(x)
    out = np.empty(n)
    for i in range(n):
        lo = max(0, i - window // 2)
        hi = min(n, i + window - window // 2)
        out[i] = (cumsum[hi] - cumsum[lo]) / (hi - lo)
    return out


def analyze_video(path: Path, out_dir: Path, stutter_ratio: float = 1.5,
                   sustained_ratio: float = 1.15, sustained_window_sec: float = 1.0):
    times = np.array(ffprobe_frame_times(path))
    if len(times) < 2:
        raise RuntimeError("フレームタイムスタンプが取得できません(映像ストリームなし?)")

    deltas = np.diff(times) * 1000.0  # ms

    # 期待フレーム間隔はコンテナが宣言する r_frame_rate(=カメラ側の設定fps)を最優先する。
    # avg_frame_rate はカクつき/ドロップで実測平均が下がるため、これを基準にすると
    # 「カクついた分だけ基準が緩み、カクつきほど検出しにくくなる」循環が生じる。
    fps_info = ffprobe_stream_fps(path)
    fps = fps_info["r_frame_rate"] or float(1000.0 / np.median(deltas))
    expected_ms = 1000.0 / fps

    stutter_mask = deltas > expected_ms * stutter_ratio
    stutter_idx = np.nonzero(stutter_mask)[0]
    dropped_equiv = np.round(deltas[stutter_idx] / expected_ms - 1.0).clip(min=1) if len(stutter_idx) else np.array([])

    events = [
        {
            "t_sec": round(float(times[idx]), 3),
            "gap_ms": round(float(deltas[idx]), 2),
            "approx_dropped_frames": int(dropped_equiv[i]),
        }
        for i, idx in enumerate(stutter_idx)
    ]
    events.sort(key=lambda e: -e["gap_ms"])

    # 孤立したスパイクとは別に、「毎フレームがわずかに遅い」持続的な速度低下(熱スロットリングの
    # 典型的な症状)を移動平均で検出する。isolatedなstutter_maskだけでは、閾値未満の遅延が
    # 連続しても引っかからず、グラフを見れば分かるのに数値レポートには出ない、という食い違いが起こる。
    window_frames = max(3, int(round(sustained_window_sec * fps)))
    rolling_ms = rolling_mean(deltas, window_frames)
    sustained_mask = rolling_ms > expected_ms * sustained_ratio
    sustained_regions = []
    if sustained_mask.any():
        edges = np.diff(np.concatenate(([0], sustained_mask.astype(np.int8), [0])))
        starts = np.nonzero(edges == 1)[0]
        ends = np.nonzero(edges == -1)[0]
        for s, e in zip(starts, ends):
            region_ms = deltas[s:e]
            sustained_regions.append({
                "t_start_sec": round(float(times[s]), 3),
                "t_end_sec": round(float(times[e]), 3),
                "avg_frame_ms": round(float(np.mean(region_ms)), 2),
                "effective_fps": round(1000.0 / float(np.mean(region_ms)), 2),
            })
    sustained_regions.sort(key=lambda r: -(r["t_end_sec"] - r["t_start_sec"]))

    half = len(deltas) // 2
    first_half_rate = float(np.mean(stutter_mask[:half])) if half else 0.0
    second_half_rate = float(np.mean(stutter_mask[half:])) if len(deltas) - half else 0.0
    if sustained_regions and sustained_regions[0]["t_start_sec"] > times[-1] * 0.3:
        thermal_hint = "録画の後半に持続的な速度低下がある → 熱スロットリングの可能性が高い"
    elif sustained_regions:
        thermal_hint = "持続的な速度低下を検出(発生位置は録画序盤〜中盤) → 熱以外の原因も検討"
    elif second_half_rate > first_half_rate * 1.5 and second_half_rate > 0.01:
        thermal_hint = "後半に孤立したカクつきが偏っている → 熱スロットリングの可能性あり"
    elif len(stutter_idx) == 0:
        thermal_hint = "カクつき検出なし"
    else:
        thermal_hint = "前半/後半で偏りが小さい → 熱以外(ストレージI/Oストール等)の可能性も検討"

    report = {
        "file": str(path),
        "fps_declared": round(fps, 3),
        "expected_frame_ms": round(expected_ms, 3),
        "total_frames": int(len(times)),
        "duration_sec": round(float(times[-1] - times[0]), 3),
        "stutter_count": int(len(stutter_idx)),
        "stutter_rate_pct": round(100.0 * len(stutter_idx) / len(deltas), 3) if len(deltas) else 0.0,
        "total_time_lost_ms": round(float(np.sum(deltas[stutter_idx] - expected_ms)), 1) if len(stutter_idx) else 0.0,
        "first_half_stutter_rate_pct": round(first_half_rate * 100, 2),
        "second_half_stutter_rate_pct": round(second_half_rate * 100, 2),
        "sustained_slowdown_regions": sustained_regions[:10],
        "thermal_pattern_hint": thermal_hint,
        "worst_events": events[:20],
    }

    fig, ax = plt.subplots(figsize=(12, 4))
    ax.plot(times[1:], deltas, linewidth=0.5, color="#99aabb", label="フレーム間隔(生)")
    ax.plot(times[1:], rolling_ms, linewidth=1.2, color="#3366cc", label=f"移動平均({sustained_window_sec:.1f}s窓)")
    ax.axhline(expected_ms, color="gray", linestyle="--", linewidth=0.8, label=f"期待値 {expected_ms:.1f}ms")
    if len(stutter_idx):
        ax.scatter(times[1:][stutter_idx], deltas[stutter_idx], color="red", s=14, zorder=3, label="カクつき検出")
    for region in sustained_regions:
        ax.axvspan(region["t_start_sec"], region["t_end_sec"], color="orange", alpha=0.25)
    ax.set_xlabel("時間 (秒)")
    ax.set_ylabel("フレーム間隔 (ms)")
    ax.set_title(f"映像フレーム間隔: {path.name}")
    ax.legend(loc="upper right", fontsize=8)
    fig.tight_layout()
    plot_path = out_dir / f"{path.stem}_video_stutter.png"
    fig.savefig(plot_path, dpi=140)
    plt.close(fig)

    return report, plot_path


# ============================== 音声: WAV読み込み ==============================


def read_wav(path: Path):
    """RIFF/RF64・32bit float / 16/24/32bit PCM WAVを読む。(frames, rate, channels, full_scale)を返す。"""
    with open(path, "rb") as f:
        riff_id = f.read(4)
        f.read(4)  # RIFF/RF64 chunk size (プレースホルダの場合があるため無視)
        wave_id = f.read(4)
        if riff_id not in (b"RIFF", b"RF64") or wave_id != b"WAVE":
            raise ValueError("WAVファイルとして認識できません")

        fmt = None
        ds64_data_size = None
        data_offset = None
        data_size = None

        while True:
            chunk_id = f.read(4)
            if len(chunk_id) < 4:
                break
            chunk_size = struct.unpack("<I", f.read(4))[0]
            chunk_start = f.tell()

            if chunk_id == b"ds64":
                payload = f.read(chunk_size)
                _, ds64_data_size, _ = struct.unpack_from("<QQQ", payload, 0)
                f.seek(chunk_start + chunk_size + (chunk_size & 1))
            elif chunk_id == b"fmt ":
                payload = f.read(chunk_size)
                tag, channels, rate, _byte_rate, _block_align, bits = struct.unpack_from("<HHIIHH", payload, 0)
                fmt = {"tag": tag, "channels": channels, "rate": rate, "bits": bits}
                f.seek(chunk_start + chunk_size + (chunk_size & 1))
            elif chunk_id == b"data":
                data_offset = chunk_start
                data_size = chunk_size
                break  # このアプリのライターではdataが最後のチャンク
            else:
                f.seek(chunk_start + chunk_size + (chunk_size & 1))

        if fmt is None or data_offset is None:
            raise ValueError("fmt/dataチャンクが見つかりません")

        if data_size == 0xFFFFFFFF:  # RF64センチネル: 実サイズはds64から
            data_size = ds64_data_size if ds64_data_size is not None else (path.stat().st_size - data_offset)

    channels, bits, tag = fmt["channels"], fmt["bits"], fmt["tag"]
    bytes_per_sample = bits // 8
    frame_count = data_size // (channels * bytes_per_sample)

    if tag == 3 and bits == 32:
        arr = np.memmap(path, dtype="<f4", mode="r", offset=data_offset, shape=(frame_count, channels))
        full_scale = 1.0
    elif tag == 3 and bits == 64:
        arr = np.memmap(path, dtype="<f8", mode="r", offset=data_offset, shape=(frame_count, channels))
        full_scale = 1.0
    elif tag == 1 and bits == 16:
        arr = np.memmap(path, dtype="<i2", mode="r", offset=data_offset, shape=(frame_count, channels))
        full_scale = 32768.0
    elif tag == 1 and bits == 32:
        arr = np.memmap(path, dtype="<i4", mode="r", offset=data_offset, shape=(frame_count, channels))
        full_scale = 2147483648.0
    elif tag == 1 and bits == 24:
        raw = np.memmap(path, dtype="<u1", mode="r", offset=data_offset, shape=(frame_count, channels, 3))
        widened = np.zeros((frame_count, channels, 4), dtype="<u1")
        widened[:, :, :3] = raw
        widened[:, :, 3] = np.where(raw[:, :, 2] >= 0x80, 0xFF, 0x00)
        arr = widened.view("<i4").reshape(frame_count, channels)
        full_scale = 8388608.0
    else:
        raise ValueError(f"未対応のWAV形式: tag={tag}, bits={bits}")

    return arr, fmt["rate"], channels, full_scale


# ============================== 音声: レベル・クリップ検出 ==============================


def compute_metrics(arr, rate, full_scale, window_sec=0.1, clip_ratio=0.977, clip_min_run=3,
                     target_block_bytes=256 * 1024 * 1024):
    """windowごとのpeak/RMSとクリップ区間を求める。

    長時間のhi-res録音(192kHz, 数十分)はfloat64化すると数GB〜になり得るため、arrは
    memmapのままブロック単位で処理し、一度に確保するメモリを一定に抑える。クリップ区間の
    検出もブロック境界をまたいで正しくつながるよう、run_start でブロック間の状態を持ち越す。
    target_block_bytes はテストでブロック境界をまたぐケースを再現するために調整可能。
    """
    frame_count, channels = arr.shape
    win = max(1, int(round(window_sec * rate)))
    n_windows = max(1, frame_count // win)
    usable_frames = n_windows * win

    windows_per_block = max(1, target_block_bytes // (channels * 4 * win))
    block_frames = windows_per_block * win

    peak_db = np.empty((n_windows, channels), dtype=np.float64)
    rms_db = np.empty((n_windows, channels), dtype=np.float64)
    clip_threshold = clip_ratio * full_scale
    clip_events: dict[int, list[tuple[int, int, float]]] = {c: [] for c in range(channels)}
    run_start: list[int | None] = [None] * channels

    w_done = 0
    for block_start in range(0, usable_frames, block_frames):
        block_end = min(usable_frames, block_start + block_frames)
        block = np.asarray(arr[block_start:block_end], dtype=np.float32)
        n_block_windows = (block_end - block_start) // win
        reshaped = block[: n_block_windows * win].reshape(n_block_windows, win, channels)

        peak = np.abs(reshaped).max(axis=1) / full_scale
        rms = np.sqrt(np.mean(reshaped.astype(np.float64) ** 2, axis=1)) / full_scale
        peak_db[w_done:w_done + n_block_windows] = 20 * np.log10(np.maximum(peak, 1e-12))
        rms_db[w_done:w_done + n_block_windows] = 20 * np.log10(np.maximum(rms, 1e-12))
        w_done += n_block_windows

        is_clip = np.abs(block) >= clip_threshold  # (block_len, channels)
        for c in range(channels):
            mask = is_clip[:, c]
            prefix = np.int8(1 if run_start[c] is not None else 0)
            ext = np.empty(len(mask) + 1, dtype=np.int8)
            ext[0] = prefix
            ext[1:] = mask.astype(np.int8)
            edges = np.diff(ext)
            local_events = sorted(
                [(int(i), "start") for i in np.nonzero(edges == 1)[0]]
                + [(int(i), "end") for i in np.nonzero(edges == -1)[0]]
            )
            for pos, kind in local_events:
                if kind == "start":
                    run_start[c] = block_start + pos
                else:
                    if run_start[c] is not None:
                        run_end = block_start + pos
                        if run_end - run_start[c] >= clip_min_run:
                            peak_val = float(np.abs(np.asarray(arr[run_start[c]:run_end, c])).max() / full_scale)
                            clip_events[c].append((run_start[c], run_end, peak_val))
                        run_start[c] = None

    for c in range(channels):
        if run_start[c] is not None and usable_frames - run_start[c] >= clip_min_run:
            peak_val = float(np.abs(np.asarray(arr[run_start[c]:usable_frames, c])).max() / full_scale)
            clip_events[c].append((run_start[c], usable_frames, peak_val))

    return peak_db, rms_db, win, clip_events


def analyze_audio(path: Path, out_dir: Path, clip_threshold_db=-0.2, clip_min_run=3, window_sec=0.1):
    arr, rate, channels, full_scale = read_wav(path)
    clip_ratio = 10 ** (clip_threshold_db / 20.0)
    peak_db, rms_db, win, clip_events = compute_metrics(
        arr, rate, full_scale, window_sec=window_sec, clip_ratio=clip_ratio, clip_min_run=clip_min_run
    )
    duration = arr.shape[0] / rate
    times = (np.arange(peak_db.shape[0]) + 0.5) * win / rate

    channel_reports = []
    for c in range(channels):
        events = clip_events[c]
        total_clip_frames = sum(e[1] - e[0] for e in events)
        finite_rms = rms_db[:, c][np.isfinite(rms_db[:, c])]
        channel_reports.append({
            "channel": c,
            "peak_dbfs": round(float(np.max(peak_db[:, c])), 2),
            "rms_dbfs_avg": round(float(np.mean(finite_rms)), 2) if len(finite_rms) else None,
            "clip_event_count": len(events),
            "clip_total_sec": round(total_clip_frames / rate, 3),
            "clip_pct_of_duration": round(100.0 * total_clip_frames / arr.shape[0], 4),
            "worst_clip_events": [
                {"t_start_sec": round(s / rate, 3), "t_end_sec": round(e / rate, 3), "peak": round(p, 4)}
                for s, e, p in sorted(events, key=lambda x: -(x[1] - x[0]))[:10]
            ],
        })

    any_clip = any(r["clip_event_count"] > 0 for r in channel_reports)
    low_level = all((r["rms_dbfs_avg"] or -999) < -30 for r in channel_reports)
    if any_clip:
        gain_hint = "クリッピング検出あり → INPUT GAINを下げるか、マイク位置/音源からの距離を見直す"
    elif low_level:
        gain_hint = "クリッピングなし。RMS平均が低め(-30dBFS未満)→ GAINを上げる余地あり"
    else:
        gain_hint = "クリッピングなし、レベルも概ね適正"

    report = {
        "file": str(path),
        "sample_rate": rate,
        "channels": channels,
        "duration_sec": round(duration, 3),
        "clip_threshold_dbfs": clip_threshold_db,
        "clip_min_run_samples": clip_min_run,
        "gain_hint": gain_hint,
        "channels_detail": channel_reports,
    }

    fig, axes = plt.subplots(channels + 1, 1, figsize=(12, 2.2 * (channels + 1)), sharex=True)
    # np.arange(frame_count)は長時間hi-res録音だとそれだけで数GBになるため、間引き後の
    # インデックスだけを生成する(フル長の時間軸配列は作らない)。
    max_points = 200_000
    step = max(1, arr.shape[0] // max_points)
    t_wave_ds = np.arange(0, arr.shape[0], step) / rate
    for c in range(channels):
        ax = axes[c]
        wave_c = np.asarray(arr[0:arr.shape[0]:step, c], dtype=np.float64) / full_scale
        ax.plot(t_wave_ds, wave_c, linewidth=0.4, color="#336699")
        for s, e, _p in clip_events[c]:
            ax.axvspan(s / rate, e / rate, color="red", alpha=0.4)
        ax.set_ylim(-1.05, 1.05)
        ax.set_ylabel(f"ch{c}")
    ax_level = axes[channels]
    for c in range(channels):
        ax_level.plot(times, peak_db[:, c], linewidth=0.8, label=f"ch{c} peak")
        ax_level.plot(times, rms_db[:, c], linewidth=0.8, linestyle="--", label=f"ch{c} RMS")
    ax_level.axhline(clip_threshold_db, color="red", linestyle=":", linewidth=0.8, label="clip threshold")
    ax_level.set_ylim(-60, 3)
    ax_level.set_ylabel("dBFS")
    ax_level.set_xlabel("時間 (秒)")
    ax_level.legend(loc="lower right", fontsize=7, ncol=channels + 1)
    fig.suptitle(f"オーディオ解析: {path.name}")
    fig.tight_layout()
    plot_path = out_dir / f"{path.stem}_audio_levels.png"
    fig.savefig(plot_path, dpi=140)
    plt.close(fig)

    return report, plot_path


# ============================== エントリポイント ==============================


def main():
    parser = argparse.ArgumentParser(description="録画MP4/WAVのカクつき・音割れ診断ツール")
    parser.add_argument("files", nargs="+", help="解析するMP4/WAVファイル")
    parser.add_argument("--out", default="rec_diagnose_out", help="レポート/画像の出力先ディレクトリ")
    parser.add_argument("--stutter-ratio", type=float, default=1.5,
                         help="期待フレーム間隔の何倍を超えたら孤立したカクつきと判定するか (既定 1.5)")
    parser.add_argument("--sustained-ratio", type=float, default=1.15,
                         help="移動平均が期待フレーム間隔の何倍を超えたら持続的な速度低下(熱スロットリング疑い)と"
                              "判定するか (既定 1.15)")
    parser.add_argument("--sustained-window-sec", type=float, default=1.0,
                         help="持続的な速度低下を見る移動平均の窓幅[秒] (既定 1.0)")
    parser.add_argument("--clip-threshold-db", type=float, default=-0.2,
                         help="この値[dBFS]以上をクリップ候補とみなす (既定 -0.2)")
    parser.add_argument("--clip-min-run", type=int, default=3,
                         help="連続何サンプル以上クリップ値が続いたら音割れと判定するか (既定 3)")
    parser.add_argument("--window-sec", type=float, default=0.1, help="レベルメーターの窓幅[秒] (既定 0.1)")
    args = parser.parse_args()

    if shutil.which("ffprobe") is None:
        print("警告: ffprobeが見つかりません。映像解析にはffmpeg/ffprobeが必要です。", file=sys.stderr)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    all_reports = {}
    for f in args.files:
        path = Path(f)
        if not path.exists():
            print(f"見つかりません: {path}", file=sys.stderr)
            continue
        ext = path.suffix.lower()
        try:
            if ext in VIDEO_EXTS:
                report, plot_path = analyze_video(
                    path, out_dir,
                    stutter_ratio=args.stutter_ratio,
                    sustained_ratio=args.sustained_ratio,
                    sustained_window_sec=args.sustained_window_sec,
                )
                kind = "video"
            elif ext in AUDIO_EXTS:
                report, plot_path = analyze_audio(
                    path, out_dir,
                    clip_threshold_db=args.clip_threshold_db,
                    clip_min_run=args.clip_min_run,
                    window_sec=args.window_sec,
                )
                kind = "audio"
            else:
                print(f"未対応の拡張子: {path}", file=sys.stderr)
                continue
        except Exception as exc:  # noqa: BLE001 — CLI診断ツールなので1ファイル失敗しても他を続ける
            print(f"解析失敗 [{path}]: {exc}", file=sys.stderr)
            continue

        all_reports[str(path)] = report
        print(f"\n===== {kind.upper()}: {path.name} =====")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        print(f"→ グラフ: {plot_path}")

    report_json_path = out_dir / "report.json"
    report_json_path.write_text(json.dumps(all_reports, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nレポートJSON: {report_json_path}")


if __name__ == "__main__":
    main()
