#!/usr/bin/env python3
"""
Turkish Lexical Normalization Engine using Google Gemini Batch API (gemini-2.5-pro).

Workflow:
  1. Pre-tokenizes noisy sentences with Zemberek's TurkishTokenizer.DEFAULT via
     TokenizeForNormalization.java.
  2. Formats each sentence with indexed tokens (#0, #1, ...) and requests strict
     token-aligned lexical normalization into standard TDK Turkish, expanding chat
     abbreviations and handling 1:N splits (space-separated) and N:1 merges ("").
  3. Submits requests to the Gemini Batch API (batchGenerateContent, 50% discount)
     with crash-safe registry checkpointing, auto-resume, and targeted single-sentence
     retries for any omitted/malformed items.
  4. Exports normalized JSONL, W-NUT MultiLexNorm .norm TSV, tab-separated pairs, and
     a summary JSON report.
"""

import os
import re
import sys
import math
import json
import time
import argparse
import subprocess
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple, Set

try:
    import requests
except ImportError:
    requests = None


# ==============================================================================
# 1. TURKISH LEXICAL NORMALIZATION SYSTEM INSTRUCTIONS (v4.0 Lean Principles)
# ==============================================================================

_CORE_RULES = """You are a Turkish computational linguist performing strict token-aligned orthographic normalization into standard TDK Turkish.

PRINCIPLES:
- Fix ONLY orthography and word boundaries: missing Turkish diacritics (add circumflexes only on minimal pairs like hâlâ/kâr), spelling/typos, expressive letter elongations, spoken suffix contractions, chat shorthands, clitic/compound spacing, ASCII apostrophes ('), and TDK capitalization.
- Conservative do-no-harm: If a token is already valid in context, copy it unchanged. NEVER paraphrase, replace slang/colloquial words, brands, or loanwords ("kanka", "valla", "lan", "tweetin", "Airfryer"), reorder words, invent/delete punctuation, or expand digits/unit abbreviations ("2'sini", "200 TL", "5 dk", "13.000 km").
- Profanity: Do not censor. Normalize single-word vulgarisms (including masked/leetspeak forms) to standard spelling, and keep multi-word vulgar acronyms as canonical lowercase 1-token forms ("amk", "aq", "oç", "mk", "sg", "bsg").
- Invariants: Copy Punctuation, Emoji, Emoticon, @mentions, #hashtags, URLs, and random keyboard laughter ("sjsjsj", "asdasd") verbatim.
- Casing: Follow TDK capitalization (proper nouns, titles, holidays, acronyms, and sentence starts after ".", "!", "?"; convert ALL-CAPS shouting and random mid-sentence casing to standard casing). However, if an entire single-sentence input was written in lowercase, keep a non-proper first word lowercase.

TOKEN-SLOT ALIGNMENT (#0 .. #<N-1>):
Map EVERY token index ("0" .. "<N-1>") to its normalized string:
- 1:1 rewrite: keep in the same slot (#0 "paylas" -> {"0": "paylaş"}).
- 1:N split (detached clitics, units, or open compounds): space-separate inside that SAME slot (#0 "fıkrımızıde" -> {"0": "fikrimizi de"}; #0 "200tl" -> {"0": "200 TL"}).
- N:1 merge (detached suffixes/apostrophes, closed compounds, or symbol-masked words): put the merged form in the FIRST slot and "" in absorbed slots:
  #0 "ankara", #1 "da" -> {"0": "Ankara'da", "1": ""}
  #0 "hiç", #1 "birşey" -> {"0": "hiçbir", "1": "şey"}
  #0 "k", #1 "*", #2 "tü" -> {"0": "kötü", "1": "", "2": ""}"""

SINGLE_SYSTEM_INSTRUCTION = (
    _CORE_RULES
    + '\n\nOUTPUT FORMAT:\n'
    + 'Return ONLY a single valid JSON object mapping every token index ("0" .. "<N-1>") to its normalized string:\n'
    + '{"0": "...", "1": "..."}'
)

GROUP_SYSTEM_INSTRUCTION = (
    _CORE_RULES
    + '\n\nGROUPED INPUT / OUTPUT FORMAT:\n'
    + '- You are given independent sentences [S0], [S1], ... Normalize each sentence strictly in isolation using ONLY its own context; never let noisy sentences bias you into over-editing clean sentences.\n'
    + '- Return ONLY a single valid JSON object mapping every sentence label ("S0", "S1", ...) to its complete token-index map ("0" .. "<N-1>"):\n'
    + '  {"S0": {"0": "...", "1": "..."}, "S1": {"0": "...", "1": "..."}}'
)

SYSTEM_INSTRUCTION = SINGLE_SYSTEM_INSTRUCTION


# ==============================================================================
# 2. MODEL, REASONING PRESETS, SAFETY SETTINGS & ENV LOADING
# ==============================================================================

REASONING_BUDGET_PRESETS = {
    "low": 512,
    "medium": 1024,
    "high": 2048,
}

SAFETY_SETTINGS = [
    {"category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_NONE"},
    {"category": "HARM_CATEGORY_HATE_SPEECH", "threshold": "BLOCK_NONE"},
    {"category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold": "BLOCK_NONE"},
    {"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": "BLOCK_NONE"},
]

RETRYABLE_HTTP_CODES = {429, 500, 502, 503, 504}


def load_env(filepath: str = ".env") -> None:
    if os.path.exists(filepath):
        with open(filepath, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    key = k.strip()
                    val = v.strip().strip("'").strip('"')
                    if key and key not in os.environ:
                        os.environ[key] = val


def tr_lower(s: str) -> str:
    """Turkish-locale lowercase preserving İ -> i and I -> ı."""
    return s.replace("İ", "i").replace("I", "ı").lower()


# ==============================================================================
# 3. PROMPT BUILDER & TOKENIZATION STAGE
# ==============================================================================

def format_normalization_prompt(rec: Dict[str, Any]) -> str:
    """Builds the per-sentence prompt with indexed Zemberek tokens."""
    lines = [f'"{rec["text"]}"']
    for t in rec.get("tokens", []):
        lines.append(f'#{t["index"]} "{t["surface"]}"')
    return "\n".join(lines)


def format_group_normalization_prompt(group_recs: List[Dict[str, Any]]) -> str:
    """Builds a multi-sentence grouped prompt labeled [S0], [S1], ..."""
    blocks: List[str] = []
    for k, rec in enumerate(group_recs):
        lines = [f'[S{k}] "{rec["text"]}"']
        for t in rec.get("tokens", []):
            lines.append(f'#{t["index"]} "{t["surface"]}"')
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)


def ensure_tokenized_jsonl(
    repo_root: Path,
    input_path: Path,
    tokenized_jsonl_path: Path,
) -> None:
    """Ensures Zemberek pre-tokenized JSONL exists (regenerates if missing or stale)."""
    need_run = (
        not tokenized_jsonl_path.exists()
        or tokenized_jsonl_path.stat().st_size == 0
        or tokenized_jsonl_path.stat().st_mtime < input_path.stat().st_mtime
    )
    if not need_run:
        print(f"  [STAGE 1] Found Zemberek tokenized JSONL: {tokenized_jsonl_path}")
        return

    java_src = repo_root / "experiment/scripts/normalization/TokenizeForNormalization.java"
    if not java_src.exists():
        raise FileNotFoundError(f"Tokenizer helper not found: {java_src}")

    if not (repo_root / "tokenization/target/classes").exists():
        subprocess.run(
            ["mvn", "test-compile", "-pl", "tokenization", "-am", "-DskipTests", "-q"],
            cwd=str(repo_root),
            check=True,
        )

    cp_cache = repo_root / "experiment/target/miner_v2_deps.cp"
    if not cp_cache.exists():
        cp_cache.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            [
                "mvn",
                "-pl",
                "apps",
                "-am",
                "test-compile",
                "dependency:build-classpath",
                "-Dmdep.excludeGroupIds=zemberek-nlp",
                f"-Dmdep.outputFile={cp_cache}",
                "-q",
            ],
            cwd=str(repo_root),
            check=True,
        )

    deps_cp = cp_cache.read_text(encoding="utf-8").strip()
    classpath = (
        f"{repo_root / 'tokenization/target/classes'}:"
        f"{repo_root / 'core/target/classes'}:"
        f"{deps_cp}"
    )
    tokenized_jsonl_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["java", "-cp", classpath, str(java_src), str(input_path), str(tokenized_jsonl_path)]
    print(f"  [STAGE 1] Running TokenizeForNormalization.java on {input_path.name}...")
    subprocess.run(cmd, cwd=str(repo_root), check=True)


