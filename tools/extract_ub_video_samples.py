from __future__ import annotations

import argparse
import csv
import importlib.util
import shutil
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


REFERENCE_WIDTH = 1920
REFERENCE_HEIGHT = 1080
UB_BANNER = (560, 120, 1360, 230)


@dataclass(frozen=True)
class Candidate:
    frame: int
    time_ms: float
    color_score: float
    left_score: float
    right_score: float
    text_edge_score: float
    crop_rgb: np.ndarray


def load_exact_detector(repo_root: Path):
    path = repo_root / "ub框识别测试" / "test_ub_banner_detector_exact.py"
    spec = importlib.util.spec_from_file_location("ub_banner_exact", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def normalize_game_frame(frame_bgr: np.ndarray) -> np.ndarray:
    height, width = frame_bgr.shape[:2]
    scale = min(width / REFERENCE_WIDTH, height / REFERENCE_HEIGHT)
    game_width = max(1, round(REFERENCE_WIDTH * scale))
    game_height = max(1, round(REFERENCE_HEIGHT * scale))
    left = max(0, (width - game_width) // 2)
    top = max(0, (height - game_height) // 2)
    viewport = frame_bgr[top : top + game_height, left : left + game_width]
    return cv2.resize(viewport, (REFERENCE_WIDTH, REFERENCE_HEIGHT), interpolation=cv2.INTER_AREA)


def text_edge_score(crop_rgb: np.ndarray) -> float:
    centre = crop_rgb[:, 90:710]
    gray = cv2.cvtColor(centre, cv2.COLOR_RGB2GRAY)
    edges = cv2.Canny(gray, 70, 150)
    return float(np.count_nonzero(edges)) / float(edges.size)


def scan_video(
    path: Path,
    detector,
    left_template: np.ndarray,
    right_template: np.ndarray,
    frame_step: int,
) -> tuple[float, list[Candidate]]:
    capture = cv2.VideoCapture(str(path))
    if not capture.isOpened():
        raise RuntimeError(f"Cannot open video: {path}")
    fps = capture.get(cv2.CAP_PROP_FPS) or 60.0
    candidates: list[Candidate] = []
    frame_index = 0
    left, top, right, bottom = UB_BANNER
    while True:
        ok, frame_bgr = capture.read()
        if not ok:
            break
        if frame_index % frame_step == 0:
            normalized_bgr = normalize_game_frame(frame_bgr)
            normalized_rgb = cv2.cvtColor(normalized_bgr, cv2.COLOR_BGR2RGB)
            crop_rgb = normalized_rgb[top:bottom, left:right]
            # Cheap prefilter before the exact template matcher. Real UB banners
            # have a strong blue band; a permissive floor only removes obvious
            # non-banner frames and keeps the production matcher authoritative.
            search = detector.downsample(crop_rgb, detector.SEARCH_DOWNSAMPLE_FACTOR)
            if detector.color_score(search) >= 0.40:
                color, left_score, right_score, present, _, _ = detector.detect(
                    normalized_rgb,
                    left_template,
                    right_template,
                )
                if present:
                    candidates.append(
                        Candidate(
                            frame=frame_index,
                            time_ms=frame_index * 1000.0 / fps,
                            color_score=color,
                            left_score=left_score,
                            right_score=right_score,
                            text_edge_score=text_edge_score(crop_rgb),
                            crop_rgb=crop_rgb.copy(),
                        )
                    )
        frame_index += 1
    capture.release()
    return fps, candidates


def cluster_candidates(candidates: list[Candidate], max_gap_frames: int) -> list[list[Candidate]]:
    if not candidates:
        return []
    groups: list[list[Candidate]] = [[candidates[0]]]
    for candidate in candidates[1:]:
        if candidate.frame - groups[-1][-1].frame <= max_gap_frames:
            groups[-1].append(candidate)
        else:
            groups.append([candidate])
    return groups


def select_samples(group: list[Candidate], count: int) -> list[Candidate]:
    # Prefer frames with the richest centre-text detail, but keep frame order in
    # the exported set so OCR results can still be read as a mini timeline.
    ranked = sorted(group, key=lambda item: item.text_edge_score, reverse=True)
    chosen: list[Candidate] = []
    for candidate in ranked:
        if all(abs(candidate.frame - existing.frame) >= 2 for existing in chosen):
            chosen.append(candidate)
        if len(chosen) >= count:
            break
    return sorted(chosen, key=lambda item: item.frame)


def write_png(path: Path, image_rgb: np.ndarray) -> None:
    image_bgr = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2BGR)
    ok, encoded = cv2.imencode(".png", image_bgr)
    if not ok:
        raise RuntimeError(f"Cannot encode {path}")
    path.write_bytes(encoded.tobytes())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("video_dir")
    parser.add_argument("--output", default="android/app/build/generated/ubVideoReplayAssets/ub_replay")
    parser.add_argument("--frame-step", type=int, default=2)
    parser.add_argument("--samples-per-cycle", type=int, default=4)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    detector = load_exact_detector(repo_root)
    left_template = detector.load_rgb(repo_root / "ub框识别测试" / "ub框左浪花.bmp")
    right_template = detector.load_rgb(repo_root / "ub框识别测试" / "ub框右浪花.bmp")
    video_source = (repo_root / args.video_dir).resolve()
    videos = [video_source] if video_source.is_file() else sorted(video_source.glob("*.mp4"))
    if not videos:
        raise RuntimeError(f"No mp4 files under {video_source}")

    output = (repo_root / args.output).resolve()
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)

    rows: list[dict[str, object]] = []
    for video_index, video in enumerate(videos, start=1):
        fps, candidates = scan_video(
            video,
            detector,
            left_template,
            right_template,
            frame_step=max(1, args.frame_step),
        )
        groups = cluster_candidates(
            candidates,
            max_gap_frames=max(args.frame_step * 3, round(fps * 0.18)),
        )
        print(f"{video.name}: {len(groups)} banner cycle(s), {len(candidates)} detected frame(s)")
        for cycle_index, group in enumerate(groups, start=1):
            for sample_index, candidate in enumerate(
                select_samples(group, max(1, args.samples_per_cycle)),
                start=1,
            ):
                filename = (
                    f"v{video_index:02d}_c{cycle_index:03d}_s{sample_index}_"
                    f"f{candidate.frame:06d}.png"
                )
                write_png(output / filename, candidate.crop_rgb)
                rows.append(
                    {
                        "file": filename,
                        "video": video.name,
                        "cycle": cycle_index,
                        "sample": sample_index,
                        "frame": candidate.frame,
                        "time_ms": round(candidate.time_ms, 3),
                        "color": round(candidate.color_score, 6),
                        "left": round(candidate.left_score, 6),
                        "right": round(candidate.right_score, 6),
                        "text_edges": round(candidate.text_edge_score, 6),
                    }
                )

    manifest = output.parent / "ub_video_samples.csv"
    with manifest.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()) if rows else ["file"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"exported {len(rows)} OCR sample(s) -> {output}")
    print(f"manifest -> {manifest}")


if __name__ == "__main__":
    main()
