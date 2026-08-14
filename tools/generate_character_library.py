from __future__ import annotations

import argparse
import ast
import concurrent.futures
import json
import shutil
import sqlite3
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

DATABASE_RELEASES_API = "https://api.github.com/repos/SonderXiaoming/priconne-database/releases?per_page=100"
DATABASE_RELEASE_PREFIX = "database-cn-"
ICON_BASE_URL = "https://redive.estertion.win/icon/unit/"
USER_AGENT = "Mozilla/5.0 KokkoroCharacterLibrary/1.0"
ICON_DOWNLOAD_RETRIES = 3


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the offline Kokkoro character library")
    parser.add_argument("--character-data", type=Path, default=Path("素材/角色数据.txt"))
    parser.add_argument("--icons-dir", type=Path, default=Path("素材/characters"))
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("android/app/src/main/assets/characters/character_library.json"),
    )
    parser.add_argument(
        "--icon-output-dir",
        type=Path,
        default=Path("android/app/src/main/assets/characters/icons"),
    )
    parser.add_argument("--db", type=Path, help="Use an already downloaded readable CN master.db")
    parser.add_argument(
        "--database-releases-api",
        default=DATABASE_RELEASES_API,
        help="GitHub releases API used to locate the latest database-cn-* asset",
    )
    parser.add_argument(
        "--proxy",
        help="Optional HTTP proxy, for example http://127.0.0.1:7890",
    )
    parser.add_argument("--no-copy-icons", action="store_true")
    parser.add_argument("--no-download-icons", action="store_true")
    return parser.parse_args()


def load_aliases(path: Path) -> dict[int, list[str]]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        if not any(isinstance(target, ast.Name) and target.id == "CHARA_NAME" for target in node.targets):
            continue
        raw = ast.literal_eval(node.value)
        return {
            int(chara_id): [str(value).strip() for value in values if str(value).strip()]
            for chara_id, values in raw.items()
        }
    raise RuntimeError(f"CHARA_NAME not found in {path}")


def build_url_opener(proxy: str | None) -> urllib.request.OpenerDirector:
    handlers: list[urllib.request.BaseHandler] = []
    if proxy:
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    return urllib.request.build_opener(*handlers)


def request_json(opener: urllib.request.OpenerDirector, url: str) -> object:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with opener.open(request, timeout=30) as response:
        return json.load(response)


def find_latest_cn_database(opener: urllib.request.OpenerDirector, releases_api: str) -> tuple[str, str]:
    releases = request_json(opener, releases_api)
    if not isinstance(releases, list):
        raise RuntimeError("Unexpected GitHub releases API response")
    cn_releases = [
        release for release in releases
        if str(release.get("tag_name", "")).startswith(DATABASE_RELEASE_PREFIX)
    ]
    if not cn_releases:
        raise RuntimeError("No database-cn-* release found")
    release = max(cn_releases, key=lambda item: str(item.get("tag_name", "")))
    assets = release.get("assets") or []
    db_assets = [
        asset for asset in assets
        if str(asset.get("name", "")).startswith("master_cn_unhash_")
        and str(asset.get("name", "")).endswith(".db")
    ]
    if not db_assets:
        raise RuntimeError(f"No readable CN .db asset found in {release.get('tag_name')}")
    asset = db_assets[0]
    return str(asset["browser_download_url"]), str(release["tag_name"])


def resolve_database(args: argparse.Namespace, temp_dir: Path) -> tuple[Path, str]:
    if args.db:
        return args.db, f"local:{args.db.name}"
    opener = build_url_opener(args.proxy)
    url, release_tag = find_latest_cn_database(opener, args.database_releases_api)
    database = temp_dir / "master_cn_unhash.db"
    print(f"Downloading {release_tag}: {url}")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with opener.open(request, timeout=120) as response, database.open("wb") as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)
    return database, release_tag


def read_database_units(database: Path) -> list[sqlite3.Row]:
    connection = sqlite3.connect(database)
    connection.row_factory = sqlite3.Row
    try:
        query = """
        SELECT
            u.unit_id AS unit_id,
            u.unit_name AS unit_name,
            u.original_unit_id AS original_unit_id,
            us.union_burst AS ub_id,
            ub.name AS ub_name,
            us.union_burst_evolution AS ub_plus_id,
            ub_plus.name AS ub_plus_name
        FROM unit_data u
        LEFT JOIN unit_skill_data us ON us.unit_id = u.unit_id
        LEFT JOIN skill_data ub ON ub.skill_id = us.union_burst
        LEFT JOIN skill_data ub_plus ON ub_plus.skill_id = us.union_burst_evolution
        ORDER BY u.unit_id
        """
        return list(connection.execute(query))
    finally:
        connection.close()