# ==============================================================================
# 4. GEMINI BATCH API + SINGLE-SENTENCE RETRY
# ==============================================================================

def build_generation_config(
    thinking_budget: int,
    thinking_level: Optional[str] = None,
    max_output_tokens: int = 32768,
    temperature: Optional[float] = None,
) -> Dict[str, Any]:
    gen_cfg: Dict[str, Any] = {
        "responseMimeType": "application/json",
        "maxOutputTokens": max_output_tokens,
    }
    if temperature is not None:
        gen_cfg["temperature"] = temperature
    if thinking_level:
        gen_cfg["thinkingConfig"] = {"thinkingLevel": thinking_level.upper()}
    elif thinking_budget >= 0:
        gen_cfg["thinkingConfig"] = {"thinkingBudget": thinking_budget}
    return gen_cfg


def build_batch_payload(
    chunk_groups: List[List[Dict[str, Any]]],
    batch_display_name: str,
    thinking_budget: int,
    thinking_level: Optional[str] = None,
    group_size: int = 25,
    max_output_tokens: int = 32768,
    temperature: Optional[float] = None,
) -> Dict[str, Any]:
    gen_cfg = build_generation_config(
        thinking_budget, thinking_level, max_output_tokens=max_output_tokens, temperature=temperature
    )
    requests_list = []
    for grp in chunk_groups:
        if group_size <= 1:
            sys_inst = SINGLE_SYSTEM_INSTRUCTION
            prompt = format_normalization_prompt(grp[0])
        else:
            sys_inst = GROUP_SYSTEM_INSTRUCTION
            prompt = format_group_normalization_prompt(grp)
        requests_list.append({
            "request": {
                "systemInstruction": {"parts": [{"text": sys_inst}]},
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": gen_cfg,
                "safetySettings": SAFETY_SETTINGS,
            }
        })
    return {
        "batch": {
            "displayName": batch_display_name,
            "inputConfig": {
                "requests": {
                    "requests": requests_list
                }
            },
        }
    }


def submit_batch_job(
    api_key: str,
    payload: Dict[str, Any],
    model: str,
    max_retries: int = 5,
) -> Dict[str, Any]:
    if requests is None:
        raise RuntimeError("The 'requests' package is required to call the Gemini API.")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:batchGenerateContent"
    headers = {"x-goog-api-key": api_key}
    for attempt in range(max_retries):
        try:
            resp = requests.post(url, headers=headers, json=payload, timeout=120)
            if resp.status_code == 200:
                return resp.json()
            elif resp.status_code == 429 and "spending cap" in resp.text.lower():
                raise RuntimeError(f"Batch submission blocked by AI Studio project spending cap (HTTP 429): {resp.text.strip()}")
            elif resp.status_code in RETRYABLE_HTTP_CODES:
                wait_s = (2 ** attempt) * 2.0 + 1.0
                print(f"    [HTTP {resp.status_code}] Transient error submitting batch ({resp.text[:160].strip()}); waiting {wait_s:.1f}s...")
                time.sleep(wait_s)
            else:
                raise RuntimeError(f"Batch submission failed (HTTP {resp.status_code}): {resp.text}")
        except requests.exceptions.RequestException as e:
            if attempt == max_retries - 1:
                raise
            wait_s = (2 ** attempt) * 2.0 + 1.0
            print(f"    [NET WARN] RequestException submitting batch ({type(e).__name__}); retrying in {wait_s:.1f}s...")
            time.sleep(wait_s)
    raise RuntimeError("Exceeded max retries submitting batch.")


def poll_batch_job(
    api_key: str,
    batch_name: str,
    max_retries: int = 5,
) -> Dict[str, Any]:
    url = f"https://generativelanguage.googleapis.com/v1beta/{batch_name}"
    headers = {"x-goog-api-key": api_key}
    for attempt in range(max_retries):
        try:
            resp = requests.get(url, headers=headers, timeout=120)
            if resp.status_code == 200:
                return resp.json()
            elif resp.status_code in RETRYABLE_HTTP_CODES:
                time.sleep((2 ** attempt) * 2.0 + 1.0)
            else:
                raise RuntimeError(f"Failed polling {batch_name} (HTTP {resp.status_code}): {resp.text}")
        except requests.exceptions.RequestException:
            if attempt == max_retries - 1:
                raise
            time.sleep((2 ** attempt) * 2.0 + 1.0)
    raise RuntimeError(f"Exceeded max retries polling {batch_name}")


def extract_text_from_candidate(resp_obj: Dict[str, Any]) -> str:
    """Extracts the non-thought text part from a Gemini candidate response."""
    cands = resp_obj.get("candidates", [])
    if not cands:
        return ""
    parts = cands[0].get("content", {}).get("parts", [])
    for part in reversed(parts):
        if "text" in part and not part.get("thought", False):
            return part["text"]
    return parts[-1].get("text", "") if parts else ""


def _normalize_token_map(raw_map: Dict[Any, Any]) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for k, v in raw_map.items():
        k_norm = str(k).lstrip("#").strip()
        if isinstance(v, str):
            out[k_norm] = " ".join(v.strip().split())
        elif v is None:
            continue
        elif isinstance(v, (int, float)) and not isinstance(v, bool):
            out[k_norm] = str(v)
    return out


def _extract_json_dict(text: Optional[str]) -> Optional[Dict[Any, Any]]:
    if not text:
        return None
    cleaned = text.strip()
    if cleaned.startswith("```json"):
        cleaned = cleaned[7:]
    elif cleaned.startswith("```"):
        cleaned = cleaned[3:]
    if cleaned.endswith("```"):
        cleaned = cleaned[:-3]
    cleaned = cleaned.strip()

    data = None
    try:
        data = json.loads(cleaned)
    except Exception:
        m = re.search(r"(\{.*\})", cleaned, re.DOTALL)
        if m:
            try:
                data = json.loads(m.group(1))
            except Exception:
                data = None
    return data if isinstance(data, dict) else None


def parse_normalization_json(text: Optional[str]) -> Optional[Dict[str, str]]:
    """
    Parses the LLM's JSON response mapping token index -> normalized string.
    Preserves "" for N:1 absorbed continuation tokens and normalizes internal whitespace.
    """
    data = _extract_json_dict(text)
    if data is None:
        return None
    out = _normalize_token_map(data)
    return out if out else None


