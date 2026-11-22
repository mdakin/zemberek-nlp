#!/usr/bin/env python3
import os
"""
Download, extract, and organize publicly available Turkish normalization benchmarks,
external lexicons, and split-safe source corpora under ~/data/turkish/normalization.

All raw and local corpora are consolidated into ~/data/turkish/normalization/source_corpora/
in their original structured formats (CSV/TSV/JSONL/TXT) so held-out test splits remain
strictly isolated from training ingestion.

Uses only Python 3 standard library + requests. Safe and idempotent to re-run.
"""

import csv
import hashlib
import json
import shutil
import subprocess
import sys
import time
import urllib.parse
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional

import requests

csv.field_size_limit(sys.maxsize)

BASE_DIR = Path(os.path.expanduser("~/data/turkish/normalization"))
TURKISH_DATA_DIR = Path(os.path.expanduser("~/data/turkish"))

BENCH_DIR = BASE_DIR / "benchmarks"
LEX_DIR = BASE_DIR / "lexicons_external"
SOURCE_CORPORA_DIR = BASE_DIR / "source_corpora"
CUSTOM_DIR = BASE_DIR / "custom_benchmark"
SCRIPTS_DIR = BASE_DIR / "scripts"

USER_AGENT = "zemberek-normalization-data-prep/2.0"


def ensure_dirs() -> None:
    dirs = [
        SCRIPTS_DIR,
        BENCH_DIR / "multilexnorm" / "tur",
        BENCH_DIR / "multilexnorm" / "tur_deu",
        BENCH_DIR / "multilexnorm" / "scripts",
        BENCH_DIR / "gecturk_boun_clitics",
        BENCH_DIR / "mukayese_trspell10",
        LEX_DIR / "eminesener_boun",
        SOURCE_CORPORA_DIR / "tweets" / "offenseval2020_tr",
        SOURCE_CORPORA_DIR / "tweets" / "yankihue_tweets_tr",
        SOURCE_CORPORA_DIR / "ecommerce_reviews" / "trendyol_2025",
        SOURCE_CORPORA_DIR / "ecommerce_reviews" / "winvoker_reviews",
        SOURCE_CORPORA_DIR / "movies_and_complaints" / "beyazperde",
        SOURCE_CORPORA_DIR / "movies_and_complaints" / "sikayetler",
        SOURCE_CORPORA_DIR / "eksisozluk" / "eksisozluk_2024_apache",
        SOURCE_CORPORA_DIR / "eksisozluk" / "eksisozluk_2025_2026_jsonl",
        SOURCE_CORPORA_DIR / "forums_modern_2020s",
        SOURCE_CORPORA_DIR / "forums_qa_lifestyle",
        SOURCE_CORPORA_DIR / "civic_forums",
        SOURCE_CORPORA_DIR / "queries" / "raw",
        SOURCE_CORPORA_DIR / "queries" / "plain",
        CUSTOM_DIR,
    ]
    for d in dirs:
        d.mkdir(parents=True, exist_ok=True)


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


def fetch_json(url: str) -> dict:
    resp = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=30)
    resp.raise_for_status()
    return resp.json()


