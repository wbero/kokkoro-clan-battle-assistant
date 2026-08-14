import unittest
import urllib.error
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from tools.generate_character_library import build_entries, download_icon


def row(
    unit_id: int,
    unit_name: str,
    *,
    original_unit_id: int = 0,
    ub_id: int = 0,
    ub_name: str = "",
    ub_plus_id: int = 0,
    ub_plus_name: str = "",
) -> dict[str, object]:
    return {
        "unit_id": unit_id,
        "unit_name": unit_name,
        "original_unit_id": original_unit_id,
        "ub_id": ub_id,
        "ub_name": ub_name,
        "ub_plus_id": ub_plus_id,
        "ub_plus_name": ub_plus_name,
    }


class CharacterLibraryGeneratorTest(unittest.TestCase):
    def test_combination_members_collapse_to_shared_original_unit(self):
        aliases = {
            1183: ["初音(初音&栞)", "星弓星"],
            1184: ["栞(初音&栞)", "星弓栞"],
        }
        rows = [
            row(118301, "初音（初音＆栞）", original_unit_id=180701),
            row(118401, "栞（初音＆栞）", original_unit_id=180701),
            row(180701, "初音＆栞", ub_id=1807001, ub_name="星愿共鸣"),
        ]

        entries, excluded = build_entries(aliases, rows)

        self.assertEqual([], excluded)
        self.assertEqual(1, len(entries))
        entry = entries[0]
        self.assertEqual(1807, entry["charaId"])
        self.assertEqual(180701, entry["unitId"])
        self.assertEqual("初音&栞", entry["name"])
        self.assertEqual({"id": 1807001, "name": "星愿共鸣"}, entry["ub"])
        self.assertIn("初音(初音&栞)", entry["aliases"])
        self.assertIn("栞(初音&栞)", entry["aliases"])
        self.assertIn("初音＆栞", entry["aliases"])

    def test_non_playable_cn_records_are_excluded(self):
        aliases = {
            1102: ["美咲(夏日)"],
            1354: ["安涅默涅(夏日)"],
        }
        rows = [
            row(110201, "美咲（夏日）"),
        ]

        entries, excluded = build_entries(aliases, rows)

        self.assertEqual([], entries)
        self.assertEqual([1102, 1354], excluded)

    def test_direct_cn_character_keeps_local_name_and_ub_plus(self):
        aliases = {1046: ["珠希", "猫剑"]}
        rows = [
            row(
                104601,
                "珠希",
                ub_id=1046001,
                ub_name="猫猫决胜爪",
                ub_plus_id=1046011,
                ub_plus_name="猫猫幻影斩击",
            )
        ]

        entries, excluded = build_entries(aliases, rows)

        self.assertEqual([], excluded)
        self.assertEqual(1, len(entries))
        self.assertEqual("珠希", entries[0]["name"])
        self.assertEqual({"id": 1046011, "name": "猫猫幻影斩击"}, entries[0]["ubPlus"])

    def test_icon_download_retries_transient_network_failure(self):
        class Headers:
            @staticmethod
            def get_content_type():
                return "image/webp"

        class Response:
            headers = Headers()

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            @staticmethod
            def read():
                return b"webp-image"

        opener = mock.Mock()
        opener.open.side_effect = [urllib.error.URLError("temporary"), Response()]
        with TemporaryDirectory() as temporary, \
                mock.patch("tools.generate_character_library.build_url_opener", return_value=opener), \
                mock.patch("tools.generate_character_library.time.sleep") as sleep:
            result = download_icon(1354, Path(temporary))

            self.assertIsNotNone(result)
            self.assertEqual(b"webp-image", result.read_bytes())
            self.assertEqual(2, opener.open.call_count)
            sleep.assert_called_once_with(1.0)

    def test_icon_download_does_not_retry_missing_portrait(self):
        opener = mock.Mock()
        opener.open.side_effect = urllib.error.HTTPError(
            url="https://example.invalid/icon.webp",
            code=404,
            msg="not found",
            hdrs=None,
            fp=None,
        )
        with TemporaryDirectory() as temporary, \
                mock.patch("tools.generate_character_library.build_url_opener", return_value=opener), \
                mock.patch("tools.generate_character_library.time.sleep") as sleep:
            result = download_icon(9999, Path(temporary))

            self.assertIsNone(result)
            self.assertEqual(1, opener.open.call_count)
            sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