def parse_group_normalization_json(
    text: Optional[str],
    group_recs: List[Dict[str, Any]],
) -> Dict[int, Dict[str, str]]:
    """
    Parses grouped JSON response mapping S0..Sk -> {token_idx: norm_str}.
    Returns a dict keyed by 0-based group offset k.
    """
    data = _extract_json_dict(text)
    if data is None:
        return {}

    result: Dict[int, Dict[str, str]] = {}
    for k, rec in enumerate(group_recs):
        sid = rec.get("sentence_id", "")
        candidate_keys = (f"S{k}", f"[S{k}]", f"s{k}", str(k), sid)
        sub = None
        for ck in candidate_keys:
            if ck and isinstance(data.get(ck), dict):
                sub = data[ck]
                break
        if sub is not None:
            norm_map = _normalize_token_map(sub)
            if norm_map:
                result[k] = norm_map
    return result


def is_normalization_complete(rec: Dict[str, Any], norm_map: Optional[Dict[str, str]]) -> bool:
    """Returns True iff every token index in rec has a valid string entry in norm_map."""
    tokens = rec.get("tokens", [])
    if not tokens:
        return True
    if not norm_map:
        return False
    for t in tokens:
        idx_str = str(t["index"])
        if idx_str not in norm_map or not isinstance(norm_map[idx_str], str):
            return False
    if norm_map.get("0", "") == "":
        return False
    if not any(norm_map.get(str(t["index"]), "") != "" for t in tokens):
        return False
    return True


def merge_usage_metadata(base_usage: Dict[str, Any], extra_usage: Dict[str, Any]) -> Dict[str, Any]:
    """Accumulates token usage metadata across batch and retry calls."""
    if not base_usage:
        return dict(extra_usage) if extra_usage else {}
    if not extra_usage:
        return dict(base_usage)
    merged = dict(base_usage)
    for k in ("promptTokenCount", "candidatesTokenCount", "thoughtsTokenCount", "totalTokenCount"):
        merged[k] = int(base_usage.get(k, 0)) + int(extra_usage.get(k, 0))
    return merged


def call_single_sentence_retry(
    api_key: str,
    rec: Dict[str, Any],
    existing_norm: Optional[Dict[str, str]],
    model: str,
    thinking_budget: int,
    thinking_level: Optional[str] = None,
    max_retries: int = 3,
) -> Tuple[Dict[str, str], Dict[str, Any]]:
    """Targeted single-sentence retry if a batch item omitted or misformatted token keys."""
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    headers = {"x-goog-api-key": api_key}
    prompt = format_normalization_prompt(rec)
    payload = {
        "systemInstruction": {"parts": [{"text": SINGLE_SYSTEM_INSTRUCTION}]},
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": build_generation_config(thinking_budget, thinking_level),
        "safetySettings": SAFETY_SETTINGS,
    }
    combined: Dict[str, str] = dict(existing_norm) if existing_norm else {}
    accum_usage: Dict[str, Any] = {}

    for attempt in range(max_retries):
        try:
            resp = requests.post(url, headers=headers, json=payload, timeout=60)
            if resp.status_code == 200:
                data = resp.json()
                accum_usage = merge_usage_metadata(accum_usage, data.get("usageMetadata", {}))
                gen_text = extract_text_from_candidate(data)
                parsed = parse_normalization_json(gen_text)
                if parsed:
                    for t in rec.get("tokens", []):
                        idx_str = str(t["index"])
                        if idx_str in parsed and isinstance(parsed[idx_str], str):
                            combined[idx_str] = parsed[idx_str]
                if is_normalization_complete(rec, combined):
                    return combined, accum_usage
            elif resp.status_code in RETRYABLE_HTTP_CODES:
                time.sleep((2 ** attempt) * 1.5 + 1.0)
                continue
            time.sleep(1.5 * (attempt + 1))
        except Exception:
            time.sleep(1.5 * (attempt + 1))
    return combined, accum_usage


# ==============================================================================
# 5. RECONSTRUCTION, PROFANITY ANNOTATION, QC GATEKEEPER & EXPORTS
# ==============================================================================

TRAILING_PUNCT_CHARS = set(".,?!;:)]}%…»”’")
OPENING_PUNCT_CHARS = set("([{#@«“‘")
INVARIANT_TOKEN_TYPES = {"Emoji", "Emoticon", "Mention", "HashTag", "URL"}
EMOTICON_TAIL_CHARS = set("()DdPp3/|oO*")

# Canonical multi-word expansions for lexicalized Turkish vulgar acronyms.
# Note: "mk", "sg", and "bsg" are matched case-sensitively in lowercase so formal
# ALL-CAPS abbreviations (e.g., "MK") are never falsely expanded.
VULGAR_ACRONYM_EXPANSIONS_ANYCASE: Dict[str, str] = {
    "amk": "amına koyayım",
    "aq": "amına koyayım",
    "oç": "orospu çocuğu",
}
VULGAR_ACRONYM_EXPANSIONS_LOWER_ONLY: Dict[str, str] = {
    "mk": "amına koyayım",
    "sg": "siktir git",
    "bsg": "bir siktir git",
}

EXPLICIT_VULGAR_WORD_RE = re.compile(
    r"^(?:"
    r"sik(?:$|[tmycvlndşrğaeiıouü]\w*)"
    r"|yarr?a[kğ]\w*"
    r"|orospu\w*"
    r"|amc[ıi][kğ]\w*"
    r"|amın(?:[aı]|da|dan)?"
    r"|göt(?:$|ü\w*|ün\w*|e|te|ten|ler\w*|veren\w*|lü[kğ]\w*|le[kğ]\w*)"
    r"|piç(?:$|ler\w*|li[kğ]\w*|in\w*|i|e|te|ten)"
    r"|taş+a[kğ]\w*"
    r"|ibne\w*|puşt\w*|pezeven[kğ]\w*|gavat\w*|kalta[kğ]\w*|kahpe\w*|sürtü[kğ]\w*|yavşa[kğ]\w*"
    r"|sıç\w*"
    r"|bok(?:$|tan|u\w*|un\w*|lu\w*|lar\w*)"
    r")$"
)
BENIGN_VULGAR_PREFIX_EXCEPTIONS = (
    "götür",
    "sıçan",
    "sıçra",
    "sikke",
    "siklet",
    "siklon",
    "siklamen",
    "siklâmen",
    "siklo",
    "sikatif",
    "sika",
    "sikorsk",
    "yarağz",
    "yarağıl",
    "taşağıl",
    "bokluca",
)


def classify_and_expand_subword(word: str) -> Tuple[Optional[str], Optional[str]]:
    """
    Checks a single normalized subword for profanity and optional acronym expansion.
    Returns (profanity_type, expanded_form_or_None).
    """
    if not word:
        return None, None
    # Strip trailing/internal apostrophe suffix if attached to an acronym (e.g. oç'lar)
    base, sep, suffix = word.partition("'")
    base_tr = tr_lower(base)

    exp = None
    if base_tr in VULGAR_ACRONYM_EXPANSIONS_ANYCASE:
        exp = VULGAR_ACRONYM_EXPANSIONS_ANYCASE[base_tr]
    elif base in VULGAR_ACRONYM_EXPANSIONS_LOWER_ONLY:
        exp = VULGAR_ACRONYM_EXPANSIONS_LOWER_ONLY[base]

    if exp is not None:
        if sep and suffix:
            return "acronym", f"{exp}'{suffix}"
        return "acronym", exp

    w_tr = tr_lower(base)
    if any(w_tr.startswith(exc) for exc in BENIGN_VULGAR_PREFIX_EXCEPTIONS):
        return None, None
    if EXPLICIT_VULGAR_WORD_RE.match(w_tr):
        return "explicit", None
    return None, None