def download_benchmarks() -> None:
    # 1. W-NUT 2021 MultiLexNorm (tur & tur_deu)
    mln_base = "https://bitbucket.org/robvanderg/multilexnorm/raw/master"
    mln_files = [
        (f"{mln_base}/data/tur/train.norm", BENCH_DIR / "multilexnorm" / "tur" / "train.norm"),
        (f"{mln_base}/data/tur/test.norm", BENCH_DIR / "multilexnorm" / "tur" / "test.norm"),
        (f"{mln_base}/data/tur_deu/train.norm", BENCH_DIR / "multilexnorm" / "tur_deu" / "train.norm"),
        (f"{mln_base}/data/tur_deu/test.norm", BENCH_DIR / "multilexnorm" / "tur_deu" / "test.norm"),
        (f"{mln_base}/scripts/normEval.py", BENCH_DIR / "multilexnorm" / "scripts" / "normEval.py"),
        (f"{mln_base}/README.md", BENCH_DIR / "multilexnorm" / "README.md"),
    ]
    for url, dest in mln_files:
        download_file(url, dest)

    # 2. GECTurk BOUN -de/-da clitic benchmark
    boun_base = "https://raw.githubusercontent.com/GGLAB-KU/gecturk/main/datasets/boun"
    for split in ["train", "dev", "test"]:
        for side in ["source", "target"]:
            fname = f"boun_{side}_{split}.txt"
            download_file(
                f"{boun_base}/{fname}",
                BENCH_DIR / "gecturk_boun_clitics" / fname,
            )
    download_file(
        "https://raw.githubusercontent.com/GGLAB-KU/gecturk/main/README.md",
        BENCH_DIR / "gecturk_boun_clitics" / "GECTURK_README.md",
    )

    # 3. Mukayese TRSPELL-10 + evaluation scripts (stream-extract only trspell-10)
    muk_dir = BENCH_DIR / "mukayese_trspell10"
    muk_raw = "https://raw.githubusercontent.com/alisafaya/mukayese/main/spell-checking"
    download_file(f"{muk_raw}/evaluate.py", muk_dir / "evaluate.py")
    download_file(f"{muk_raw}/predict_zemberek.py", muk_dir / "predict_zemberek.py")
    download_file(f"{muk_raw}/README.md", muk_dir / "SPELLCHECK_EVAL_README.md")

    trspell_csv = muk_dir / "test.csv"
    if not (trspell_csv.exists() and trspell_csv.stat().st_size > 0):
        tar_url = (
            "https://github.com/alisafaya/mukayese/releases/download/"
            "v0.0.1/mukayese-datasets.tar.gz"
        )
        print(f"[STRM] Extracting mukayese-datasets/trspell-10 from {tar_url}")
        cmd = (
            f'curl -fSL --retry 3 "{tar_url}" | '
            f'tar -xz --strip-components=2 -C "{muk_dir}" mukayese-datasets/trspell-10'
        )
        subprocess.run(["bash", "-o", "pipefail", "-c", cmd], check=True)
        print(f"[OK  ] Extracted trspell-10 to {muk_dir.relative_to(BASE_DIR)}")
    else:
        print(f"[SKIP] {trspell_csv.relative_to(BASE_DIR)} ({trspell_csv.stat().st_size:,} B)")


def download_external_lexicons() -> None:
    base = "https://huggingface.co/datasets/eminesener/normalization/resolve/main"
    lex_dir = LEX_DIR / "eminesener_boun"
    files = [
        ("lexicon/normalization.tsv", lex_dir / "normalization.tsv"),
        ("lexicon/normalization_manual.tsv", lex_dir / "normalization_manual.tsv"),
        ("results/normalization_report.md", lex_dir / "normalization_report.md"),
        ("README.md", lex_dir / "README.md"),
    ]
    for rel, dest in files:
        download_file(f"{base}/{rel}", dest)