def unique_strings(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        normalized = value.strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(normalized)
    return result


def find_icon(icons_dir: Path, unit_id: int) -> Path | None:
    # A standard 3-star portrait exists for normal selectable units even when
    # their base rarity is 1/2 stars. Requiring it avoids story/NPC portraits.
    numeric_id = unit_id + 30
    for extension in ("png", "webp"):
        candidate = icons_dir / f"icon_unit_{numeric_id}.{extension}"
        if candidate.is_file():
            return candidate
    return None


def normalize_database_name(value: str) -> str:
    return value.strip().replace("＆", "&")


def has_normal_ub(row: sqlite3.Row | dict[str, object] | None) -> bool:
    if row is None:
        return False
    return int(row["ub_id"] or 0) > 0 and bool(str(row["ub_name"] or "").strip())


def make_entry(
    canonical_row: sqlite3.Row | dict[str, object],
    members: list[tuple[int, list[str], sqlite3.Row | dict[str, object]]],
) -> dict[str, object]:
    unit_id = int(canonical_row["unit_id"])
    canonical_database_name = str(canonical_row["unit_name"] or "").strip()
    is_combination = any(int(member_row["unit_id"]) != unit_id for _, _, member_row in members)
    unit_name = (
        normalize_database_name(canonical_database_name)
        if is_combination
        else members[0][1][0]
    )
    member_aliases = [alias for _, aliases, _ in members for alias in aliases]
    member_database_names = [
        str(member_row["unit_name"] or "").strip()
        for _, _, member_row in members
        if str(member_row["unit_name"] or "").strip()
    ]
    ub = {
        "id": int(canonical_row["ub_id"]),
        "name": str(canonical_row["ub_name"]).strip(),
    }
    ub_plus_id = int(canonical_row["ub_plus_id"] or 0)
    ub_plus_name = str(canonical_row["ub_plus_name"] or "").strip()
    ub_plus = (
        {"id": ub_plus_id, "name": ub_plus_name}
        if ub_plus_id > 0 and ub_plus_name
        else None
    )
    return {
        "charaId": unit_id // 100,
        "unitId": unit_id,
        "name": unit_name,
        "aliases": unique_strings([
            unit_name,
            canonical_database_name,
            normalize_database_name(canonical_database_name),
            *member_aliases,
            *member_database_names,
            *(normalize_database_name(value) for value in member_database_names),
        ]),
        "iconAsset": None,
        "ub": ub,
        "ubPlus": ub_plus,
    }


def index_database_rows(
    rows: list[sqlite3.Row] | list[dict[str, object]],
) -> tuple[
    dict[int, sqlite3.Row | dict[str, object]],
    dict[int, sqlite3.Row | dict[str, object]],
]:
    by_unit_id: dict[int, sqlite3.Row | dict[str, object]] = {}
    by_chara_id: dict[int, sqlite3.Row | dict[str, object]] = {}
    for row in rows:
        unit_id = int(row["unit_id"])
        by_unit_id[unit_id] = row
        chara_id = unit_id // 100
        previous = by_chara_id.get(chara_id)
        if previous is None:
            by_chara_id[chara_id] = row
            continue
        previous_id = int(previous["unit_id"])
        # Prefer the canonical xx01 record over story/trial copies.
        if unit_id % 100 == 1 and previous_id % 100 != 1:
            by_chara_id[chara_id] = row
    return by_unit_id, by_chara_id


def resolve_canonical_row(
    member_row: sqlite3.Row | dict[str, object] | None,
    by_unit_id: dict[int, sqlite3.Row | dict[str, object]],
) -> sqlite3.Row | dict[str, object] | None:
    if has_normal_ub(member_row):
        return member_row
    if member_row is None:
        return None
    original_unit_id = int(member_row["original_unit_id"] or 0)
    original_row = by_unit_id.get(original_unit_id)
    return original_row if has_normal_ub(original_row) else None


def build_entries(
    aliases: dict[int, list[str]],
    rows: list[sqlite3.Row] | list[dict[str, object]],
) -> tuple[list[dict[str, object]], list[int]]:
    by_unit_id, by_chara_id = index_database_rows(rows)
    grouped: dict[
        int,
        tuple[
            sqlite3.Row | dict[str, object],
            list[tuple[int, list[str], sqlite3.Row | dict[str, object]]],
        ],
    ] = {}
    excluded: list[int] = []

    for chara_id, alias_values in sorted(aliases.items()):
        if not alias_values:
            continue
        member_row = by_chara_id.get(chara_id)
        canonical_row = resolve_canonical_row(member_row, by_unit_id)
        if member_row is None or canonical_row is None:
            excluded.append(chara_id)
            continue
        canonical_unit_id = int(canonical_row["unit_id"])
        if canonical_unit_id not in grouped:
            grouped[canonical_unit_id] = (canonical_row, [])
        grouped[canonical_unit_id][1].append((chara_id, alias_values, member_row))

    entries = [
        make_entry(canonical_row, members)
        for _, (canonical_row, members) in sorted(grouped.items())
    ]
    return entries, excluded


def materialize_local_icon(source: Path, output_dir: Path) -> Path:
    if source.suffix.lower() == ".webp":
        destination = output_dir / source.name
        shutil.copy2(source, destination)
        return destination
    try:
        from PIL import Image
    except ImportError:
        destination = output_dir / source.name
        shutil.copy2(source, destination)
        return destination
    destination = output_dir / f"{source.stem}.webp"
    with Image.open(source) as image:
        image.save(destination, "WEBP", quality=82, method=4)
    return destination


def download_icon(
    chara_id: int,
    output_dir: Path,
    proxy: str | None = None,
    retries: int = ICON_DOWNLOAD_RETRIES,
) -> Path | None:
    resource_id = f"{chara_id:04d}31"
    filename = f"icon_unit_{resource_id}.webp"
    destination = output_dir / filename
    if destination.is_file():
        return destination
    request = urllib.request.Request(
        f"{ICON_BASE_URL}{resource_id}.webp",
        headers={"User-Agent": USER_AGENT},
    )
    opener = build_url_opener(proxy)
    for attempt in range(1, max(1, retries) + 1):
        try:
            with opener.open(request, timeout=20) as response:
                if not response.headers.get_content_type().startswith("image/"):
                    return None
                payload = response.read()
                if not payload:
                    raise OSError("empty icon response")
                destination.write_bytes(payload)
                return destination
        except urllib.error.HTTPError as exc:
            # A missing portrait is a real data condition, not a transient network
            # failure. Retry server-side failures only; otherwise fail immediately.
            if exc.code < 500 or attempt >= retries:
                return None
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt >= retries:
                return None
        time.sleep(float(attempt))
    return None


def materialize_icons(
    entries: list[dict[str, object]],
    icons_dir: Path,
    output_dir: Path,
    download_missing: bool,
    proxy: str | None = None,
) -> tuple[int, list[int]]:
    output_dir.mkdir(parents=True, exist_ok=True)

    def worker(entry: dict[str, object]) -> tuple[dict[str, object], Path | None]:
        unit_id = int(entry["unitId"])
        chara_id = int(entry["charaId"])
        local = find_icon(icons_dir, unit_id)
        if local is not None:
            return entry, materialize_local_icon(local, output_dir)
        if download_missing:
            return entry, download_icon(chara_id, output_dir, proxy)
        return entry, None

    expected: set[str] = set()
    missing: list[int] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as executor:
        for entry, materialized in executor.map(worker, entries):
            if materialized is None:
                missing.append(int(entry["unitId"]))
                entry["iconAsset"] = None
                continue
            expected.add(materialized.name)
            entry["iconAsset"] = f"characters/icons/{materialized.name}"

    for existing in output_dir.iterdir():
        if existing.is_file() and existing.name not in expected:
            existing.unlink()
    return len(expected), missing


def main() -> None:
    args = parse_args()
    aliases = load_aliases(args.character_data)

    with tempfile.TemporaryDirectory(prefix="kokkoro-character-library-") as temporary:
        database, database_source = resolve_database(args, Path(temporary))
        rows = read_database_units(database)

    # The local roster remains the alias source, while the current CN master DB
    # is the authority for whether a role is actually selectable in CN. Special
    # multi-character cards are represented by member records whose
    # original_unit_id points at one shared battle unit; collapse those members
    # into one selectable entry and take UB data from that original unit.
    entries, excluded_by_database = build_entries(aliases, rows)

    copied = 0
    missing_icons: list[int] = []
    if not args.no_copy_icons:
        copied, missing_icons = materialize_icons(
            entries,
            args.icons_dir,
            args.icon_output_dir,
            download_missing=not args.no_download_icons,
            proxy=args.proxy,
        )
        # Official unit portrait availability is the final playable-role gate.
        # This removes NPC/name-only records without relying on a stale DB.
        entries = [entry for entry in entries if entry["iconAsset"] is not None]

    payload = {
        "schemaVersion": 2,
        "databaseSource": database_source,
        "characters": entries,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    ub_count = sum(1 for entry in entries if entry["ub"] is not None)
    six_star = sum(1 for entry in entries if entry["ubPlus"] is not None)
    print(
        f"Generated {len(entries)} CN-playable portrait-backed characters, "
        f"UB data={ub_count}, UB+ data={six_star}, "
        f"{copied} icons; excluded by CN DB={len(excluded_by_database)}, "
        f"excluded without icon={len(missing_icons)}"
    )
    if excluded_by_database:
        print("Excluded alias chara ids:", ",".join(map(str, excluded_by_database)))
    if missing_icons:
        print("Missing icon unit ids:", ",".join(map(str, missing_icons)))


if __name__ == "__main__":
    main()
