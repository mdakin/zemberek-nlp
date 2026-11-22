#!/usr/bin/env python3
"""
Download and extract open Turkish search/query datasets and auxiliary spelling
dictionaries under ~/data/turkish/normalization/queries/ and lexicons_external/.

Uses only Python 3 standard library + requests. Safe and idempotent to re-run.
"""

import csv
import gzip
import hashlib
import html
import json
import re
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import requests

csv.field_size_limit(sys.maxsize)

BASE_DIR = Path("/home/dndara/data/turkish/normalization")
SOURCE_CORPORA_DIR = BASE_DIR / "source_corpora"
QUERIES_DIR = SOURCE_CORPORA_DIR / "queries"
Q_RAW_DIR = QUERIES_DIR / "raw"
Q_PLAIN_DIR = QUERIES_DIR / "plain"
RAW_DIR = SOURCE_CORPORA_DIR
LEX_DIR = BASE_DIR / "lexicons_external"
ASIMOKBY_DIR = LEX_DIR / "asimokby_spelling_dict"

USER_AGENT = "zemberek-normalization-query-prep/1.0"
WHITESPACE_RE = re.compile(r"\s+")
SLUG_RE = re.compile(r"/(?:konu|threads?|soru)/([a-z0-9\-]+)")
TRAILING_ID_RE = re.compile(r"\s+\d+$")

CTRL_CHAR_MAP = str.maketrans({
    "\x91": "'",
    "\x92": "'",
    "\x93": '"',
    "\x94": '"',
    "\x96": "-",
    "\x97": "-",
    "\x85": "...",
    "\xa0": " ",
    "\ufeff": "",
})


def ensure_dirs() -> None:
    for d in [Q_RAW_DIR, Q_PLAIN_DIR, ASIMOKBY_DIR]:
        d.mkdir(parents=True, exist_ok=True)


def clean_line(text: str) -> str:
    if not text:
        return ""
    text = text.translate(CTRL_CHAR_MAP)
    text = html.unescape(text)
    text = WHITESPACE_RE.sub(" ", text).strip()
    return text


def download_file(url: str, dest: Path, retries: int = 3, timeout: int = 60) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0:
        print(f"[SKIP] {dest.relative_to(BASE_DIR)} ({dest.stat().st_size:,} B)")
        return dest

    tmp = dest.with_suffix(dest.suffix + ".part")
    last_err: Optional[Exception] = None
    for attempt in range(1, retries + 1):
        try:
            print(f"[GET ] {url} -> {dest.relative_to(BASE_DIR)} (try {attempt})")
            with requests.get(
                url,
                headers={"User-Agent": USER_AGENT},
                stream=True,
                timeout=timeout,
            ) as resp:
                resp.raise_for_status()
                with open(tmp, "wb") as f:
                    for chunk in resp.iter_content(chunk_size=262144):
                        if chunk:
                            f.write(chunk)
            tmp.replace(dest)
            print(f"[OK  ] {dest.relative_to(BASE_DIR)} ({dest.stat().st_size:,} B)")
            return dest
        except Exception as exc:
            last_err = exc
            if tmp.exists():
                tmp.unlink()
            if attempt < retries:
                time.sleep(2 * attempt)
    raise RuntimeError(f"Failed to download {url}: {last_err}")


def fetch_json(url: str) -> object:
    resp = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=30)
    resp.raise_for_status()
    return resp.json()


def write_plain_lines(dest: Path, lines: Iterable[str]) -> Tuple[int, int]:
    tmp = dest.with_suffix(".txt.part")
    n_lines = 0
    n_tokens = 0
    with open(tmp, "w", encoding="utf-8", newline="\n") as out:
        for raw in lines:
            c = clean_line(raw)
            if not c:
                continue
            out.write(c)
            out.write("\n")
            n_lines += 1
            n_tokens += len(c.split())
    tmp.replace(dest)
    print(f"[TEXT] {dest.relative_to(BASE_DIR)}: {n_lines:,} lines, {n_tokens:,} tokens")
    return n_lines, n_tokens