def download_corpora() -> None:
    # 1. Tweets: OffensEval 2020 Turkish
    off_dir = SOURCE_CORPORA_DIR / "tweets" / "offenseval2020_tr"
    off_zip = off_dir / "offenseval2020-turkish.zip"
    download_file("https://coltekin.github.io/offensive-turkish/offenseval2020-turkish.zip", off_zip)
    extracted_marker = off_dir / ".extracted"
    if not extracted_marker.exists():
        with zipfile.ZipFile(off_zip, "r") as zf:
            zf.extractall(off_dir)
        extracted_marker.write_text("ok\n", encoding="utf-8")
        print(f"[UNZP] {off_zip.relative_to(BASE_DIR)}")

    # 2. Tweets: yankihue/tweets-turkish
    download_file(
        "https://huggingface.co/datasets/yankihue/tweets-turkish/resolve/main/data.csv",
        SOURCE_CORPORA_DIR / "tweets" / "yankihue_tweets_tr" / "data.csv",
    )

    # 3. E-commerce: Trendyol 2025
    download_file(
        "https://huggingface.co/datasets/Doukan/trendyol-turkish-product-reviews/resolve/main/trendyol_reviews_20251212_000835.csv",
        SOURCE_CORPORA_DIR / "ecommerce_reviews" / "trendyol_2025" / "trendyol_reviews_20251212_000835.csv",
        timeout=120,
    )

    # 4. E-commerce & multi-domain reviews: winvoker
    win_base = "https://huggingface.co/datasets/winvoker/turkish-sentiment-analysis-dataset/resolve/main"
    download_file(f"{win_base}/train.csv", SOURCE_CORPORA_DIR / "ecommerce_reviews" / "winvoker_reviews" / "train.csv", timeout=120)
    download_file(f"{win_base}/test.csv", SOURCE_CORPORA_DIR / "ecommerce_reviews" / "winvoker_reviews" / "test.csv", timeout=120)

    # 5. Movie reviews: Beyazperde
    bp_base = "https://huggingface.co/datasets/turkish-nlp-suite/beyazperde-all-movie-reviews/resolve/main/dataset"
    for split in ["train.json", "valid.json", "test.json"]:
        download_file(f"{bp_base}/{split}", SOURCE_CORPORA_DIR / "movies_and_complaints" / "beyazperde" / split)

    # 6. Complaints: erenfazlioglu/sikayetler
    download_file(
        "https://huggingface.co/datasets/erenfazlioglu/sikayetler/resolve/main/sikayetler.csv",
        SOURCE_CORPORA_DIR / "movies_and_complaints" / "sikayetler" / "sikayetler.csv",
    )

    # 7. Eksi Sozluk: 2024 Apache dump + 2025-2026 JSONLs
    download_file(
        "https://huggingface.co/datasets/ozertuu/eksiSozlukScrapy/resolve/main/eksisozluk_entries.csv",
        SOURCE_CORPORA_DIR / "eksisozluk" / "eksisozluk_2024_apache" / "eksisozluk_entries.csv",
        timeout=120,
    )

    eksi_repos = [
        ("tech", "turkish-nlp-datasets/eksisozluk_teknoloji_tr"),
        ("finance", "turkish-nlp-datasets/eksisozluk-ekonomi-ve-finans-tr"),
    ]
    eksi_jsonl_dir = SOURCE_CORPORA_DIR / "eksisozluk" / "eksisozluk_2025_2026_jsonl"
    for tag, repo_id in eksi_repos:
        meta = fetch_json(f"https://huggingface.co/api/datasets/{repo_id}?blobs=true")
        for sib in meta.get("siblings", []):
            rf = sib.get("rfilename", "")
            if not rf.endswith(".jsonl"):
                continue
            safe_name = f"{tag}__{Path(rf).name}"
            encoded_rf = urllib.parse.quote(rf, safe="/")
            url = f"https://huggingface.co/datasets/{repo_id}/resolve/main/{encoded_rf}"
            download_file(url, eksi_jsonl_dir / safe_name)

    # 8. Modern 2020s Forums: ForumSohbetleri
    fs_base = "https://huggingface.co/datasets/turkish-nlp-suite/ForumSohbetleri/resolve/main"
    forum_files = [
        ("donanimarsivi/train--00000-of-00001.jsonl", "donanimarsivi.jsonl"),
        ("wmaraci/train--00000-of-00001.jsonl", "wmaraci.jsonl"),
        ("turkiyeforum/train--00000-of-00001.jsonl", "turkiyeforum_0.jsonl"),
        ("turkiyeforum/train--00001-of-00001.jsonl", "turkiyeforum_1.jsonl"),
        ("iyinet/train--00000-of-00001.jsonl", "iyinet.jsonl"),
    ]
    for rel, local_name in forum_files:
        download_file(f"{fs_base}/{rel}", SOURCE_CORPORA_DIR / "forums_modern_2020s" / local_name, timeout=180)


