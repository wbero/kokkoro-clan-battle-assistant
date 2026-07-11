import csv
import tempfile
import unittest
from pathlib import Path

from tools.clock_debug_baseline import (
    DigitRow,
    FrameRow,
    apply_manual_labels,
    analyze,
    build_confusion_matrix,
    detect_transition_intervals,
    digits_for_time,
    summarize_margins,
    json_safe,
    read_digits,
    read_manual_labels,
    weak_times_from_frames,
)


class ClockDebugBaselineTest(unittest.TestCase):
    def test_read_digits_prefers_structural_decision_scores_in_new_schema(self):
        with tempfile.TemporaryDirectory() as temp:
            session = Path(temp)
            decision = ",".join(str(i / 10) for i in range(10))
            ncc = ",".join(str(1 - i / 10) for i in range(10))
            (session / "digits.csv").write_text(
                "frameId,wallMs,slot,scoreKind,rawTop1,rawTop2,rawMargin,chosen,chosenScore,decisionMargin,decisionRule,"
                + ",".join(f"decision{i}" for i in range(10)) + ","
                + ",".join(f"ncc{i}" for i in range(10)) + ",cropFile\n"
                + f"1,1000,SECOND_ONES,STRUCTURAL_IOU,9,8,0.1,9,0.9,0.1,test,{decision},{ncc},crop.png\n",
                encoding="utf-8",
            )

            row = read_digits(session)[0]

            self.assertEqual(tuple(i / 10 for i in range(10)), row.scores)

    def test_digits_for_time_maps_clock_slots(self):
        self.assertEqual(
            {"MINUTE": 1, "SECOND_TENS": 0, "SECOND_ONES": 6},
            digits_for_time(66),
        )

    def test_manual_label_overrides_weak_label(self):
        rows = [DigitRow("s1", 10, 1000, "SECOND_ONES", 0, 5, 0.1, (0.9,) * 10)]
        labels = {("s1", 10, "SECOND_ONES"): 6}
        labeled = apply_manual_labels(rows, {("s1", 10): 5}, labels)
        self.assertEqual(6, labeled[0].truth)
        self.assertEqual("manual", labeled[0].label_source)

    def test_filter_time_is_only_a_weak_label(self):
        rows = [DigitRow("s1", 10, 1000, "SECOND_ONES", 0, 5, 0.1, (0.9,) * 10)]
        labeled = apply_manual_labels(rows, {("s1", 10): 5}, {})
        self.assertEqual(5, labeled[0].truth)
        self.assertEqual("weak_filter_time", labeled[0].label_source)

    def test_same_time_filter_output_also_provides_weak_label(self):
        frames = [
            FrameRow("s1", 10, 1000, "RUNNING", False, 88, "same-time", "PRIMARY", 0),
        ]
        self.assertEqual({("s1", 10): 88}, weak_times_from_frames(frames))

    def test_detects_n_plus_one_to_n_minus_one_transition(self):
        frames = [
            FrameRow("s1", 10, 1000, "RUNNING", True, 20, "accepted", "PRIMARY", 0),
            FrameRow("s1", 11, 1060, "RUNNING", False, None, "rejected", "", 0),
            FrameRow("s1", 12, 1120, "RUNNING", True, 18, "accepted", "PRIMARY", 0),
        ]
        intervals = detect_transition_intervals(frames)
        self.assertEqual(1, len(intervals))
        self.assertEqual(19, intervals[0].missing_time)
        self.assertEqual((10, 12), (intervals[0].start_frame_id, intervals[0].end_frame_id))

    def test_confusion_matrix_uses_raw_top1_against_labels(self):
        rows = [
            DigitRow("s1", 1, 1, "SECOND_ONES", 0, 6, 0.03, (0.0,) * 10, 6, "manual"),
            DigitRow("s1", 2, 2, "SECOND_ONES", 8, 3, 0.05, (0.0,) * 10, 3, "manual"),
        ]
        matrix = build_confusion_matrix(rows)
        self.assertEqual(1, matrix[6][0])
        self.assertEqual(1, matrix[3][8])

    def test_margin_summary_reports_distribution(self):
        rows = [
            DigitRow("s1", 1, 1, "SECOND_ONES", 0, 6, 0.01, (0.0,) * 10, 6, "manual"),
            DigitRow("s1", 2, 2, "SECOND_ONES", 6, 0, 0.09, (0.0,) * 10, 0, "manual"),
        ]
        summary = summarize_margins(rows)
        self.assertEqual(2, summary["count"])
        self.assertAlmostEqual(0.05, summary["mean"])
        self.assertAlmostEqual(0.01, summary["min"])
        self.assertAlmostEqual(0.09, summary["max"])

    def test_json_safe_replaces_nan_with_null(self):
        self.assertEqual({"value": None, "items": [1, None]}, json_safe({"value": float("nan"), "items": [1, float("nan")]}))

    def test_explicit_missing_manual_labels_file_fails(self):
        with self.assertRaisesRegex(FileNotFoundError, "manual labels file does not exist"):
            read_manual_labels(Path("definitely-missing-labels.csv"))

    def test_conflicting_duplicate_manual_label_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "labels.csv"
            path.write_text(
                "session,frameId,slot,truth,note\n"
                "s1,1,SECOND_ONES,5,a\n"
                "s1,1,SECOND_ONES,6,b\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "conflicting duplicate manual label"):
                read_manual_labels(path)

    def test_analyze_end_to_end_applies_manual_labels_and_writes_confusion(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            input_dir = root / "clock-debug"
            session = input_dir / "session-one"
            session.mkdir(parents=True)
            self._write_fixture_csvs(session)
            labels = root / "labels.csv"
            labels.write_text(
                "session,frameId,slot,truth,note\n"
                "session-one,1,SECOND_ONES,6,confirmed\n",
                encoding="utf-8",
            )
            output = root / "output"
            summary = analyze(input_dir, output, labels, 0.08)
            self.assertEqual(1, summary["totals"]["manual_labels_loaded"])
            self.assertEqual(1, summary["totals"]["manual_labels_applied"])
            self.assertEqual(0, summary["totals"]["manual_labels_unmatched"])
            self.assertEqual(6, summary["raw_top1_vs_labels"]["total"])
            with (output / "raw_top1_confusion.csv").open(encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))
            truth_six = next(row for row in rows if row["label_source"] == "weak_plus_manual" and row["truth"] == "6")
            self.assertEqual("1", truth_six["pred_0"])

    def test_analyze_rejects_unmatched_manual_label_by_default(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            session = root / "clock-debug" / "session-one"
            session.mkdir(parents=True)
            self._write_fixture_csvs(session)
            labels = root / "labels.csv"
            labels.write_text(
                "session,frameId,slot,truth,note\n"
                "missing-session,999,SECOND_ONES,6,orphan\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "unmatched manual labels"):
                analyze(root / "clock-debug", root / "output", labels, 0.08)

    def test_analyze_can_report_unmatched_manual_label_when_explicitly_allowed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            session = root / "clock-debug" / "session-one"
            session.mkdir(parents=True)
            self._write_fixture_csvs(session)
            labels = root / "labels.csv"
            labels.write_text(
                "session,frameId,slot,truth,note\n"
                "missing-session,999,SECOND_ONES,6,orphan\n",
                encoding="utf-8",
            )
            summary = analyze(root / "clock-debug", root / "output", labels, 0.08, allow_unmatched=True)
            self.assertEqual(1, summary["totals"]["manual_labels_loaded"])
            self.assertEqual(0, summary["totals"]["manual_labels_applied"])
            self.assertEqual(1, summary["totals"]["manual_labels_unmatched"])
            self.assertIn("unmatched", " ".join(summary["warnings"]))

    def test_real_capture_smoke_counts_when_fixture_exists(self):
        capture = Path("android/clock-debug-pull-2026-07-12/clock-debug")
        if not capture.exists():
            self.skipTest("local ignored capture is unavailable")
        with tempfile.TemporaryDirectory() as temp:
            summary = analyze(capture, Path(temp), None, 0.08)
        self.assertEqual(8896, summary["totals"]["frames"])
        self.assertEqual(21990, summary["totals"]["digit_rows"])
        self.assertEqual(0, summary["totals"]["dropped"])
        self.assertEqual(21, summary["totals"]["transition_intervals"])

    @staticmethod
    def _write_fixture_csvs(session: Path):
        (session / "frames.csv").write_text(
            "frameId,wallMs,gate,recognitionRaw,recognitionOk,recognitionConfidence,recognitionReason,filterAccepted,filterTime,filterReason,filterSource,dropped\n"
            "1,1000,RUNNING,0:05,true,0.9,,true,5,,PRIMARY,0\n"
            "2,1060,RUNNING,0:05,true,0.9,,false,5,same-time,PRIMARY,0\n",
            encoding="utf-8",
        )
        score_columns = ",".join(f"s{i}" for i in range(10))
        header = f"frameId,wallMs,slot,rawTop1,rawTop2,rawMargin,chosen,chosenScore,decisionMargin,decisionRule,{score_columns},cropFile\n"
        lines = [header]
        for frame_id, wall_ms in ((1, 1000), (2, 1060)):
            for slot, raw in (("MINUTE", 0), ("SECOND_TENS", 0), ("SECOND_ONES", 0)):
                scores = ["0.9" if i == raw else "0.1" for i in range(10)]
                lines.append(
                    f"{frame_id},{wall_ms},{slot},{raw},1,0.8,{raw},0.9,0.8,test,{','.join(scores)},frame.png\n"
                )
        (session / "digits.csv").write_text("".join(lines), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