def process_bing_turkey_queries() -> Tuple[int, int]:
    bing_tsv = Q_RAW_DIR / "bing_turkey_2020_2021.tsv"
    if not (bing_tsv.exists() and bing_tsv.stat().st_size > 0):
        tmp = bing_tsv.with_suffix(".tsv.part")
        total_rows = 0
        with open(tmp, "w", encoding="utf-8", newline="\n") as out:
            out.write("Date\tQuery\tIsImplicitIntent\tCountry\tPopularityScore\n")
            for year in ["2020", "2021"]:
                listing = fetch_json(
                    f"https://api.github.com/repos/microsoft/BingCoronavirusQuerySet/contents/data/{year}"
                )
                qbc_files = sorted(
                    x["name"]
                    for x in listing
                    if x.get("name", "").startswith("QueriesByCountry_")
                )
                for fname in qbc_files:
                    raw_url = (
                        f"https://raw.githubusercontent.com/microsoft/BingCoronavirusQuerySet/"
                        f"master/data/{year}/{fname}"
                    )
                    print(f"[STRM] {fname} (filtering Country == Turkey)")
                    matched = 0
                    with requests.get(
                        raw_url,
                        headers={"User-Agent": USER_AGENT},
                        stream=True,
                        timeout=90,
                    ) as resp:
                        resp.raise_for_status()
                        resp.encoding = "utf-8"
                        for idx, line in enumerate(resp.iter_lines(decode_unicode=True)):
                            if not line:
                                continue
                            if idx == 0 and line.startswith("Date\t"):
                                continue
                            cols = line.split("\t")
                            if len(cols) >= 5 and cols[3].strip() == "Turkey":
                                out.write(line)
                                out.write("\n")
                                matched += 1
                    total_rows += matched
                    print(f"       -> {matched:,} Turkey rows")
        tmp.replace(bing_tsv)
        print(f"[OK  ] Saved {total_rows:,} Turkey rows to {bing_tsv.relative_to(BASE_DIR)}")
    else:
        print(f"[SKIP] {bing_tsv.relative_to(BASE_DIR)} ({bing_tsv.stat().st_size:,} B)")

    scores: Dict[str, int] = defaultdict(int)
    with open(bing_tsv, "r", encoding="utf-8", errors="replace") as f:
        for idx, line in enumerate(f):
            if idx == 0 and line.startswith("Date\t"):
                continue
            cols = line.rstrip("\r\n").split("\t")
            if len(cols) < 5:
                continue
            q = clean_line(cols[1])
            if not q:
                continue
            try:
                sc = int(cols[4])
            except ValueError:
                sc = 1
            scores[q] += sc

    ordered = [q for q, _ in sorted(scores.items(), key=lambda kv: (-kv[1], kv[0]))]
    return write_plain_lines(Q_PLAIN_DIR / "bing_turkey_queries_unique.txt", ordered)


