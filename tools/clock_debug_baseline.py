#!/usr/bin/env python3
"""Build an offline baseline from Android clock-debug CSV exports.

The Android filter result is deliberately treated as a weak label. It is useful
for triage and dataset construction, but it is not ground truth. A human-owned
labels.csv can override weak labels without changing the raw capture files.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Mapping, Optional, Sequence


SLOTS = ("MINUTE", "SECOND_TENS", "SECOND_ONES")
FOCUS_DIGITS = frozenset({0, 3, 5, 6, 8, 9})


@dataclass(frozen=True)
class FrameRow:
    session: str
    frame_id: int
    wall_ms: int
    gate: str
    filter_accepted: bool
    filter_time: Optional[int]
    filter_reason: str
    filter_source: str
    dropped: int
    recognition_ok: bool = False
    recognition_reason: str = ""


@dataclass(frozen=True)
class DigitRow:
    session: str
    frame_id: int
    wall_ms: int
    slot: str
    raw_top1: int
    chosen: int
    raw_margin: float
    scores: tuple[float, ...]
    truth: Optional[int] = None
    label_source: str = "unlabeled"
    crop_file: str = ""


@dataclass(frozen=True)
class TransitionInterval:
    session: str
    start_frame_id: int
    end_frame_id: int
    start_wall_ms: int
    end_wall_ms: int
    from_time: int
    missing_time: int
    to_time: int


def _bool(value: str) -> bool:
    return value.strip().lower() == "true"


def _optional_int(value: str) -> Optional[int]:
    value = value.strip()
    return int(value) if value else None


def digits_for_time(time_seconds: int) -> dict[str, int]:
    if not 0 <= time_seconds <= 599:
        raise ValueError(f"time_seconds out of range: {time_seconds}")
    minutes, seconds = divmod(time_seconds, 60)
    return {
        "MINUTE": minutes,
        "SECOND_TENS": seconds // 10,
        "SECOND_ONES": seconds % 10,
    }


def read_frames(session_dir: Path) -> list[FrameRow]:
    path = session_dir / "frames.csv"
    if not path.exists() or path.stat().st_size == 0:
        return []
    rows: list[FrameRow] = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            rows.append(
                FrameRow(
                    session=session_dir.name,
                    frame_id=int(row["frameId"]),
                    wall_ms=int(row["wallMs"]),
                    gate=row.get("gate", ""),
                    filter_accepted=_bool(row.get("filterAccepted", "")),
                    filter_time=_optional_int(row.get("filterTime", "")),
                    filter_reason=row.get("filterReason", ""),
                    filter_source=row.get("filterSource", ""),
                    dropped=int(row.get("dropped", "0") or 0),
                    recognition_ok=_bool(row.get("recognitionOk", "")),
                    recognition_reason=row.get("recognitionReason", ""),
                )
            )
    return rows


def read_digits(session_dir: Path) -> list[DigitRow]:
    path = session_dir / "digits.csv"
    if not path.exists() or path.stat().st_size == 0:
        return []
    rows: list[DigitRow] = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            score_prefix = "decision" if "decision0" in row else "s"
            rows.append(
                DigitRow(
                    session=session_dir.name,
                    frame_id=int(row["frameId"]),
                    wall_ms=int(row["wallMs"]),
                    slot=row["slot"],
                    raw_top1=int(row["rawTop1"]),
                    chosen=int(row["chosen"]),
                    raw_margin=float(row["rawMargin"]),
                    scores=tuple(float(row[f"{score_prefix}{i}"]) for i in range(10)),
                    crop_file=row.get("cropFile", ""),
                )
            )
    return rows


def read_manual_labels(path: Optional[Path]) -> dict[tuple[str, int, str], int]:
    if path is None:
        return {}
    if not path.exists():
        raise FileNotFoundError(f"manual labels file does not exist: {path}")
    labels: dict[tuple[str, int, str], int] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            truth = int(row["truth"])
            if truth not in range(10):
                raise ValueError(f"manual truth must be 0..9: {row}")
            key = (row["session"], int(row["frameId"]), row["slot"])
            previous = labels.get(key)
            if previous is not None and previous != truth:
                raise ValueError(
                    f"conflicting duplicate manual label for {key}: {previous} vs {truth}"
                )
            labels[key] = truth
    return labels


def apply_manual_labels(
    digits: Iterable[DigitRow],
    weak_times: Mapping[tuple[str, int], int],
    manual_labels: Mapping[tuple[str, int, str], int],
) -> list[DigitRow]:
    labeled: list[DigitRow] = []
    for row in digits:
        key = (row.session, row.frame_id, row.slot)
        weak_time = weak_times.get((row.session, row.frame_id))
        if key in manual_labels:
            truth = manual_labels[key]
            source = "manual"
        elif weak_time is not None:
            truth = digits_for_time(weak_time).get(row.slot)
            source = "weak_filter_time" if truth is not None else "unlabeled"
        else:
            truth = None
            source = "unlabeled"
        labeled.append(
            DigitRow(
                row.session,
                row.frame_id,
                row.wall_ms,
                row.slot,
                row.raw_top1,
                row.chosen,
                row.raw_margin,
                row.scores,
                truth,
                source,
                row.crop_file,
            )
        )
    return labeled


def weak_times_from_frames(frames: Iterable[FrameRow]) -> dict[tuple[str, int], int]:
    """Return every emitted filterTime as a weak label, including same-time frames."""
    return {
        (row.session, row.frame_id): row.filter_time
        for row in frames
        if row.filter_time is not None
    }


def detect_transition_intervals(frames: Iterable[FrameRow]) -> list[TransitionInterval]:
    by_session: dict[str, list[FrameRow]] = defaultdict(list)
    for frame in frames:
        if frame.filter_time is not None:
            by_session[frame.session].append(frame)
    intervals: list[TransitionInterval] = []
    for session, session_frames in by_session.items():
        ordered = sorted(session_frames, key=lambda f: (f.wall_ms, f.frame_id))
        for previous, current in zip(ordered, ordered[1:]):
            if previous.filter_time - current.filter_time == 2:
                intervals.append(
                    TransitionInterval(
                        session,
                        previous.frame_id,
                        current.frame_id,
                        previous.wall_ms,
                        current.wall_ms,
                        previous.filter_time,
                        previous.filter_time - 1,
                        current.filter_time,
                    )
                )
    return intervals


def build_confusion_matrix(rows: Iterable[DigitRow]) -> list[list[int]]:
    matrix = [[0 for _ in range(10)] for _ in range(10)]
    for row in rows:
        if row.truth is not None:
            matrix[row.truth][row.raw_top1] += 1
    return matrix


def _percentile(values: Sequence[float], fraction: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lower = math.floor(index)
    upper = math.ceil(index)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] * (upper - index) + ordered[upper] * (index - lower)


def summarize_margins(rows: Iterable[DigitRow]) -> dict[str, float | int]:
    values = [row.raw_margin for row in rows if row.truth is not None]
    if not values:
        return {"count": 0, "mean": math.nan, "min": math.nan, "p10": math.nan,
                "median": math.nan, "p90": math.nan, "max": math.nan}
    return {
        "count": len(values),
        "mean": statistics.fmean(values),
        "min": min(values),
        "p10": _percentile(values, 0.10),
        "median": _percentile(values, 0.50),
        "p90": _percentile(values, 0.90),
        "max": max(values),
    }


def score_means(rows: Iterable[DigitRow]) -> dict[int, list[float]]:
    buckets: dict[int, list[tuple[float, ...]]] = defaultdict(list)
    for row in rows:
        if row.truth is not None:
            buckets[row.truth].append(row.scores)
    return {
        truth: [statistics.fmean(sample[i] for sample in samples) for i in range(10)]
        for truth, samples in sorted(buckets.items())
    }


def build_review_manifest(
    rows: Iterable[DigitRow], intervals: Iterable[TransitionInterval], margin_limit: float
) -> list[dict[str, object]]:
    rows = list(rows)
    transition_by_session: dict[str, list[TransitionInterval]] = defaultdict(list)
    for interval in intervals:
        transition_by_session[interval.session].append(interval)
    manifest: dict[tuple[str, int, str], dict[str, object]] = {}
    for row in rows:
        reasons: list[str] = []
        expected_missing: Optional[int] = None
        for interval in transition_by_session.get(row.session, []):
            if interval.start_wall_ms <= row.wall_ms <= interval.end_wall_ms:
                reasons.append("n+1_to_n-1_transition")
                expected_missing = interval.missing_time
                break
        if row.truth in FOCUS_DIGITS and row.raw_top1 != row.truth:
            reasons.append("focus_digit_mismatch")
        if row.truth in FOCUS_DIGITS and row.raw_margin <= margin_limit:
            reasons.append("focus_digit_low_margin")
        if not reasons:
            continue
        manifest[(row.session, row.frame_id, row.slot)] = {
            "session": row.session,
            "frameId": row.frame_id,
            "wallMs": row.wall_ms,
            "slot": row.slot,
            "weakTruth": "" if row.truth is None else row.truth,
            "rawTop1": row.raw_top1,
            "chosen": row.chosen,
            "rawMargin": row.raw_margin,
            "labelSource": row.label_source,
            "expectedMissingTime": "" if expected_missing is None else expected_missing,
            "cropFile": row.crop_file,
            "reason": ";".join(reasons),
            "manualTruth": "",
            "note": "",
        }
    return sorted(manifest.values(), key=lambda r: (r["session"], r["wallMs"], r["slot"]))


def _counter(rows: Iterable[FrameRow], field: str) -> dict[str, int]:
    return dict(sorted(Counter(str(getattr(row, field) or "<blank>") for row in rows).items()))


def _matrix_accuracy(matrix: list[list[int]]) -> dict[str, float | int]:
    total = sum(sum(row) for row in matrix)
    correct = sum(matrix[i][i] for i in range(10))
    return {"total": total, "correct": correct, "agreement": correct / total if total else math.nan}


def json_safe(value):
    if isinstance(value, float) and math.isnan(value):
        return None
    if isinstance(value, dict):
        return {key: json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    return value


def write_csv(path: Path, fieldnames: Sequence[str], rows: Iterable[Mapping[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def analyze(
    input_dir: Path,
    output_dir: Path,
    labels_path: Optional[Path],
    margin_limit: float,
    allow_unmatched: bool = False,
) -> dict:
    session_dirs = sorted(path for path in input_dir.iterdir() if path.is_dir())
    frames = [row for session in session_dirs for row in read_frames(session)]
    digits = [row for session in session_dirs for row in read_digits(session)]
    manual = read_manual_labels(labels_path)
    digit_keys = {(row.session, row.frame_id, row.slot) for row in digits}
    applied_manual_keys = set(manual).intersection(digit_keys)
    unmatched_manual_keys = set(manual).difference(digit_keys)
    if unmatched_manual_keys and not allow_unmatched:
        examples = ", ".join(str(key) for key in sorted(unmatched_manual_keys)[:3])
        raise ValueError(
            f"{len(unmatched_manual_keys)} unmatched manual labels; examples: {examples}. "
            "Fix the labels or pass --allow-unmatched to report them without applying."
        )
    weak_times = weak_times_from_frames(frames)
    labeled = apply_manual_labels(digits, weak_times, manual)
    intervals = detect_transition_intervals(frames)
    manifest = build_review_manifest(labeled, intervals, margin_limit)
    matrix = build_confusion_matrix(labeled)
    manual_matrix = build_confusion_matrix(row for row in labeled if row.label_source == "manual")
    means = score_means(labeled)

    sessions = []
    for session in session_dirs:
        sf = [row for row in frames if row.session == session.name]
        sd = [row for row in digits if row.session == session.name]
        sessions.append({
            "session": session.name,
            "frames": len(sf),
            "digit_rows": len(sd),
            "dropped": max((row.dropped for row in sf), default=0),
            "accepted_frames": sum(row.filter_accepted for row in sf),
        })

    warnings = ["filterTime labels are weak labels, not ground truth; agreement is not accuracy"]
    if unmatched_manual_keys:
        warnings.append(
            f"{len(unmatched_manual_keys)} unmatched manual labels were loaded but not applied"
        )
    summary = {
        "warning": "filterTime labels are weak labels, not ground truth; agreement is not accuracy",
        "warnings": warnings,
        "input": str(input_dir),
        "sessions": sessions,
        "totals": {
            "sessions": len(session_dirs),
            "frames": len(frames),
            "digit_rows": len(digits),
            "dropped": sum(item["dropped"] for item in sessions),
            "filter_accepted": sum(row.filter_accepted for row in frames),
            "recognition_ok": sum(row.recognition_ok for row in frames),
            "manual_labels_loaded": len(manual),
            "manual_labels_applied": len(applied_manual_keys),
            "manual_labels_unmatched": len(unmatched_manual_keys),
            "weak_labeled_digits": sum(row.label_source == "weak_filter_time" for row in labeled),
            "unlabeled_digits": sum(row.truth is None for row in labeled),
            "transition_intervals": len(intervals),
            "review_manifest_rows": len(manifest),
        },
        "gate_counts": _counter(frames, "gate"),
        "filter_reason_counts": _counter(frames, "filter_reason"),
        "filter_source_counts": _counter(frames, "filter_source"),
        "recognition_reason_counts": _counter(frames, "recognition_reason"),
        "raw_top1_vs_labels": _matrix_accuracy(matrix),
        "raw_top1_vs_manual_labels": _matrix_accuracy(manual_matrix),
        "margin": summarize_margins(labeled),
        "margin_by_truth": {
            str(truth): summarize_margins(row for row in labeled if row.truth == truth)
            for truth in range(10)
        },
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "summary.json").write_text(
        json.dumps(json_safe(summary), ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8"
    )
    write_csv(
        output_dir / "raw_top1_confusion.csv",
        ["label_source", "truth", *[f"pred_{i}" for i in range(10)]],
        [
            {"label_source": source, "truth": truth, **{f"pred_{i}": source_matrix[truth][i] for i in range(10)}}
            for source, source_matrix in (("weak_plus_manual", matrix), ("manual_only", manual_matrix))
            for truth in range(10)
        ],
    )
    write_csv(
        output_dir / "score_means.csv",
        ["truth", "count", *[f"mean_s{i}" for i in range(10)]],
        [
            {"truth": truth, "count": sum(row.truth == truth for row in labeled),
             **{f"mean_s{i}": values[i] for i in range(10)}}
            for truth, values in means.items()
        ],
    )
    margin_rows = [{"truth": "ALL", **summarize_margins(labeled)}]
    margin_rows.extend(
        {"truth": truth, **summarize_margins(row for row in labeled if row.truth == truth)}
        for truth in range(10)
    )
    write_csv(output_dir / "margin_stats.csv", list(margin_rows[0]), margin_rows)
    manifest_fields = [
        "session", "frameId", "wallMs", "slot", "weakTruth", "rawTop1", "chosen",
        "rawMargin", "labelSource", "expectedMissingTime", "cropFile", "reason",
        "manualTruth", "note",
    ]
    write_csv(output_dir / "review_manifest.csv", manifest_fields, manifest)
    write_csv(
        output_dir / "labels_template.csv",
        ["session", "frameId", "slot", "truth", "note"],
        ({"session": row["session"], "frameId": row["frameId"], "slot": row["slot"],
          "truth": row["manualTruth"], "note": row["note"]} for row in manifest),
    )
    write_csv(
        output_dir / "transition_intervals.csv",
        list(asdict(intervals[0]).keys()) if intervals else [
            "session", "start_frame_id", "end_frame_id", "start_wall_ms", "end_wall_ms",
            "from_time", "missing_time", "to_time",
        ],
        (asdict(interval) for interval in intervals),
    )
    return summary


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="clock-debug directory containing session-* folders")
    parser.add_argument("--output", type=Path, default=Path("android/clock-debug-baseline-output"))
    parser.add_argument("--labels", type=Path, help="optional manual labels.csv")
    parser.add_argument("--margin-limit", type=float, default=0.08)
    parser.add_argument(
        "--allow-unmatched",
        action="store_true",
        help="report manual labels that match no digit row instead of failing",
    )
    args = parser.parse_args(argv)
    try:
        summary = analyze(
            args.input,
            args.output,
            args.labels,
            args.margin_limit,
            allow_unmatched=args.allow_unmatched,
        )
    except (FileNotFoundError, ValueError) as error:
        parser.error(str(error))
    print(json.dumps(summary["totals"], ensure_ascii=False, indent=2))
    print("WARNING: filterTime-derived labels are weak labels, not ground truth.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