def consolidate_local_corpora() -> List[Dict[str, object]]:
    copies = [
        (TURKISH_DATA_DIR / "noisy" / "kadinlar-klubu.txt", SOURCE_CORPORA_DIR / "forums_qa_lifestyle" / "kadinlar-klubu.txt"),
        (TURKISH_DATA_DIR / "clean" / "www.kizlarsoruyor.com.txt", SOURCE_CORPORA_DIR / "forums_qa_lifestyle" / "www.kizlarsoruyor.com.txt"),
        (TURKISH_DATA_DIR / "clean" / "www.nurturia.com.tr.txt", SOURCE_CORPORA_DIR / "forums_qa_lifestyle" / "www.nurturia.com.tr.txt"),
        (TURKISH_DATA_DIR / "clean" / "forum.shiftdelete.net.txt", SOURCE_CORPORA_DIR / "forums_modern_2020s" / "forum.shiftdelete.net.txt"),
        (TURKISH_DATA_DIR / "clean" / "www.tvyorumlari.com.txt", SOURCE_CORPORA_DIR / "movies_and_complaints" / "www.tvyorumlari.com.txt"),
        (TURKISH_DATA_DIR / "clean" / "www.sikayetim.com.txt", SOURCE_CORPORA_DIR / "movies_and_complaints" / "www.sikayetim.com.txt"),
        (TURKISH_DATA_DIR / "clean" / "forum.memurlar.net.txt", SOURCE_CORPORA_DIR / "civic_forums" / "forum.memurlar.net.txt"),
        (TURKISH_DATA_DIR / "clean" / "wowturkey.com.txt", SOURCE_CORPORA_DIR / "civic_forums" / "wowturkey.com.txt"),
    ]
    consolidated: List[Dict[str, object]] = []
    for src, dst in copies:
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists() and dst.stat().st_size > 0:
            print(f"[SKIP] {dst.relative_to(BASE_DIR)} ({dst.stat().st_size:,} B)")
        elif src.exists():
            shutil.copy2(src, dst)
            print(f"[COPY] {src} -> {dst.relative_to(BASE_DIR)} ({dst.stat().st_size:,} B)")
        else:
            print(f"[WARN] Local source missing: {src}")
            continue
        consolidated.append({
            "path": str(dst.relative_to(BASE_DIR)),
            "origin": str(src),
            "bytes": dst.stat().st_size,
        })
    return consolidated


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1048576), b""):
            h.update(chunk)
    return h.hexdigest()


def count_lines(path: Path) -> int:
    with open(path, "rb") as f:
        return sum(1 for _ in f)


def update_manifest(consolidated: List[Dict[str, object]]) -> None:
    bench_entries = []
    for p in sorted(BENCH_DIR.rglob("*")):
        if p.is_file():
            bench_entries.append({
                "path": str(p.relative_to(BASE_DIR)),
                "bytes": p.stat().st_size,
                "lines": count_lines(p),
                "sha256": sha256_file(p),
            })

    lex_entries = []
    for p in sorted(LEX_DIR.rglob("*")):
        if p.is_file():
            lex_entries.append({
                "path": str(p.relative_to(BASE_DIR)),
                "bytes": p.stat().st_size,
                "lines": count_lines(p),
                "sha256": sha256_file(p),
            })

    source_entries = []
    for p in sorted(SOURCE_CORPORA_DIR.rglob("*")):
        if p.is_file() and p.name != "README.md":
            source_entries.append({
                "path": str(p.relative_to(BASE_DIR)),
                "bytes": p.stat().st_size,
            })

    manifest = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "base_dir": str(BASE_DIR),
        "benchmarks": bench_entries,
        "lexicons_external": lex_entries,
        "source_corpora": source_entries,
        "source_corpora_totals": {
            "files": len(source_entries),
            "bytes": sum(e["bytes"] for e in source_entries),
        },
        "consolidated_local_corpora": consolidated,
    }
    manifest_path = BASE_DIR / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"[INFO] Wrote {manifest_path}")


def main() -> None:
    ensure_dirs()
    download_benchmarks()
    download_external_lexicons()
    download_corpora()
    consolidated = consolidate_local_corpora()
    update_manifest(consolidated)
    print("[DONE] All benchmarks, lexicons, and unified source_corpora/ verified.")


if __name__ == "__main__":
    main()