def process_mkqa() -> Tuple[int, int]:
    mkqa_gz = Q_RAW_DIR / "mkqa.jsonl.gz"
    download_file(
        "https://raw.githubusercontent.com/apple/ml-mkqa/main/dataset/mkqa.jsonl.gz",
        mkqa_gz,
        timeout=120,
    )
    try:
        download_file(
            "https://raw.githubusercontent.com/apple/ml-mkqa/main/LICENSE",
            Q_RAW_DIR / "mkqa_LICENSE.txt",
        )
    except Exception:
        download_file(
            "https://raw.githubusercontent.com/apple/ml-mkqa/main/README.md",
            Q_RAW_DIR / "mkqa_README.md",
        )

    def iter_tr() -> Iterable[str]:
        with gzip.open(mkqa_gz, "rt", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                obj = json.loads(line)
                q_tr = (obj.get("queries") or {}).get("tr")
                if isinstance(q_tr, str) and q_tr.strip():
                    yield q_tr

    return write_plain_lines(Q_PLAIN_DIR / "mkqa_google_tr_clean_10k.txt", iter_tr())


def process_ecommerce_intent() -> Tuple[int, int]:
    csv_path = Q_RAW_DIR / "ecommerce_intent_tr.csv"
    download_file(
        "https://huggingface.co/datasets/kdrhnn/turkce-eticaret-niyet-analizi/resolve/main/musteri_hizmetleri_veriseti.csv",
        csv_path,
    )

    def iter_rows() -> Iterable[str]:
        with open(csv_path, "r", encoding="utf-8-sig", errors="replace", newline="") as f:
            reader = csv.reader(f)
            header = next(reader, None)
            if not header:
                return
            idx = 0
            for i, h in enumerate(header):
                if h.strip() == "text":
                    idx = i
                    break
            for row in reader:
                if idx < len(row):
                    yield row[idx]

    return write_plain_lines(Q_PLAIN_DIR / "ecommerce_customer_queries.txt", iter_rows())


def extract_eksisozluk_titles() -> Tuple[int, int]:
    seen = set()
    ordered: List[str] = []

    def add_title(raw: str) -> None:
        c = clean_line(raw)
        if c and c not in seen:
            seen.add(c)
            ordered.append(c)

    csv_path = RAW_DIR / "eksisozluk" / "eksisozluk_2024_apache" / "eksisozluk_entries.csv"
    if csv_path.exists():
        with open(csv_path, "r", encoding="utf-8-sig", errors="replace", newline="") as f:
            reader = csv.reader(f)
            header = next(reader, None)
            idx = 1
            if header:
                for i, h in enumerate(header):
                    if h.strip() == "title":
                        idx = i
                        break
            for row in reader:
                if idx < len(row):
                    add_title(row[idx])

    jsonl_dir = RAW_DIR / "eksisozluk" / "eksisozluk_2025_2026_jsonl"
    if jsonl_dir.exists():
        for p in sorted(jsonl_dir.glob("*.jsonl")):
            with open(p, "r", encoding="utf-8-sig", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    topic = obj.get("topic")
                    if isinstance(topic, str):
                        add_title(topic)

    return write_plain_lines(Q_PLAIN_DIR / "eksisozluk_topic_titles.txt", ordered)


def extract_forum_slugs() -> Tuple[int, int]:
    seen = set()
    ordered: List[str] = []
    forums_dir = RAW_DIR / "forums_modern_2020s"
    if forums_dir.exists():
        for p in sorted(forums_dir.glob("*.jsonl")):
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        url = json.loads(line).get("url", "")
                    except json.JSONDecodeError:
                        continue
                    m = SLUG_RE.search(url)
                    if not m:
                        continue
                    slug = m.group(1).replace("-", " ").strip()
                    slug = TRAILING_ID_RE.sub("", slug).strip()
                    if len(slug.split()) >= 2 and slug not in seen:
                        seen.add(slug)
                        ordered.append(slug)

    return write_plain_lines(Q_PLAIN_DIR / "forum_search_thread_slugs.txt", ordered)


def download_asimokby_dict() -> None:
    base = "https://huggingface.co/datasets/asimokby/Turkish-Spelling-Dictionary/resolve/main"
    for fname in ["base_turkish_misspellings.csv", "expanded_turkish_misspellings.csv", "README.md"]:
        download_file(f"{base}/{fname}", ASIMOKBY_DIR / fname, timeout=120)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1048576), b""):
            h.update(chunk)
    return h.hexdigest()


def count_lines(path: Path) -> int:
    with open(path, "rb") as f:
        return sum(1 for _ in f)