def annotate_token_profanity(t: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """
    Annotates a token dict in-place with is_profane, profanity_type, and optional
    norm_expanded (for multi-word vulgar acronyms). Returns summary entry if profane.
    """
    nrm = t.get("norm", "")
    if not nrm:
        return None
    parts = nrm.split(" ")
    expanded_parts: List[str] = []
    found_types: List[str] = []
    has_expansion = False

    for p in parts:
        ptype, pexp = classify_and_expand_subword(p)
        if ptype:
            found_types.append(ptype)
        if pexp is not None:
            expanded_parts.append(pexp)
            has_expansion = True
        else:
            expanded_parts.append(p)

    if not found_types:
        return None

    chosen_type = "acronym" if "acronym" in found_types else "explicit"
    t["is_profane"] = True
    t["profanity_type"] = chosen_type
    if has_expansion:
        t["norm_expanded"] = " ".join(expanded_parts)
    return {
        "index": t["index"],
        "surface": t["surface"],
        "norm": nrm,
        "type": chosen_type,
        **({"norm_expanded": t["norm_expanded"]} if has_expansion else {}),
    }


def _char_edit_distance(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        curr = [i]
        for j, cb in enumerate(b, 1):
            cost = 0 if ca == cb else 1
            curr.append(min(curr[-1] + 1, prev[j] + 1, prev[j - 1] + cost))
        prev = curr
    return prev[-1]


def compute_qc_flags(raw_text: str, norm_text: str, orig_rec: Dict[str, Any], tokens: List[Dict[str, Any]]) -> List[str]:
    """Zero-cost deterministic QC checks to flag ambiguous/suspicious items for Tier-2 audit."""
    flags: List[str] = []
    raw_digits = re.findall(r"\d+", raw_text)
    norm_digits = re.findall(r"\d+", norm_text)
    if raw_digits != norm_digits:
        # Allow leetspeak profanity restorations like s1kt1r -> siktir
        has_leetspeak = any(
            re.search(r"[a-zA-ZçğıöşüÇĞİÖŞÜ]\d+[a-zA-ZçğıöşüÇĞİÖŞÜ]", tok.get("surface", ""))
            for tok in tokens
        )
        if not has_leetspeak:
            flags.append("digit_mismatch")

    # Disharmonic diminutive check (-cığım after voiceless consonant)
    if re.search(r"[pçtkfhsş]c[ıiuü]ğ[ıiuü]m", tr_lower(norm_text)):
        flags.append("voiceless_diminutive_disharmony")

    # Character drift ratio guard (ignoring short chat abbreviation expansions)
    if len(raw_text) >= 20:
        dist = _char_edit_distance(tr_lower(raw_text), tr_lower(norm_text))
        if dist / max(1, len(raw_text)) > 0.45:
            flags.append("high_char_drift")

    # T0_clean_control unexpected lexical rewrite check
    if orig_rec.get("noise_tier") == "T0_clean_control":
        non_trivial_edits = 0
        for tok in tokens:
            s = tok.get("surface", "")
            n = tok.get("norm", "")
            if s != n and tr_lower(s.replace("’", "'")) != tr_lower(n.replace(" ", "")):
                non_trivial_edits += 1
        if non_trivial_edits >= 2:
            flags.append("t0_clean_multi_edit")

    # Casing sanity checks: stray internal ASCII 'I' or un-lowercased ALL-CAPS shouting
    allcaps_long_words = 0
    for tok in tokens:
        if tok.get("type") in INVARIANT_TOKEN_TYPES:
            continue
        for w in tok.get("norm", "").split():
            stem = w.partition("'")[0]
            if len(stem) >= 2 and "I" in stem[1:] and any(c.islower() for c in stem) and stem != "OpenAI":
                flags.append("mid_word_ascii_I")
                break
            if len(stem) >= 5 and stem.isalpha() and stem.isupper():
                allcaps_long_words += 1
    if allcaps_long_words >= 3:
        flags.append("residual_allcaps_shouting")
    return flags


def reconstruct_normalized_text(tokens: List[Dict[str, Any]], use_expanded: bool = False) -> str:
    """
    Detokenizes normalized tokens into clean natural text using punctuation rules
    plus Zemberek raw start/end span adjacency for slashes, decimals, ranges,
    symmetric single/double quotes, ASCII emoticons, and consecutive emojis.
    """
    non_empty: List[Dict[str, Any]] = []
    for t in tokens:
        val = t.get("norm_expanded", t.get("norm", "")) if use_expanded else t.get("norm", "")
        if val:
            non_empty.append((t, val))

    pieces: List[str] = []
    prev_was_opening = False
    prev_attach_next = False
    prev_val = ""
    prev_tok_type = ""
    prev_span_end: Optional[int] = None
    in_double_quotes = False
    in_single_quotes = False

    for pos, (t, val) in enumerate(non_empty):
        cur_start = t.get("start")
        cur_end = t.get("end")
        raw_adjacent = (
            prev_span_end is not None
            and cur_start is not None
            and cur_start == prev_span_end + 1
        )

        # Peek next non-empty token span for spaced-colon ASCII emoticons like "mi :d"
        next_raw_adjacent = False
        next_val = ""
        if pos + 1 < len(non_empty) and cur_end is not None:
            nxt_t, nxt_v = non_empty[pos + 1]
            nxt_start = nxt_t.get("start")
            if nxt_start is not None and nxt_start == cur_end + 1:
                next_raw_adjacent = True
                next_val = nxt_v

        if cur_end is not None:
            prev_span_end = cur_end

        tok_type = t.get("type", "")
        is_single_punct = tok_type == "Punctuation" and len(val) == 1
        is_double_quote = is_single_punct and val == '"'
        is_single_quote = is_single_punct and val == "'"
        is_slash = is_single_punct and val == "/"

        attach_to_prev = False
        next_opening = False
        next_attach = False

        # 1. Consecutive raw-adjacent Unicode emojis (e.g. 🤗⚘, 😂😂😂) stay unsplit
        if tok_type == "Emoji" and prev_tok_type == "Emoji" and raw_adjacent:
            attach_to_prev = True
        # 2. Extra mouth chars immediately glued to an Emoticon (e.g. ":(" + "(" -> ":((")
        elif prev_tok_type == "Emoticon" and raw_adjacent and is_single_punct and val in EMOTICON_TAIL_CHARS:
            attach_to_prev = True
        # 3. Spaced ASCII emoticon starting with ":" or ";" (e.g. "gibi mi :d", "selam ;)")
        elif (
            is_single_punct
            and val in {":", ";"}
            and not raw_adjacent
            and next_raw_adjacent
            and len(next_val) <= 2
            and next_val[:1] in EMOTICON_TAIL_CHARS
        ):
            attach_to_prev = False
            next_opening = True
        # 4. Numeric range hyphen glued to preceding number (e.g. "%30" + "-40" -> "%30-40")
        elif (
            raw_adjacent
            and len(val) > 1
            and val[0] == "-"
            and val[1].isdigit()
            and prev_val
            and prev_val[-1].isdigit()
        ):
            attach_to_prev = True
        elif is_double_quote:
            if in_double_quotes:
                attach_to_prev = True
                in_double_quotes = False
            else:
                next_opening = True
                in_double_quotes = True
        elif is_single_quote:
            if in_single_quotes:
                attach_to_prev = True
                in_single_quotes = False
            else:
                next_opening = True
                in_single_quotes = True
        elif is_slash:
            if raw_adjacent:
                attach_to_prev = True
                next_attach = True
        elif tok_type == "Punctuation" and val and all(ch in TRAILING_PUNCT_CHARS for ch in val):
            attach_to_prev = (val != "%") or raw_adjacent
            if (
                is_single_punct
                and raw_adjacent
                and val in {".", ","}
                and prev_val
                and prev_val[-1].isdigit()
            ) or (val == "%" and not attach_to_prev):
                next_attach = True  # only attach following digits for true decimals/prefix-%
        elif is_single_punct and val in OPENING_PUNCT_CHARS:
            next_opening = True

        # Attach numeric decimals/ratios/prefix-% cleanly when raw tokens had zero gap
        if (
            prev_attach_next
            and raw_adjacent
            and (
                prev_val == "/"
                or (prev_val in {".", ",", "%"} and val[0].isdigit())
            )
        ):
            attach_to_prev = True

        if not pieces or prev_was_opening or attach_to_prev:
            pieces.append(val)
        else:
            pieces.append(" " + val)

        prev_was_opening = next_opening
        prev_attach_next = next_attach
        prev_val = val
        prev_tok_type = tok_type if not (prev_tok_type == "Emoticon" and attach_to_prev) else "Emoticon"

    return "".join(pieces)


def apply_normalization_to_record(
    orig_rec: Dict[str, Any],
    batch_norm: Dict[str, str],
    final_norm: Dict[str, str],
    model: str,
    usage_meta: Dict[str, Any],
) -> Tuple[Dict[str, Any], bool, int]:
    """Populates per-token normalized fields, profanity/expansion tags, QC flags, and reconstructions."""
    out_rec = json.loads(json.dumps(orig_rec))
    if usage_meta:
        out_rec["llm_usage"] = usage_meta

    sent_complete = True
    fallback_tokens = 0
    tokens = out_rec.get("tokens", [])
    profanity_entries: List[Dict[str, Any]] = []

    for t in tokens:
        idx_str = str(t["index"])
        tok_type = t.get("type", "")
        chosen = final_norm.get(idx_str)

        # Hard invariant lock: Emoji, Emoticon, Mention, HashTag, and URL never drift
        if tok_type in INVARIANT_TOKEN_TYPES and isinstance(chosen, str) and chosen != "":
            t["norm"] = t["surface"]
            t["norm_method"] = (
                f"llm_batch_{model}"
                if (idx_str in batch_norm and batch_norm[idx_str] != "")
                else f"llm_retry_{model}"
            )
        elif isinstance(chosen, str) and not (idx_str == "0" and chosen == ""):
            t["norm"] = chosen
            if idx_str in batch_norm and batch_norm[idx_str] == chosen:
                t["norm_method"] = f"llm_batch_{model}"
            else:
                t["norm_method"] = f"llm_retry_{model}"
        else:
            t["norm"] = t["surface"]
            t["norm_method"] = "fallback_raw"
            sent_complete = False
            fallback_tokens += 1

        p_entry = annotate_token_profanity(t)
        if p_entry is not None:
            profanity_entries.append(p_entry)

    joined = " ".join(t["norm"] for t in tokens if t["norm"] != "")
    joined_expanded = " ".join(
        t.get("norm_expanded", t["norm"]) for t in tokens if t["norm"] != ""
    )
    norm_text = reconstruct_normalized_text(tokens, use_expanded=False)
    norm_text_expanded = reconstruct_normalized_text(tokens, use_expanded=True)

    out_rec["normalized_tokens_joined"] = joined
    out_rec["normalized_tokens_joined_tr_lower"] = tr_lower(joined)
    out_rec["normalized_text"] = norm_text
    out_rec["normalized_text_expanded"] = norm_text_expanded
    if joined_expanded != joined:
        out_rec["normalized_tokens_joined_expanded"] = joined_expanded

    has_prof = bool(profanity_entries) or bool(orig_rec.get("profanity_tags"))
    out_rec["has_profanity"] = has_prof
    if profanity_entries:
        out_rec["profanity_tokens"] = profanity_entries

    raw_text = str(out_rec.get("text", ""))
    out_rec["qc_flags"] = compute_qc_flags(raw_text, norm_text, orig_rec, tokens)
    out_rec["is_complete"] = sent_complete
    return out_rec, sent_complete, fallback_tokens


def load_registry(path: Path) -> Dict[str, Any]:
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"batches": [], "completed_sentence_ids": []}


def save_registry(path: Path, data: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, path)


def write_exports(
    records: List[Dict[str, Any]],
    completed_out_records: Dict[str, Dict[str, Any]],
    norm_export_path: Path,
    pairs_export_path: Path,
    expand_profanity_in_norm: bool = False,
) -> None:
    """Writes W-NUT MultiLexNorm .norm TSV and sentence-pair TSV atomically."""
    norm_export_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_norm = norm_export_path.with_suffix(".norm.tmp")
    tmp_pairs = pairs_export_path.with_suffix(".tsv.tmp")

    with open(tmp_norm, "w", encoding="utf-8") as nf, open(tmp_pairs, "w", encoding="utf-8") as pf:
        pf.write(
            "id\traw_text\tnormalized_text\tnormalized_text_expanded\thas_profanity\tnormalized_tokens_joined_tr_lower\n"
        )
        for r in records:
            sid = r["sentence_id"]
            ann = completed_out_records.get(sid)
            if not ann:
                continue
            for t in ann.get("tokens", []):
                surf = str(t["surface"]).replace("\t", " ").replace("\n", " ")
                chosen_norm = (
                    t.get("norm_expanded", t.get("norm", surf))
                    if expand_profanity_in_norm
                    else t.get("norm", surf)
                )
                nrm = str(chosen_norm).replace("\t", " ").replace("\n", " ")
                nf.write(f"{surf}\t{nrm}\n")
            nf.write("\n")

            raw_clean = str(ann.get("text", "")).replace("\t", " ").replace("\n", " ")
            norm_clean = str(ann.get("normalized_text", "")).replace("\t", " ").replace("\n", " ")
            exp_clean = str(ann.get("normalized_text_expanded", norm_clean)).replace("\t", " ").replace("\n", " ")
            has_prof = "1" if ann.get("has_profanity") else "0"
            tr_clean = str(ann.get("normalized_tokens_joined_tr_lower", "")).replace("\t", " ").replace("\n", " ")
            pf.write(f"{sid}\t{raw_clean}\t{norm_clean}\t{exp_clean}\t{has_prof}\t{tr_clean}\n")

    os.replace(tmp_norm, norm_export_path)
    os.replace(tmp_pairs, pairs_export_path)


# ==============================================================================
# 6. ORCHESTRATOR
# ==============================================================================

def run_normalization_pipeline(
    repo_root: Path,
    input_path: Path,
    output_dir: Path,
    run_name: str,
    api_key: Optional[str],
    model: str,
    thinking_budget: int,
    thinking_level: Optional[str],
    batch_size: int,
    group_size: int,
    max_active_batches: int,
    poll_interval: int,
    max_output_tokens: int = 32768,
    temperature: Optional[float] = None,
    expand_profanity_in_norm: bool = False,
    limit: int = 0,
    dry_run: bool = False,
) -> None:
    tokenized_dir = output_dir / "tokenized"
    registries_dir = output_dir / "registries"
    normalized_dir = output_dir / "normalized"
    exports_dir = output_dir / "exports"
    reports_dir = output_dir / "reports"
    for d in (tokenized_dir, registries_dir, normalized_dir, exports_dir, reports_dir):
        d.mkdir(parents=True, exist_ok=True)

    tokenized_path = tokenized_dir / f"{input_path.stem}_tokenized.jsonl"
    registry_path = registries_dir / f"{run_name}_registry.json"
    normalized_path = normalized_dir / f"{run_name}_normalized.jsonl"
    norm_export_path = exports_dir / f"{run_name}.norm"
    pairs_export_path = exports_dir / f"{run_name}_pairs.tsv"
    summary_path = reports_dir / f"{run_name}_summary.json"

    print("\n" + "=" * 80)
    print(
        f"  NORMALIZATION RUN: {run_name} | model={model} | thinking={thinking_level or thinking_budget} | "
        f"batch_size={batch_size} | group_size={group_size}"
    )
    print("=" * 80)

    ensure_tokenized_jsonl(repo_root, input_path, tokenized_path)

    records: List[Dict[str, Any]] = []
    with open(tokenized_path, "r", encoding="utf-8") as f:
        for idx, line in enumerate(f):
            if limit > 0 and idx >= limit:
                break
            line = line.strip()
            if line:
                records.append(json.loads(line))

    total_sentences = len(records)
    total_tokens = sum(len(r.get("tokens", [])) for r in records)
    print(f"  [LOADED] {total_sentences:,} sentences | {total_tokens:,} Zemberek tokens")

    if dry_run:
        sample_grp = records[:min(len(records), max(1, group_size))]
        sample_prompt = (
            format_normalization_prompt(sample_grp[0])
            if (group_size <= 1 and sample_grp)
            else (format_group_normalization_prompt(sample_grp) if sample_grp else "")
        )
        sys_inst = SINGLE_SYSTEM_INSTRUCTION if group_size <= 1 else GROUP_SYSTEM_INSTRUCTION
        num_requests = math.ceil(total_sentences / max(1, group_size))
        avg_user_chars = sum(len(format_normalization_prompt(r)) for r in records) / max(1, len(records))
        sys_chars = len(sys_inst)
        avg_user_tokens = avg_user_chars / 2.6
        sys_tokens = sys_chars / 3.8
        est_input = int(avg_user_tokens * total_sentences + sys_tokens * num_requests)
        est_output = int(4.5 * total_tokens)

        label = "Sentence #1" if group_size <= 1 else f"Group #1 ({len(sample_grp)} sentences)"
        print(f"\n  [DRY-RUN] Sample Prompt ({label}):")
        print("  " + "-" * 72)
        for pline in sample_prompt.splitlines()[:45]:
            print(f"  {pline}")
        if len(sample_prompt.splitlines()) > 45:
            print(f"  ... ({len(sample_prompt.splitlines()) - 45} more lines)")
        print("  " + "-" * 72)
        print(f"  [DRY-RUN ESTIMATES for {total_sentences:,} sentences across {num_requests:,} requests]:")
        print(f"    Avg User Prompt:    ~{avg_user_tokens:.0f} tokens/sent ({avg_user_chars:.0f} chars)")
        print(f"    System Instruction: ~{sys_tokens:.0f} tokens/request ({sys_chars:,} chars)")
        print(f"    Avg Request Prompt: ~{(est_input / max(1, num_requests)):.0f} tokens/request")
        print(f"    Est. Input Tokens:  ~{est_input:,}")
        print(f"    Est. JSON Output:   ~{est_output:,} (excluding thinking tokens)")
        return

    if not api_key:
        raise ValueError("GEMINI_API_KEY or GOOGLE_API_KEY environment variable is required.")

    registry = load_registry(registry_path)

    completed_out_records: Dict[str, Dict[str, Any]] = {}
    if normalized_path.exists():
        with open(normalized_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    obj = json.loads(line)
                    completed_out_records[obj["sentence_id"]] = obj

    completed_ids: Set[str] = set()
    for sid, obj in completed_out_records.items():
        toks = obj.get("tokens", [])
        if obj.get("is_complete") and all(str(t.get("norm_method", "")).startswith("llm_") for t in toks):
            completed_ids.add(sid)

    registry["completed_sentence_ids"] = sorted(completed_ids)

    enqueued_ids: Set[str] = set(completed_ids)
    for b in registry.get("batches", []):
        if b.get("state") in ("BATCH_STATE_PENDING", "BATCH_STATE_RUNNING"):
            for sid in b.get("sentence_ids", []):
                enqueued_ids.add(sid)

    unsubmitted_chunks: List[Tuple[int, List[Dict[str, Any]], int]] = []
    curr_chunk: List[Dict[str, Any]] = []
    chunk_start = 0
    for idx, r in enumerate(records):
        sid = r["sentence_id"]
        if sid in enqueued_ids:
            continue
        if not curr_chunk:
            chunk_start = idx
        curr_chunk.append(r)
        if len(curr_chunk) >= batch_size:
            unsubmitted_chunks.append((chunk_start, curr_chunk, group_size))
            curr_chunk = []
    if curr_chunk:
        unsubmitted_chunks.append((chunk_start, curr_chunk, group_size))

    record_lookup = {r["sentence_id"]: r for r in records}
    partial_norm_cache: Dict[str, Dict[str, str]] = {}
    batch_retry_counts: Dict[str, int] = {}
    incomplete_batch_retry_count = 0
    chunk_ptr = 0
    start_time = time.time()
    single_retry_count = 0

    while True:
        active_batches = [
            b for b in registry.get("batches", [])
            if b.get("state") not in (
                "BATCH_STATE_SUCCEEDED",
                "BATCH_STATE_FAILED",
                "BATCH_STATE_CANCELLED",
                "BATCH_STATE_EXPIRED",
            )
        ]

        while len(active_batches) < max_active_batches and chunk_ptr < len(unsubmitted_chunks):
            s_idx, chunk_recs, eff_group_size = unsubmitted_chunks[chunk_ptr]
            chunk_ptr += 1
            step = max(1, eff_group_size)
            chunk_groups = [chunk_recs[i:i + step] for i in range(0, len(chunk_recs), step)]
            display_name = f"{run_name}_{s_idx}_{s_idx + len(chunk_recs) - 1}_{int(time.time())}"
            print(
                f"  [BATCH SUBMIT] {display_name} ({len(chunk_recs)} sentences in "
                f"{len(chunk_groups)} requests, group_size={eff_group_size}, model={model})..."
            )
            try:
                payload = build_batch_payload(
                    chunk_groups,
                    display_name,
                    thinking_budget,
                    thinking_level,
                    group_size=eff_group_size,
                    max_output_tokens=max_output_tokens,
                    temperature=temperature,
                )
                resp = submit_batch_job(api_key, payload, model)
                batch_id = resp.get("name")
                print(f"    -> Enqueued: {batch_id}")

                b_entry = {
                    "name": batch_id,
                    "displayName": display_name,
                    "start_idx": s_idx,
                    "count": len(chunk_recs),
                    "request_count": len(chunk_groups),
                    "group_size": eff_group_size,
                    "sentence_ids": [r["sentence_id"] for r in chunk_recs],
                    "groups": [[r["sentence_id"] for r in grp] for grp in chunk_groups],
                    "state": "BATCH_STATE_PENDING",
                    "create_time": time.time(),
                }
                registry["batches"].append(b_entry)
                active_batches.append(b_entry)
                save_registry(registry_path, registry)
                time.sleep(1.0)
            except Exception as e:
                print(f"    [WARN] Failed submitting chunk {display_name}: {e}")
                chunk_ptr -= 1
                if chunk_ptr < 0:
                    chunk_ptr = 0
                if any(code in str(e) for code in ("HTTP 400", "HTTP 401", "HTTP 402", "HTTP 403", "HTTP 404")):
                    raise
                break

        for b_entry in active_batches:
            b_name = b_entry["name"]
            try:
                status_data = poll_batch_job(api_key, b_name)
            except Exception as e:
                print(f"  [WARN] Error polling batch {b_name}: {e}")
                continue

            state = status_data.get("metadata", {}).get("state", "UNKNOWN")
            b_entry["state"] = state
            b_stats = status_data.get("metadata", {}).get("batchStats", {})
            print(f"  [{time.strftime('%H:%M:%S')}] {b_entry.get('displayName')} ({b_name}): state={state} stats={b_stats}")

            if state == "BATCH_STATE_SUCCEEDED":
                inlined = (
                    status_data.get("response", {})
                    .get("inlinedResponses", {})
                    .get("inlinedResponses", [])
                )
                groups = b_entry.get("groups") or [[sid] for sid in b_entry.get("sentence_ids", [])]
                b_group_size = int(b_entry.get("group_size", group_size))
                incomplete_for_batch_retry: List[Dict[str, Any]] = []

                for g_idx, grp_sids in enumerate(groups):
                    grp_recs = [record_lookup[sid] for sid in grp_sids if sid in record_lookup]
                    if not grp_recs:
                        continue

                    item = inlined[g_idx] if g_idx < len(inlined) else {}
                    resp_obj = item.get("response", {}) if isinstance(item, dict) else {}
                    gen_text = extract_text_from_candidate(resp_obj)
                    grp_usage = resp_obj.get("usageMetadata", {})

                    if b_group_size <= 1 and len(grp_recs) == 1:
                        single_parsed = parse_normalization_json(gen_text) or {}
                        parsed_by_offset = {0: single_parsed} if single_parsed else {}
                    else:
                        parsed_by_offset = parse_group_normalization_json(gen_text, grp_recs)

                    leader_sid = grp_recs[0]["sentence_id"]
                    for k_idx, orig_rec in enumerate(grp_recs):
                        s_id = orig_rec["sentence_id"]
                        prior_partial = partial_norm_cache.get(s_id, {})
                        batch_norm = dict(prior_partial)
                        batch_norm.update(parsed_by_offset.get(k_idx, {}))
                        final_norm = dict(batch_norm)
                        retry_usage: Dict[str, Any] = {}

                        if k_idx == 0:
                            base_rec_usage = dict(grp_usage)
                            if len(grp_recs) > 1 and base_rec_usage:
                                base_rec_usage["group_sentences"] = len(grp_recs)
                        elif len(grp_recs) > 1:
                            base_rec_usage = {
                                "promptTokenCount": 0,
                                "candidatesTokenCount": 0,
                                "thoughtsTokenCount": 0,
                                "totalTokenCount": 0,
                                "shared_with": leader_sid,
                            }
                        else:
                            base_rec_usage = {}

                        if not is_normalization_complete(orig_rec, batch_norm):
                            if batch_retry_counts.get(s_id, 0) < 1:
                                # Re-queue via Gemini Batch API (group_size=1) to preserve 50% Batch discount!
                                batch_retry_counts[s_id] = batch_retry_counts.get(s_id, 0) + 1
                                partial_norm_cache[s_id] = batch_norm
                                incomplete_for_batch_retry.append(orig_rec)
                                incomplete_batch_retry_count += 1
                                # Still record group usage if k_idx == 0 on a dummy/existing entry if needed
                                if k_idx == 0 and base_rec_usage:
                                    orig_rec["_deferred_usage"] = merge_usage_metadata(
                                        orig_rec.get("_deferred_usage", {}), base_rec_usage
                                    )
                                continue
                            else:
                                single_retry_count += 1
                                final_norm, retry_usage = call_single_sentence_retry(
                                    api_key, orig_rec, batch_norm, model, thinking_budget, thinking_level
                                )

                        deferred_u = orig_rec.pop("_deferred_usage", {})
                        rec_usage = merge_usage_metadata(merge_usage_metadata(deferred_u, base_rec_usage), retry_usage)

                        out_rec, sent_complete, fallback_cnt = apply_normalization_to_record(
                            orig_rec, batch_norm, final_norm, model, rec_usage
                        )
                        out_rec.pop("_deferred_usage", None)
                        if fallback_cnt > 0:
                            print(f"    [WARN] Fallback used for {fallback_cnt} token(s) in {s_id}")

                        completed_out_records[s_id] = out_rec
                        if sent_complete and s_id not in completed_ids:
                            completed_ids.add(s_id)
                            registry["completed_sentence_ids"].append(s_id)

                if incomplete_for_batch_retry:
                    print(
                        f"    [BATCH-RETRY] Enqueueing {len(incomplete_for_batch_retry)} incomplete sentence(s) "
                        f"as mini Batch API job (group_size=1, 50% discount)"
                    )
                    unsubmitted_chunks.append((0, incomplete_for_batch_retry, 1))

                tmp_out = normalized_path.with_suffix(".jsonl.tmp")
                with open(tmp_out, "w", encoding="utf-8") as out_f:
                    for r in records:
                        sid = r["sentence_id"]
                        if sid in completed_out_records:
                            out_f.write(json.dumps(completed_out_records[sid], ensure_ascii=False) + "\n")
                os.replace(tmp_out, normalized_path)
                write_exports(
                    records,
                    completed_out_records,
                    norm_export_path,
                    pairs_export_path,
                    expand_profanity_in_norm=expand_profanity_in_norm,
                )
                save_registry(registry_path, registry)
                print(f"    -> Saved {len(completed_out_records):,}/{total_sentences:,} normalized sentences -> {normalized_path}")

            elif state in ("BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED", "BATCH_STATE_EXPIRED"):
                print(f"    [WARN] Batch {b_name} terminated with state={state}")
                retry_recs = []
                for sid in b_entry.get("sentence_ids", []):
                    if sid not in completed_ids and sid in record_lookup:
                        cnt = batch_retry_counts.get(sid, 0)
                        if cnt < 2:
                            batch_retry_counts[sid] = cnt + 1
                            retry_recs.append(record_lookup[sid])
                if retry_recs:
                    print(f"    [RE-QUEUE] Re-enqueueing {len(retry_recs)} sentences from failed batch {b_name}")
                    unsubmitted_chunks.append(
                        (b_entry.get("start_idx", 0), retry_recs, int(b_entry.get("group_size", group_size)))
                    )
                save_registry(registry_path, registry)

        still_active = [
            b for b in registry.get("batches", [])
            if b.get("state") not in (
                "BATCH_STATE_SUCCEEDED",
                "BATCH_STATE_FAILED",
                "BATCH_STATE_CANCELLED",
                "BATCH_STATE_EXPIRED",
            )
        ]
        if not still_active and chunk_ptr >= len(unsubmitted_chunks):
            break
        time.sleep(poll_interval)

    changed_tokens = 0
    split_tokens = 0
    merge_head_tokens = 0
    merge_cont_tokens = 0
    llm_resolved_tokens = 0
    fallback_tokens = 0
    profane_sentences = 0
    profane_tokens = 0
    expanded_acronym_tokens = 0
    qc_flagged_sentences = 0

    for r in records:
        ann = completed_out_records.get(r["sentence_id"])
        if not ann:
            continue
        if ann.get("has_profanity"):
            profane_sentences += 1
        if ann.get("qc_flags"):
            qc_flagged_sentences += 1
        toks = ann.get("tokens", [])
        for i, t in enumerate(toks):
            if str(t.get("norm_method", "")).startswith("llm_"):
                llm_resolved_tokens += 1
            else:
                fallback_tokens += 1
            if t.get("is_profane"):
                profane_tokens += 1
            if "norm_expanded" in t:
                expanded_acronym_tokens += 1
            surf = t["surface"]
            nrm = t.get("norm", surf)
            if nrm != surf:
                changed_tokens += 1
            if nrm == "":
                merge_cont_tokens += 1
            elif " " in nrm:
                split_tokens += 1
            elif i + 1 < len(toks) and toks[i + 1].get("norm", "x") == "":
                merge_head_tokens += 1

    prompt_tokens = sum(r.get("llm_usage", {}).get("promptTokenCount", 0) for r in completed_out_records.values())
    cand_tokens = sum(r.get("llm_usage", {}).get("candidatesTokenCount", 0) for r in completed_out_records.values())
    thought_tokens = sum(r.get("llm_usage", {}).get("thoughtsTokenCount", 0) for r in completed_out_records.values())

    summary = {
        "run_name": run_name,
        "input_path": str(input_path),
        "model": model,
        "batch_size": batch_size,
        "group_size": group_size,
        "max_output_tokens": max_output_tokens,
        "thinking_budget": thinking_budget,
        "thinking_level": thinking_level,
        "total_sentences": len(completed_out_records),
        "total_tokens": total_tokens,
        "llm_normalized_tokens": llm_resolved_tokens,
        "fallback_tokens": fallback_tokens,
        "changed_tokens": changed_tokens,
        "changed_pct": round(changed_tokens / max(1, total_tokens) * 100.0, 3),
        "split_tokens_1_to_n": split_tokens,
        "merge_heads_n_to_1": merge_head_tokens,
        "merge_continuations": merge_cont_tokens,
        "profane_sentences": profane_sentences,
        "profane_tokens": profane_tokens,
        "expanded_acronym_tokens": expanded_acronym_tokens,
        "qc_flagged_sentences": qc_flagged_sentences,
        "batch_retry_sentences": incomplete_batch_retry_count,
        "single_sentence_retries": single_retry_count,
        "usage": {
            "prompt_tokens": prompt_tokens,
            "candidates_tokens": cand_tokens,
            "thoughts_tokens": thought_tokens,
            "total_tokens": prompt_tokens + cand_tokens + thought_tokens,
        },
        "elapsed_seconds": round(time.time() - start_time, 1),
        "paths": {
            "tokenized_jsonl": str(tokenized_path),
            "normalized_jsonl": str(normalized_path),
            "norm_tsv": str(norm_export_path),
            "pairs_tsv": str(pairs_export_path),
        },
    }
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"  [COMPLETE] {run_name}: {llm_resolved_tokens:,}/{total_tokens:,} tokens normalized "
          f"({changed_tokens:,} modified, {split_tokens:,} splits, {merge_cont_tokens:,} merges, "
          f"profane_sents={profane_sentences}, qc_flagged={qc_flagged_sentences})")
    print(f"  [OUTPUT]   Normalized JSONL: {normalized_path}")
    print(f"  [EXPORT]   MultiLexNorm .norm: {norm_export_path}")
    print(f"  [EXPORT]   Sentence pairs TSV: {pairs_export_path}")
    print(f"  [REPORT]   Summary JSON: {summary_path}")


def main() -> None:
    load_env()
    load_env(".env.local")
    load_env(str(Path.home() / ".env"))

    repo_root = Path(__file__).resolve().parent.parent.parent.parent
    default_input = Path(os.path.expanduser("~/data/turkish/normalization/noisy_tr_v2/full_110k/general/slices/train_slice_01.jsonl"))
    default_output = Path(os.path.expanduser("~/data/turkish/normalization/noisy_tr_v2/full_110k/general/llm_gold"))

    parser = argparse.ArgumentParser(
        description="Turkish Lexical Normalization Engine via Gemini Batch API (gemini-2.5-pro / gemini-2.5-flash)"
    )
    parser.add_argument(
        "--input-file", default=str(default_input),
        help=f"Input .jsonl (with 'text') or .txt file (default: {default_input})"
    )
    parser.add_argument(
        "--output-dir", default=str(default_output),
        help=f"Output root directory (default: {default_output})"
    )
    parser.add_argument(
        "--run-name", default=None,
        help="Run identifier prefix for outputs/registry (default: derived from input slice stem)"
    )
    parser.add_argument(
        "--model", default="gemini-2.5-pro",
        help="Gemini model identifier for Batch API (default: gemini-2.5-pro)"
    )
    parser.add_argument(
        "--reasoning", choices=["low", "medium", "high"], default="medium",
        help="Reasoning effort preset: low, medium, or high (default: medium)"
    )
    parser.add_argument(
        "--thinking-budget", type=int, default=None,
        help="Explicit thinking token budget (overrides --reasoning if set)"
    )
    parser.add_argument(
        "--thinking-level", choices=["LOW", "MEDIUM", "HIGH"], default=None,
        help="Explicit Gemini API thinkingLevel string"
    )
    parser.add_argument(
        "--max-output-tokens", type=int, default=32768,
        help="Explicit maxOutputTokens per request (default: 32768, Gemini Batch API maximum)"
    )
    parser.add_argument(
        "--temperature", type=float, default=None,
        help="Optional explicit temperature (default: None -> model optimal default)"
    )
    parser.add_argument(
        "--batch-size", type=int, default=5000,
        help="Number of sentences per Batch API job (default: 5000 -> 1 slice = 1 job)"
    )
    parser.add_argument(
        "--group-size", type=int, default=25,
        help="Number of sentences grouped into each API request inside a Batch job (default: 25 -> 200 calls/5K)"
    )
    parser.add_argument(
        "--max-active-batches", type=int, default=4,
        help="Maximum concurrent Batch API jobs in flight (default: 4)"
    )
    parser.add_argument(
        "--poll-interval", type=int, default=None,
        help="Seconds between Batch API status checks (default: 5 for <=10, 10 for <=100, else 15)"
    )
    parser.add_argument(
        "--expand-profanity-in-norm", action="store_true",
        help="Write expanded vulgar phrases (e.g. 'amına koyayım') instead of acronyms ('amk') in .norm export"
    )
    parser.add_argument(
        "--limit", type=int, default=0,
        help="Process only the first N sentences (0 = all)"
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Tokenize, print sample prompt, and estimate token usage without calling the API"
    )

    args = parser.parse_args()
    if args.group_size < 1:
        parser.error("--group-size must be >= 1")

    thinking_budget = (
        args.thinking_budget
        if args.thinking_budget is not None
        else REASONING_BUDGET_PRESETS[args.reasoning]
    )
    thinking_level = args.thinking_level
    if thinking_level is None and args.thinking_budget is None and "2.5" not in args.model.lower():
        thinking_level = args.reasoning.upper()

    inp_stem = Path(args.input_file).stem
    run_name = args.run_name
    if not run_name:
        if args.limit > 0:
            run_name = f"{inp_stem}_limit{args.limit}_g{args.group_size}_{(thinking_level or args.reasoning).lower()}"
        else:
            run_name = inp_stem

    poll_interval = args.poll_interval
    if poll_interval is None:
        if 0 < args.limit <= 10:
            poll_interval = 5
        elif 0 < args.limit <= 100:
            poll_interval = 10
        else:
            poll_interval = 15

    api_key = (
        os.environ.get("GEMINI_API_KEY")
        or os.environ.get("GOOGLE_API_KEY")
        or ""
    ).strip().strip("'").strip('"')

    run_normalization_pipeline(
        repo_root=repo_root,
        input_path=Path(args.input_file).resolve(),
        output_dir=Path(args.output_dir).resolve(),
        run_name=run_name,
        api_key=api_key,
        model=args.model,
        thinking_budget=thinking_budget,
        thinking_level=thinking_level,
        batch_size=args.batch_size,
        group_size=args.group_size,
        max_active_batches=args.max_active_batches,
        poll_interval=poll_interval,
        max_output_tokens=args.max_output_tokens,
        temperature=args.temperature,
        expand_profanity_in_norm=args.expand_profanity_in_norm,
        limit=args.limit,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()