def update_manifest_and_readme(query_stats: Dict[str, Dict[str, int]]) -> None:
    manifest_path = BASE_DIR / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    else:
        manifest = {"base_dir": str(BASE_DIR)}

    lex_entries = []
    for p in sorted(LEX_DIR.rglob("*")):
        if not p.is_file():
            continue
        lex_entries.append({
            "path": str(p.relative_to(BASE_DIR)),
            "bytes": p.stat().st_size,
            "lines": count_lines(p),
            "sha256": sha256_file(p),
        })

    for name, st in query_stats.items():
        st["sha256"] = sha256_file(BASE_DIR / st["path"])

    manifest["generated_at_utc"] = datetime.now(timezone.utc).isoformat()
    manifest["lexicons_external"] = lex_entries
    manifest["queries_plain"] = query_stats
    manifest["queries_plain_totals"] = {
        "files": len(query_stats),
        "bytes": sum(s["bytes"] for s in query_stats.values()),
        "lines": sum(s["lines"] for s in query_stats.values()),
        "tokens": sum(s["tokens"] for s in query_stats.values()),
    }
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"[INFO] Updated {manifest_path}")

    readme_path = BASE_DIR / "README.md"
    existing = readme_path.read_text(encoding="utf-8") if readme_path.exists() else ""
    marker = "## Search Query Datasets (`queries/`)"
    if marker in existing:
        existing = existing[:existing.index(marker)].rstrip()

    tot_lines = manifest["queries_plain_totals"]["lines"]
    tot_tokens = manifest["queries_plain_totals"]["tokens"]
    tot_mb = manifest["queries_plain_totals"]["bytes"] / (1024 * 1024)

    section = [
        "",
        "## Search Query Datasets (`queries/`)",
        "",
        "| File | Source / Nature | Lines | Whitespace Tokens | Size (MB) |",
        "| :--- | :--- | ---: | ---: | ---: |",
    ]
    desc_map = {
        "bing_turkey_queries_unique.txt": "Microsoft `BingCoronavirusQuerySet` (`Country=Turkey`, 2020–2021 real web queries)",
        "mkqa_google_tr_clean_10k.txt": "Apple `MKQA` (`tr`, 10,000 human-translated Google Search queries — Clean-Query Control)",
        "ecommerce_customer_queries.txt": "`kdrhnn/turkce-eticaret-niyet-analizi` (noisy Turkish e-commerce customer queries)",
        "eksisozluk_topic_titles.txt": "Unique Ekşi Sözlük topic titles (`title`/`topic` — lowercase noun-phrase/entity queries)",
        "forum_search_thread_slugs.txt": "Unique `ForumSohbetleri` thread slugs (ASCII-degraded product/troubleshooting queries)",
    }
    for name in sorted(query_stats):
        st = query_stats[name]
        mb = st["bytes"] / (1024 * 1024)
        section.append(
            f"| `{name}` | {desc_map.get(name, st['path'])} | {st['lines']:,} | {st['tokens']:,} | {mb:.2f} |"
        )
    section.append(
        f"| **Total (`queries/plain/`)** | **5 query sources** | **{tot_lines:,}** | **{tot_tokens:,}** | **{tot_mb:.2f}** |"
    )
    section.extend([
        "",
        "## Auxiliary Spelling Dictionary (`lexicons_external/asimokby_spelling_dict/`)",
        "",
        "- `base_turkish_misspellings.csv` & `expanded_turkish_misspellings.csv` (`asimokby/Turkish-Spelling-Dictionary`, wrong→correct word pairs incl. numeral/apostrophe variants)",
        "",
    ])

    readme_path.write_text(existing.rstrip() + "\n" + "\n".join(section), encoding="utf-8")
    print(f"[INFO] Updated {readme_path}")


def main() -> None:
    ensure_dirs()
    query_stats: Dict[str, Dict[str, int]] = {}

    def rec(name: str, nl: int, nt: int) -> None:
        p = Q_PLAIN_DIR / name
        query_stats[name] = {
            "path": str(p.relative_to(BASE_DIR)),
            "bytes": p.stat().st_size,
            "lines": nl,
            "tokens": nt,
        }

    nl, nt = process_bing_turkey_queries()
    rec("bing_turkey_queries_unique.txt", nl, nt)

    nl, nt = process_mkqa()
    rec("mkqa_google_tr_clean_10k.txt", nl, nt)

    nl, nt = process_ecommerce_intent()
    rec("ecommerce_customer_queries.txt", nl, nt)

    nl, nt = extract_eksisozluk_titles()
    rec("eksisozluk_topic_titles.txt", nl, nt)

    nl, nt = extract_forum_slugs()
    rec("forum_search_thread_slugs.txt", nl, nt)

    download_asimokby_dict()
    update_manifest_and_readme(query_stats)
    print("[DONE] Query datasets and auxiliary spelling dictionary prepared.")


if __name__ == "__main__":
    main()
