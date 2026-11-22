#!/usr/bin/env python3
"""
Golden Set Slice Annotation Engine (GSLA-v2.1) using Google Gemini Batch API.

Workflow for each 1,000-sentence slice (test_slice_XXX.txt or train_slice_XXX.txt):
  1. Candidate Parse Generation:
     Uses ZCS-v2.0 <slice_name>_candidates.jsonl produced by SliceCandidateGenerator.java.
  2. Zemberek-Only Prompt Formatting:
     Formats each sentence as an isolated request containing:
     - Natural sentence text
     - ONLY Zemberek's native analysis (zemberek_key / SingleAnalysis.formatLong())
       for each ambiguous token (#index "surface"), omitting redundant Oflazer tags
       and redundant token header bars.
  3. Asynchronous Gemini Batch API (batchGenerateContent):
     Submits one request per sentence inside Batch API jobs (50% cost discount),
     with crash-safe registry checkpointing and auto-resume.
  4. Completeness Validation & Targeted Retry:
     Verifies that every ambiguous token index in every sentence received a valid
     candidate ID; automatically retries any incomplete sentence before fallback,
     writing the exact LLM selections directly to <slice_name>_annotated.jsonl
     with zero post-processing mutation.
"""

import os
import re
import sys
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
# 1. CALIBRATED ZEMBEREK CONVENTION SYSTEM INSTRUCTION (GSLA-v2.4)
# ==============================================================================

SYSTEM_INSTRUCTION = """Select the single most accurate Zemberek morphological candidate ID for each ambiguous word (#index) in the Turkish sentence.
Format: [lemma:POS,SecPOS] stem:POS+sfx:Tag|deriv:Tag→NewPOS.

Zemberek Tag Meanings & Tie-Break Conventions:
1. Clauses & Compounds:
   - Bare noun compounds ("otel yatırımları", "cazibe merkezi"): prefer modifier :Noun+A3sg and head +P3sg (not +Acc), unless the first word is a separate clause argument ("[Subject] [Object+Acc]", e.g. "Polis sanıkları yakaladı" -> +Acc) or a descriptive/nisba adjective modifying the noun ("yeni yatırımları", "askerî savcı", "merkezî yönetim" -> :Adj).
   - Single-word lexicalized compounds (cemevi, cezaevi, milletvekili, yayınevi, kamuoyu, işyeri, başbakan): prefer unpossessed +A3sg+Case ("cezaevine") or plural +leri:A3pl ("Değerli milletvekilleri") over +A3sg+P3sg+Case, +ler:A3pl+i:P3sg, or +P2sg+Case, unless possessed by an explicit external possessor ("ilçenin cemevinde" -> +P3sg, "partinin milletvekilleri" -> +ler:A3pl+i:P3sg, "senin işyerinde" -> +P2sg).
   - Subordinate clauses ("X+Gen Y+ı V|Part+P3sg"): when X+Gen is the clause subject agreeing with V+P3sg, select +Acc on internal object Y, unless X+Gen directly possesses Y ("[X+Gen Y+P3sg]", e.g. "hastanın durumu ağırlaştığı") or Y+P3sg is a possessed nominative subject of an intransitive/passive participle.
   - Participles (-dık/-ecek): prefer |PastPart→Adj / |FutPart→Adj before a modified head noun ("geçtiği güzergah") and |PastPart→Noun / |FutPart→Noun for headless/complement clauses ("alındığı belirtildi", "olduğunu söyledi"), unless a participle before a noun is actually a headless complement clause preceding a separate subject or adverbial noun ("alındığı dün açıklandı" -> |PastPart→Noun).
   - Verbal nouns (-mak/-ma/-ış) & -madan/-meden: prefer |Inf1/Inf2/Inf3→Noun over +Neg+Imp or +Neg+Opt when denoting an action/clause ("evlerine dönmeyi"), unless it is a genuine negative command/wish (+Neg+Imp/+Neg+Opt), a dative-inflected noun root ([adam/anlam:Noun]+Dat), or a lexicalized noun denoting an entity/institution ([sözleşme/anlaşma/görüşme/gelişim/girişim/iletişim:Noun]). For -madan/-meden, prefer |WithoutHavingDoneSo→Adv ("without doing", e.g. "durmadan konuştu") over |Inf2→Noun+Abl, unless it is an ablative complement governed by a postposition or verb ("bölmeden önce" before [önce/sonra:Postp,PCAbl], "tartışmadan kaçındı" -> |Inf2→Noun+A3sg+Abl).
2. Zero-Derivation (|Zero→Verb, |Zero→Noun), Copula & Finite Verbs:
   - Select |Zero→Verb (copula) ONLY when a nominal/adjective is the clause predicate or takes copular -ken/-ysa (|ken:While→Adv, +sa:Cond). For finite verbs (-miştir/-mişti/-mektedir), prefer direct tense (+Narr+A3sg+Cop, +Narr+Past, +Prog2+Cop) over |Part→Adj|Zero→Verb or |Inf1→Noun+Loc|Zero→Verb, unless the word is a lexicalized/headless participial predicate taking copula.
   - For "var/yok", "değil", and "ise": prefer [var/yok:Adj] (with |Zero→Verb when suffixed) for existential "there is/isn't", [değil:Verb] for predicate negation, and [ise:Adv] for topic contrast ("Fransa ise..."), unless "var" is [varmak:Verb] ("arrive"), possessed [var/yok:Noun] ("varını yoğunu"), contrastive conjunction [değil:Conj] ("X değil Y"), or true conditional [imek:Verb]+Cond ("doğru ise").
   - When the same lemma has both direct [X:Noun]+Suffixes and [X:Adj]|Zero→Noun+Suffixes (e.g. insanlar, kadınlarda, ortadan, altına, arasında, zorundasınız), prefer direct [X:Noun] (and Num|Ness over Num|Zero→Noun|Ness), unless [X:Adj] and [X:Noun] are distinct-meaning homographs and context requires the nominalized adjective ("the [Adj] one", e.g. [yaş:Adj]|Zero→Noun "wet one" vs [yaş:Noun] "age") or the word is an uninflected adjective modifier ([alt:Adj] kısım).
3. Determiners, Pronouns, Numerals, Adverbs & Postpositions:
   - Demonstratives/quantifiers (bu, o, şu, her, birkaç, birçok, birtakım, tüm, bazı): prefer :Det when modifying a following noun phrase ("bu insanlar", "o gün") and :Pron when standing alone, unless the word is a standalone subject/object pronoun that merely precedes a predicate or another NP ("Bu utanç verici", "o beni bırakmadan", "bu çok iyi bir adım" -> :Pron; for o/onu/ona/onlar/onları/onların, prefer [o:Pron,Pers] for human/animate referents and [o:Pron,Demons] for inanimate/abstract referents as in "ondan sonra", "ne varsa onları geliştirmek"). For degree words (çok, daha, en, pek, biraz, fazla), select :Adv when modifying an adjective/adverb/verb ("çok yardımcı olacak") and :Adj before a noun. For post-nominal enumerative "falan/filan" ("analiz falan"), select :Noun+A3sg.
   - "bir", numerals & "yüzde": indefinite article "a/an" -> [bir:Det]; number "one" -> [bir:Num,Card]; "same/equal" -> [bir:Adj]; in "bir de" / "only" -> [bir:Adv]. Dotted numerals ("1.", "2.") meaning "first, second" -> :Num,Ord (not :Num,Dist). For "yüzde" in percentages ("yüzde 10"), prefer [yüz:Num,Card]|Zero→Noun+A3sg+de:Loc over [yüzde:Noun], unless inflected as a standalone noun ("yüzdesi").
   - Postpositions & relational nouns: match :Postp (PCNom/PCDat/PCAbl/PCGen/PCIns) to the preceding complement's case. For "ile", select [ile:Conj] when coordinating two parallel NPs ("X and Y") and [ile:Postp,PCNom] for instrument/manner/accompaniment ("with/by X"). For relational nouns with a 3rd-person complement ("X tarafından/nedeniyle/hakkında/içinde/üzerinde/yanında/ardından"), prefer Noun+A3sg+P3sg+Case over standalone :Adv, unless possessed by a 2nd-person singular possessor ("senin hakkında/yanında" -> +P2sg+Case) or used standalone as a sentence/temporal adverb without a complement ("Aslında ...", "Sonunda başardık" -> :Adv).
4. Voice, Lexicon & Orthographic Twins:
   - Prefer direct lexicalized lemmas ([belirtmek/bildirmek/anlatmak/üretmek/kaldırmak/düzenlemek/hoşlanmak/düşündürmek/değerlendirmek/sürdürmek/öldürmek/geliştirmek/yaratmak:Verb], [gerekli/değerli/ilgili/yeterli/sürekli:Adj], [savcı/öğrenci/yolcu/işlemci:Noun]) over multi-step derivations (|Caus, |Acquire, |Become, |With, |Agt) when both have the exact same meaning in context (for "yardımcı", select [yardımcı:Adj] when adjectival/predicative as in "çok yardımcı olacak" and [yardımcı:Noun] when an entity/title head as in "başkan yardımcısı"), unless the candidates differ in voice or meaning: specifically, distinguish middle/intransitive [bulunmak/alınmak/katılmak/ayrılmak/bozulmak/silinmek/görünmek/söylenmek/uzanmak/üzülmek/bayılmak:Verb] ("be located/present", "take offense", "attend/join", "depart", "spoil/get upset", "fade", "appear", "grumble", "lie down", "feel sad", "faint/love") from true passive [bulmak/almak/katmak/ayırmak/bozmak/silmek/görmek/söylemek:Verb]|Pass ("be found", "be taken/admitted", "be added", "be allocated", "be destroyed/tampered with", "be erased", "be seen", "be said"; specifically, select [bulmak:Verb]|Pass for passive inability "adreslerinde bulunamayan" = could not be found, or with a dative beneficiary "daireye bulunan kiracı" = found for the flat, vs [bulunmak:Verb] for locative presence "X'te bulunan" or "tavsiyede bulunmak").
   - For -ları/-leri: select +A3sg+ları:P3pl (not +lar:A3pl+ı:P3pl) whenever the possessor is 3rd-person plural ("onların/uzmanların elleri, kararları"), unless the suffix is unpossessed plural accusative (+lar:A3pl+ı:Acc, "sanıkları teşhis etti") or singular-possessor/compound plural (+lar:A3pl+ı:P3sg, "onun kararları", "otel yatırımları", "suçun eserlerinin").
   - Capitalized words (,Prop / ,Abbrv): select ,Prop or ,Abbrv ONLY when capitalized and referring to a named entity, celestial body, official organization/convention/law title ("UNESCO Dünya Mirası", "Türk Ceza Kanunu"), or real acronym; never select ,Prop or ,Abbrv for lowercase words, common words capitalized at sentence start, title-cased text ("Her Ay..."), or false abbreviation collisions ([tıp:Noun] vs [Tıp:Noun,Abbrv]).
   - Circumflex homographs & Nisba twins: [hâl:Noun] (state/condition) vs [hal:Noun] (market); [kâr:Noun] (profit) vs [kar:Noun] (snow); [adet:Noun] (unit/piece) vs [âdet:Noun] (custom/habit). When both circumflexed and plain Nisba adjective lemmas exist ([şahsî:Adj] vs [şahsi:Adj]), prefer the circumflexed lemma ([şahsî:Adj]).

Return ONLY valid JSON: {"<token_index>": <candidate_id>}"""

CONVENTIONS_ONLY_SYSTEM_INSTRUCTION = """You are an expert Turkish linguist. For each ambiguous word (#index) in the sentence, analyze the clause syntax, case government, voice, and semantics from first principles, and select the single most accurate Zemberek candidate ID.

Zemberek Notation & Representation Conventions (apply ONLY to break ties between candidates that have the exact same syntactic role and meaning in context):
1. Tag Notation:
   - Format: [lemma:POS,SecPOS] stem:POS+inflection:Tag|derivation:Tag→NewPOS.
   - |Zero→Verb is the copula derivation (used on nominal/adjectival clause predicates and before copular -ken/-ysa).
   - |Zero→Noun marks zero-derivation when a non-noun takes nominal inflections.
   - :Postp,PCNom/PCDat/PCAbl/PCGen/PCIns indicates the case governed by a postposition on its preceding complement.
2. Canonical Lexicon Conventions (When Meaning & Syntactic Role Are Identical):
   - Direct Lemma vs. Derivation: When a direct dictionary entry (e.g. [belirtmek:Verb], [gerekli:Adj], [savcı:Noun], [insan:Noun]+lar, [var/yok:Adj], [değil:Verb], [ise:Adv]) and a derived/zero-derived analysis ([belirmek:Verb]|Caus, [insan:Adj]|Zero→Noun+lar, Num|Zero→Noun|Ness) have the exact same meaning and POS role in the sentence, Zemberek's canonical form is the direct lemma with fewer derivations. When candidates differ in actual voice, POS, or meaning (e.g. middle/intransitive [bulunmak/katılmak/ayrılmak:Verb] vs. passive [bulmak/katmak/ayırmak:Verb]|Pass), choose strictly by the sentence's true syntax and meaning.
   - Single-Word Compound Lemmas (cemevi, cezaevi, milletvekili, yayınevi, kamuoyu, işyeri, başbakan): Unpossessed forms use +A3sg+Case (singular) or +leri:A3pl (plural); +P3sg or +ler:A3pl+i:P3sg is used only when possessed by an external genitive/noun modifier.
   - 3rd-Person Plural Possessor (-ları/-leri): When a noun has a 3rd-person plural possessor ("onların ..."), Zemberek's convention is +A3sg+ları:P3pl rather than +lar:A3pl+ı:P3pl.
   - Relational Nouns & Idiomatic Conventions: Relational nouns governing a 3rd-person complement (tarafından, nedeniyle, hakkında, içinde, üzerinde, yanında, ardından) use Noun+A3sg+P3sg+Case (vs. standalone :Adv when used without a complement). Percentage "yüzde N" uses [yüz:Num,Card]|Zero→Noun+A3sg+de:Loc. Dotted ordinals ("1.", "2.") use :Num,Ord. For o/onlar pronouns, :Pron,Pers denotes human/animate referents and :Pron,Demons denotes inanimate/abstract referents. For twin Nisba adjectives ([şahsî:Adj] vs [şahsi:Adj]), use the circumflexed lemma. Use ,Prop / ,Abbrv only for capitalized named entities, official titles, or real acronyms (never for lowercase words or sentence-initial common words).

Return ONLY valid JSON: {"<token_index>": <candidate_id>}"""


# ==============================================================================
# 2. MODEL & REASONING PRESETS
# ==============================================================================

REASONING_BUDGET_PRESETS = {
    "low": 512,
    "medium": 1024,
    "high": 2048,
}


def load_env(filepath: str = ".env") -> None:
    if os.path.exists(filepath):
        with open(filepath, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ[k.strip()] = v.strip().strip("'").strip('"')


# ==============================================================================
# 3. ZEMBEREK-ONLY PROMPT BUILDER
# ==============================================================================

def get_unique_candidates(t: Dict[str, Any]) -> List[Dict[str, Any]]:
    """
    Deduplicates candidate analyses with identical zemberek_key strings
    (caused by Index:1/Index:2 lexicon homographs in Zemberek), preserving
    the first (lowest-ID) candidate for each unique analysis string.
    """
    seen_keys: Set[str] = set()
    unique_cands: List[Dict[str, Any]] = []
    for c in t.get("candidates", []):
        zk = c.get("zemberek_key", "").strip()
        if zk not in seen_keys:
            seen_keys.add(zk)
            unique_cands.append(c)
    return unique_cands


def format_zemberek_only_prompt(rec: Dict[str, Any]) -> str:
    """
    Constructs a clean, single-sentence prompt using ONLY Zemberek's native
    analysis string (`zemberek_key` / `SingleAnalysis.formatLong()`), with
    byte-identical duplicate candidates removed.
    """
    tokens = rec.get("tokens", [])

    lines = [
        f'Sentence: "{rec["text"]}"',
        "",
        "Ambiguous words:"
    ]

    for t in tokens:
        if t.get("is_ambiguous", False):
            lines.append(f'- #{t["index"]} "{t["surface"]}":')
            for c in get_unique_candidates(t):
                cid = c.get("id", 0)
                zk = c.get("zemberek_key", "").strip()
                lines.append(f"  [{cid}] {zk}")

    return "\n".join(lines)


# ==============================================================================
# 4. STAGE 1: JAVA CANDIDATE EXTRACTION (SliceCandidateGenerator)
# ==============================================================================

def ensure_candidates_jsonl(
    repo_root: Path,
    pool: str,
    slice_index: Optional[int],
    candidates_jsonl_path: Path
) -> None:
    """
    Ensures the ZCS-v2 _candidates.jsonl file generated by SliceCandidateGenerator
    is present on disk; reuses/symlinks from default annotated_slices/{pool}/candidates
    if available, or invokes run_slice_candidate_generator.sh if missing.
    """
    if candidates_jsonl_path.exists() and candidates_jsonl_path.stat().st_size > 0:
        print(f"  [STAGE 1] Found ZCS-v2 candidates JSONL: {candidates_jsonl_path}")
        return

    default_cand_path = Path("/home/dndara/data/turkish/annotated_slices") / pool / "candidates" / candidates_jsonl_path.name
    if default_cand_path.exists() and default_cand_path.stat().st_size > 0:
        candidates_jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        if candidates_jsonl_path.is_symlink() or candidates_jsonl_path.exists():
            candidates_jsonl_path.unlink()
        candidates_jsonl_path.symlink_to(default_cand_path)
        print(f"  [STAGE 1] Linked existing ZCS-v2 candidates JSONL: {candidates_jsonl_path} -> {default_cand_path}")
        return

    runner_sh = repo_root / "experiment/scripts/model/run_slice_candidate_generator.sh"
    cmd = [str(runner_sh), "--pool", pool, "--threads", "16"]
    if slice_index is not None:
        cmd.extend(["--slices", str(slice_index)])
    print(f"  [STAGE 1] Running SliceCandidateGenerator via {runner_sh.name}...")
    subprocess.run(cmd, cwd=str(repo_root), check=True)


# ==============================================================================
# 5. STAGE 2 & 3: GEMINI BATCH API + SINGLE-SENTENCE RETRY
# ==============================================================================

RETRYABLE_HTTP_CODES = {429, 500, 502, 503, 504}


def build_generation_config(thinking_budget: int, thinking_level: Optional[str] = None) -> Dict[str, Any]:
    gen_cfg: Dict[str, Any] = {
        "temperature": 0.0,
        "responseMimeType": "application/json",
    }
    if thinking_level:
        gen_cfg["thinkingConfig"] = {"thinkingLevel": thinking_level.upper()}
    elif thinking_budget >= 0:
        gen_cfg["thinkingConfig"] = {"thinkingBudget": thinking_budget}
    return gen_cfg


def build_batch_payload(
    records: List[Dict[str, Any]],
    batch_display_name: str,
    thinking_budget: int,
    thinking_level: Optional[str] = None
) -> Dict[str, Any]:
    gen_cfg = build_generation_config(thinking_budget, thinking_level)
    requests_list = []
    for r in records:
        prompt = format_zemberek_only_prompt(r)
        requests_list.append({
            "request": {
                "systemInstruction": {"parts": [{"text": SYSTEM_INSTRUCTION}]},
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": gen_cfg
            }
        })
    return {
        "batch": {
            "displayName": batch_display_name,
            "inputConfig": {
                "requests": {
                    "requests": requests_list
                }
            }
        }
    }


def submit_batch_job(
    api_key: str,
    payload: Dict[str, Any],
    model: str,
    max_retries: int = 5
) -> Dict[str, Any]:
    if requests is None:
        raise RuntimeError("The 'requests' package is required to call the Gemini API.")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:batchGenerateContent?key={api_key}"
    for attempt in range(max_retries):
        try:
            resp = requests.post(url, json=payload, timeout=90)
            if resp.status_code == 200:
                return resp.json()
            elif resp.status_code in RETRYABLE_HTTP_CODES:
                wait_s = (2 ** attempt) * 2.0 + 1.0
                print(f"    [HTTP {resp.status_code}] Transient error submitting batch; waiting {wait_s:.1f}s...")
                time.sleep(wait_s)
            else:
                raise RuntimeError(f"Batch submission failed (HTTP {resp.status_code}): {resp.text}")
        except requests.exceptions.RequestException as e:
            if attempt == max_retries - 1:
                raise
            wait_s = (2 ** attempt) * 2.0 + 1.0
            print(f"    [NET WARN] RequestException submitting batch ({e}); retrying in {wait_s:.1f}s...")
            time.sleep(wait_s)
    raise RuntimeError("Exceeded max retries submitting batch.")


def poll_batch_job(
    api_key: str,
    batch_name: str,
    max_retries: int = 5
) -> Dict[str, Any]:
    url = f"https://generativelanguage.googleapis.com/v1beta/{batch_name}?key={api_key}"
    for attempt in range(max_retries):
        try:
            resp = requests.get(url, timeout=45)
            if resp.status_code == 200:
                return resp.json()
            elif resp.status_code in RETRYABLE_HTTP_CODES:
                wait_s = (2 ** attempt) * 2.0 + 1.0
                time.sleep(wait_s)
            else:
                raise RuntimeError(f"Failed polling {batch_name} (HTTP {resp.status_code}): {resp.text}")
        except requests.exceptions.RequestException:
            if attempt == max_retries - 1:
                raise
            time.sleep((2 ** attempt) * 2.0 + 1.0)
    raise RuntimeError(f"Exceeded max retries polling {batch_name}")


def is_selection_complete(rec: Dict[str, Any], sel_map: Optional[Dict[str, int]]) -> bool:
    """Returns True iff every ambiguous token in rec has a valid candidate ID in sel_map."""
    amb_tokens = [t for t in rec.get("tokens", []) if t.get("is_ambiguous", False)]
    if not amb_tokens:
        return True
    if not sel_map:
        return False
    for t in amb_tokens:
        t_idx_str = str(t["index"])
        valid_ids = {c["id"] for c in t.get("candidates", [])}
        if sel_map.get(t_idx_str) not in valid_ids:
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
    existing_sel: Optional[Dict[str, int]],
    model: str,
    thinking_budget: int,
    thinking_level: Optional[str] = None,
    max_retries: int = 3
) -> Tuple[Dict[str, int], Dict[str, Any]]:
    """
    Targeted single-sentence retry if a batch response omitted or misformatted
    one or more ambiguous token keys. Keeps retrying up to max_retries until
    all ambiguous tokens in the sentence have valid candidate IDs.
    """
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    prompt = format_zemberek_only_prompt(rec)
    payload = {
        "systemInstruction": {"parts": [{"text": SYSTEM_INSTRUCTION}]},
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": build_generation_config(thinking_budget, thinking_level)
    }
    combined_sel: Dict[str, int] = dict(existing_sel) if existing_sel else {}
    accum_usage: Dict[str, Any] = {}

    for attempt in range(max_retries):
        try:
            resp = requests.post(url, json=payload, timeout=60)
            if resp.status_code == 200:
                data = resp.json()
                usage = data.get("usageMetadata", {})
                accum_usage = merge_usage_metadata(accum_usage, usage)
                gen_text = extract_text_from_candidate(data)
                parsed = parse_selection_json(gen_text)
                if parsed:
                    # Only overwrite/fill keys that are valid candidate IDs for that token
                    for t in rec.get("tokens", []):
                        if t.get("is_ambiguous", False):
                            t_idx_str = str(t["index"])
                            valid_ids = {c["id"] for c in t.get("candidates", [])}
                            cand_val = parsed.get(t_idx_str)
                            if cand_val in valid_ids:
                                combined_sel[t_idx_str] = cand_val
                if is_selection_complete(rec, combined_sel):
                    return combined_sel, accum_usage
            elif resp.status_code in RETRYABLE_HTTP_CODES:
                time.sleep((2 ** attempt) * 1.5 + 1.0)
                continue
            time.sleep(1.5 * (attempt + 1))
        except Exception:
            time.sleep(1.5 * (attempt + 1))
    return combined_sel, accum_usage


def parse_selection_json(text: Optional[str]) -> Optional[Dict[str, int]]:
    """
    Parses the LLM's JSON response mapping token index -> candidate ID.
    Parses each key-value pair independently so a single malformed key
    does not discard valid selections for other tokens in the sentence.
    """
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

    if not isinstance(data, dict):
        return None

    out: Dict[str, int] = {}
    for k, v in data.items():
        try:
            k_norm = str(k).lstrip("#").strip()
            if isinstance(v, int) and not isinstance(v, bool):
                out[k_norm] = v
            elif isinstance(v, str):
                v_clean = v.strip().lstrip("[").rstrip("]").strip()
                out[k_norm] = int(v_clean)
        except (ValueError, TypeError):
            continue
    return out if out else None


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


# ==============================================================================
# 7. SLICE ORCHESTRATOR
# ==============================================================================

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


def process_single_slice(
    repo_root: Path,
    pool: str,
    slice_index: Optional[int],
    slice_name: str,
    slice_txt_path: Path,
    output_root: Path,
    api_key: Optional[str],
    model: str,
    thinking_budget: int,
    thinking_level: Optional[str],
    batch_size: int,
    max_active_batches: int,
    poll_interval: int,
    limit: int = 0,
    extract_only: bool = False,
    dry_run: bool = False,
    export_zemberek_txt: bool = False
) -> None:
    candidates_dir = output_root / "candidates"
    registries_dir = output_root / "registries"
    annotated_dir = output_root / "annotated"
    reports_dir = output_root / "reports"

    for d in (candidates_dir, registries_dir, annotated_dir, reports_dir):
        d.mkdir(parents=True, exist_ok=True)

    candidates_path = candidates_dir / f"{slice_name}_candidates.jsonl"
    registry_path = registries_dir / f"{slice_name}_registry.json"
    annotated_path = annotated_dir / f"{slice_name}_annotated.jsonl"
    summary_path = reports_dir / f"{slice_name}_summary.json"

    print("\n" + "=" * 80)
    print(f"  SLICE: {slice_name}")
    print("=" * 80)

    # Stage 1: Ensure _candidates.jsonl exists
    ensure_candidates_jsonl(repo_root, pool, slice_index, candidates_path)

    records: List[Dict[str, Any]] = []
    with open(candidates_path, "r", encoding="utf-8") as f:
        for idx, line in enumerate(f):
            if limit > 0 and idx >= limit:
                break
            line = line.strip()
            if line:
                records.append(json.loads(line))

    total_sentences = len(records)
    total_tokens = sum(len(r.get("tokens", [])) for r in records)
    total_ambig_tokens = sum(
        sum(1 for t in r.get("tokens", []) if t.get("is_ambiguous", False))
        for r in records
    )
    print(f"  [LOADED] {total_sentences:,} sentences | {total_tokens:,} total tokens | {total_ambig_tokens:,} ambiguous tokens")

    if extract_only:
        print(f"  [EXTRACT-ONLY] Candidate extraction complete for {slice_name}. Skipping LLM calls.")
        return

    if dry_run:
        sample_prompt = format_zemberek_only_prompt(records[0]) if records else ""
        avg_user_chars = sum(len(format_zemberek_only_prompt(r)) for r in records) / max(1, len(records))
        sys_chars = len(SYSTEM_INSTRUCTION)
        # Calibrated from empirical gemini-3.8-flash and gemini-4-argon usageMetadata on GSLA-v2.3
        avg_user_tokens = avg_user_chars / 1.30
        sys_tokens = sys_chars / 2.25
        est_prompt_tokens = int((avg_user_tokens + sys_tokens) * total_sentences)
        est_output_tokens = int(45 * total_sentences)

        print("\n  [DRY-RUN] Sample Zemberek-Only Prompt (Sentence #1):")
        print("  " + "-" * 72)
        for pline in sample_prompt.splitlines():
            print(f"  {pline}")
        print("  " + "-" * 72)
        print(f"  [DRY-RUN TOKEN & COST ESTIMATES for {total_sentences:,} sentences ({slice_name})]:")
        print(f"    Avg User Prompt:        ~{avg_user_tokens:.0f} tokens/sent ({avg_user_chars:.0f} chars)")
        print(f"    System Instruction:     ~{sys_tokens:.0f} tokens/sent ({sys_chars:,} chars)")
        print(f"    Total Input Tokens:     ~{est_prompt_tokens:,} tokens / 1,000-sentence slice")
        print(f"    JSON Output Tokens:     ~{est_output_tokens:,} tokens / 1,000-sentence slice (~45/sent)")

        in_m = est_prompt_tokens / 1_000_000.0
        # Empirical model cost profiles (Batch API 50% discount rates):
        # 1. gemini-3.8-flash LOW (0 thought tokens + ~51 JSON out/sent)
        # 2. gemini-3.8-flash MEDIUM (~738 thought tokens + ~51 JSON = ~790 out/sent)
        # 3. gemini-4-argon LOW / MEDIUM (0 thought tokens + ~41 JSON out/sent)
        # 4. gemini-4-argon HIGH (~820 avg thought tokens + ~41 JSON = ~861 out/sent)
        models_table = [
            ("gemini-3.8-flash LOW (0 thought, ~51 out/s)", 51, 0.075, 0.30, 0.25, 1.50),
            ("gemini-3.8-flash MEDIUM (~738 thought, ~790 out/s)", 790, 0.075, 0.30, 0.25, 1.50),
            ("gemini-4-argon LOW/MEDIUM (0 thought, ~41 out/s)", 41, 0.625, 5.00, 1.00, 6.00),
            ("gemini-4-argon HIGH (~820 thought, ~861 out/s)", 861, 0.625, 5.00, 1.00, 6.00),
        ]
        print("\n    Estimated Batch API Cost (50% Discount Included):")
        for m_label, out_per_sent, in_low, out_low, in_high, out_high in models_table:
            out_m = (out_per_sent * total_sentences) / 1_000_000.0
            c_low = in_m * in_low + out_m * out_low
            c_high = in_m * in_high + out_m * out_high
            print(f"      - {m_label:48s}: ${c_low:5.2f} - ${c_high:5.2f} / 1K slice  "
                  f"(${c_low*10:6.2f}-${c_high*10:6.2f} / 10K)")
        return

    if not api_key:
        raise ValueError("GEMINI_API_KEY or GOOGLE_API_KEY environment variable is required for Batch API execution.")

    # Stage 2: Batch API Submission & Polling
    registry = load_registry(registry_path)

    completed_out_records: Dict[str, Dict[str, Any]] = {}
    if annotated_path.exists():
        with open(annotated_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    obj = json.loads(line)
                    completed_out_records[obj["sentence_id"]] = obj

    # Ground completed_ids strictly in records present on disk that have no fallback_top tokens
    completed_ids: Set[str] = set()
    for sid, obj in completed_out_records.items():
        ambs = [t for t in obj.get("tokens", []) if t.get("is_ambiguous", False)]
        if all(str(t.get("selection_method", "")).startswith("llm_") for t in ambs):
            completed_ids.add(sid)

    # Short-circuit any sentence with 0 ambiguous tokens
    for r in records:
        sid = r["sentence_id"]
        if sid not in completed_ids:
            ambs = [t for t in r.get("tokens", []) if t.get("is_ambiguous", False)]
            if not ambs:
                ann_copy = json.loads(json.dumps(r))
                ann_copy["is_complete"] = True
                completed_out_records[sid] = ann_copy
                completed_ids.add(sid)

    registry["completed_sentence_ids"] = sorted(completed_ids)

    enqueued_ids: Set[str] = set(completed_ids)
    for b in registry.get("batches", []):
        if b.get("state") in ("BATCH_STATE_PENDING", "BATCH_STATE_RUNNING"):
            for sid in b.get("sentence_ids", []):
                enqueued_ids.add(sid)

    unsubmitted_chunks: List[Tuple[int, List[Dict[str, Any]]]] = []
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
            unsubmitted_chunks.append((chunk_start, curr_chunk))
            curr_chunk = []
    if curr_chunk:
        unsubmitted_chunks.append((chunk_start, curr_chunk))

    record_lookup = {r["sentence_id"]: r for r in records}
    batch_retry_counts: Dict[str, int] = {}
    chunk_ptr = 0
    start_time = time.time()
    single_retry_count = 0

    while True:
        active_batches = [
            b for b in registry.get("batches", [])
            if b.get("state") not in ("BATCH_STATE_SUCCEEDED", "BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED", "BATCH_STATE_EXPIRED")
        ]

        while len(active_batches) < max_active_batches and chunk_ptr < len(unsubmitted_chunks):
            s_idx, chunk_recs = unsubmitted_chunks[chunk_ptr]
            chunk_ptr += 1
            display_name = f"{slice_name}_{s_idx}_{s_idx + len(chunk_recs) - 1}_{int(time.time())}"
            print(f"  [BATCH SUBMIT] {display_name} ({len(chunk_recs)} sentences, model={model})...")
            try:
                payload = build_batch_payload(chunk_recs, display_name, thinking_budget, thinking_level)
                resp = submit_batch_job(api_key, payload, model)
                batch_id = resp.get("name")
                print(f"    -> Enqueued: {batch_id}")

                b_entry = {
                    "name": batch_id,
                    "displayName": display_name,
                    "start_idx": s_idx,
                    "count": len(chunk_recs),
                    "sentence_ids": [r["sentence_id"] for r in chunk_recs],
                    "state": "BATCH_STATE_PENDING",
                    "create_time": time.time()
                }
                registry["batches"].append(b_entry)
                active_batches.append(b_entry)
                save_registry(registry_path, registry)
                time.sleep(1.0)
            except Exception as e:
                print(f"    [WARN] Failed submitting chunk {display_name}: {e}")
                chunk_ptr -= 1
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
                s_ids = b_entry["sentence_ids"]

                for idx, s_id in enumerate(s_ids):
                    orig_rec = record_lookup.get(s_id)
                    if not orig_rec:
                        continue
                    annotated_rec = json.loads(json.dumps(orig_rec))

                    resp_obj = inlined[idx].get("response", {}) if idx < len(inlined) else {}
                    gen_text = extract_text_from_candidate(resp_obj)
                    batch_sel = parse_selection_json(gen_text) or {}
                    usage_meta = resp_obj.get("usageMetadata", {})

                    # Stage 3: Verify completeness across all ambiguous tokens in sentence
                    amb_tokens = [t for t in annotated_rec.get("tokens", []) if t.get("is_ambiguous", False)]
                    batch_valid_keys: Set[str] = set()
                    for t in amb_tokens:
                        t_idx_str = str(t["index"])
                        valid_ids = {c["id"] for c in t.get("candidates", [])}
                        if batch_sel.get(t_idx_str) in valid_ids:
                            batch_valid_keys.add(t_idx_str)

                    final_sel = dict(batch_sel)
                    if len(batch_valid_keys) < len(amb_tokens):
                        single_retry_count += 1
                        final_sel, retry_usage = call_single_sentence_retry(
                            api_key, annotated_rec, batch_sel, model, thinking_budget, thinking_level
                        )
                        usage_meta = merge_usage_metadata(usage_meta, retry_usage)

                    if usage_meta:
                        annotated_rec["llm_usage"] = usage_meta

                    sent_complete = True
                    for t in amb_tokens:
                        t_idx_str = str(t["index"])
                        valid_ids = [c["id"] for c in t.get("candidates", [])]
                        chosen_id = final_sel.get(t_idx_str)
                        if chosen_id is not None and chosen_id in valid_ids:
                            t["selected_candidate_id"] = chosen_id
                            if t_idx_str in batch_valid_keys and batch_sel.get(t_idx_str) == chosen_id:
                                t["selection_method"] = f"llm_batch_{model}"
                            else:
                                t["selection_method"] = f"llm_retry_{model}"
                        else:
                            t["selected_candidate_id"] = valid_ids[0] if valid_ids else 0
                            t["selection_method"] = "fallback_top"
                            sent_complete = False
                            print(f"    [WARN] Fallback used on {s_id} token #{t_idx_str} ({t.get('surface')})")

                    annotated_rec["is_complete"] = sent_complete
                    completed_out_records[s_id] = annotated_rec
                    if sent_complete and s_id not in completed_ids:
                        completed_ids.add(s_id)
                        registry["completed_sentence_ids"].append(s_id)

                # Atomic write of _annotated.jsonl BEFORE saving registry
                tmp_ann = annotated_path.with_suffix(".jsonl.tmp")
                with open(tmp_ann, "w", encoding="utf-8") as out_f:
                    for r in records:
                        sid = r["sentence_id"]
                        if sid in completed_out_records:
                            out_f.write(json.dumps(completed_out_records[sid], ensure_ascii=False) + "\n")
                os.replace(tmp_ann, annotated_path)
                save_registry(registry_path, registry)
                print(f"    -> Saved {len(completed_out_records):,}/{total_sentences:,} annotated sentences to {annotated_path}")

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
                    unsubmitted_chunks.append((b_entry.get("start_idx", 0), retry_recs))
                save_registry(registry_path, registry)

        still_active = [
            b for b in registry.get("batches", [])
            if b.get("state") not in ("BATCH_STATE_SUCCEEDED", "BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED", "BATCH_STATE_EXPIRED")
        ]
        if not still_active and chunk_ptr >= len(unsubmitted_chunks):
            break
        time.sleep(poll_interval)

    # Stage 4: Summary Report Export (No Post-Processing Mutation)
    llm_resolved_count = 0
    fallback_count = 0

    for r in records:
        sid = r["sentence_id"]
        ann_rec = completed_out_records.get(sid)
        if not ann_rec:
            continue
        for t in ann_rec.get("tokens", []):
            if t.get("is_ambiguous", False):
                if str(t.get("selection_method", "")).startswith("llm_"):
                    llm_resolved_count += 1
                else:
                    fallback_count += 1

    if export_zemberek_txt:
        zemberek_txt_path = annotated_dir / f"{slice_name}.zemberek.txt"
        export_to_zemberek_native_txt(annotated_path, zemberek_txt_path)

    prompt_tokens = sum(r.get("llm_usage", {}).get("promptTokenCount", 0) for r in completed_out_records.values())
    cand_tokens = sum(r.get("llm_usage", {}).get("candidatesTokenCount", 0) for r in completed_out_records.values())
    thought_tokens = sum(r.get("llm_usage", {}).get("thoughtsTokenCount", 0) for r in completed_out_records.values())

    summary = {
        "slice_name": slice_name,
        "model": model,
        "thinking_budget": thinking_budget,
        "thinking_level": thinking_level,
        "total_sentences": len(completed_out_records),
        "total_tokens": total_tokens,
        "total_ambiguous_tokens": total_ambig_tokens,
        "llm_disambiguated_tokens": llm_resolved_count,
        "fallback_tokens": fallback_count,
        "single_sentence_retries": single_retry_count,
        "llm_success_rate_pct": round(llm_resolved_count / max(1, total_ambig_tokens) * 100.0, 3),
        "usage": {
            "prompt_tokens": prompt_tokens,
            "candidates_tokens": cand_tokens,
            "thoughts_tokens": thought_tokens,
            "total_tokens": prompt_tokens + cand_tokens + thought_tokens,
        },
        "elapsed_seconds": round(time.time() - start_time, 1),
        "paths": {
            "candidates_jsonl": str(candidates_path),
            "annotated_jsonl": str(annotated_path),
        }
    }
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"  [COMPLETE] {slice_name}: {llm_resolved_count:,}/{total_ambig_tokens:,} ambiguous tokens resolved "
          f"({summary['llm_success_rate_pct']}%)")
    print(f"  [OUTPUT]   Annotated JSONL: {annotated_path}")
    print(f"  [REPORT]   Summary JSON:    {summary_path}")


def export_to_zemberek_native_txt(annotated_jsonl_path: Path, output_txt_path: Path) -> None:
    """Optional export to Zemberek S:<sentence> text format with '*' on selected parses."""
    with open(annotated_jsonl_path, "r", encoding="utf-8") as in_f, \
         open(output_txt_path, "w", encoding="utf-8") as out_f:
        for line in in_f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            out_f.write(f"S:{rec['text']}\n")
            for t in rec.get("tokens", []):
                out_f.write(f"{t['surface']}\n")
                sel_id = t.get("selected_candidate_id", 0)
                is_amb = t.get("is_ambiguous", False)
                for c in t.get("candidates", []):
                    star = "*" if (is_amb and c.get("id") == sel_id) else ""
                    out_f.write(f"{c['zemberek_key']}{star}\n")


def parse_slice_numbers(spec: str) -> List[int]:
    """Parses slice range specifications like '1', '1-5', or '1,3,5-8'."""
    nums: List[int] = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            a, b = part.split("-", 1)
            nums.extend(range(int(a), int(b) + 1))
        else:
            nums.append(int(part))
    return sorted(set(nums))


def main() -> None:
    load_env()
    load_env(".env.local")

    repo_root = Path(__file__).resolve().parent.parent.parent.parent
    default_slices_dir = Path("/home/dndara/data/turkish/selected_slices")
    default_output_dir = Path("/home/dndara/data/turkish/annotated_slices")

    parser = argparse.ArgumentParser(
        description="GSLA-v2.1: Zemberek-Only Golden Set Slice Annotator via Gemini Batch API"
    )
    parser.add_argument(
        "--pool", choices=["test", "train"], default="test",
        help="Which slice pool to process ('test' for test_slice_XXX, 'train' for train_slice_XXX)"
    )
    parser.add_argument(
        "--slices", default="1",
        help="Slice index or range to process, e.g. '1', '1-5', '1,2,10' (default: '1')"
    )
    parser.add_argument(
        "--input-file", default=None,
        help="Optional explicit path to a single slice .txt file (overrides --pool and --slices)"
    )
    parser.add_argument(
        "--slices-dir", default=str(default_slices_dir),
        help=f"Base directory containing test_slices/ and train_slices/ (default: {default_slices_dir})"
    )
    parser.add_argument(
        "--output-dir", default=str(default_output_dir),
        help=f"Output root directory for candidates, registries, and annotated JSONLs (default: {default_output_dir})"
    )
    parser.add_argument(
        "--model", default="gemini-4-argon",
        help="Gemini model identifier for Batch API (default: gemini-4-argon)"
    )
    parser.add_argument(
        "--reasoning", choices=["low", "medium", "high"], default="medium",
        help="Reasoning effort preset: low (512), medium (1024), high (2048) thinking tokens (default: medium)"
    )
    parser.add_argument(
        "--thinking-budget", type=int, default=None,
        help="Explicit thinking token budget (overrides --reasoning preset if set)"
    )
    parser.add_argument(
        "--thinking-level", choices=["LOW", "MEDIUM", "HIGH"], default=None,
        help="Optional explicit Gemini API thinkingLevel string (if preferred over numeric thinkingBudget)"
    )
    parser.add_argument(
        "--batch-size", type=int, default=500,
        help="Number of sentences per Batch API job (default: 500 -> 2 batches per 1,000-sentence slice)"
    )
    parser.add_argument(
        "--max-active-batches", type=int, default=4,
        help="Maximum concurrent Batch API jobs in flight (default: 4)"
    )
    parser.add_argument(
        "--poll-interval", type=int, default=15,
        help="Seconds between Batch API status checks (default: 15)"
    )
    parser.add_argument(
        "--limit", type=int, default=0,
        help="Optional limit on number of sentences per slice to process (0 = all 1,000)"
    )
    parser.add_argument(
        "--extract-only", action="store_true",
        help="Only run Stage 1 (Java Zemberek candidate extraction to JSONL) without calling the LLM"
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Extract candidates, print a sample Zemberek-only prompt, and estimate token usage without calling the API"
    )
    parser.add_argument(
        "--export-zemberek-txt", action="store_true",
        help="Also export Zemberek's native S:<sentence> .txt training format alongside _annotated.jsonl"
    )
    parser.add_argument(
        "--prompt-mode", choices=["full", "conventions"], default="full",
        help="System prompt mode: 'full' (GSLA-v2.4) or 'conventions' (GSLA-Test-Conv-v1 convention-only prompt)"
    )

    args = parser.parse_args()

    global SYSTEM_INSTRUCTION
    if args.prompt_mode == "conventions":
        SYSTEM_INSTRUCTION = CONVENTIONS_ONLY_SYSTEM_INSTRUCTION
        print("  [PROMPT MODE] Using GSLA-Test-Conv-v1 (Convention-Only System Instruction)")

    thinking_budget = (
        args.thinking_budget
        if args.thinking_budget is not None
        else REASONING_BUDGET_PRESETS[args.reasoning]
    )
    thinking_level = args.thinking_level
    if thinking_level is None and args.thinking_budget is None and "2.5" not in args.model.lower():
        thinking_level = args.reasoning.upper()

    api_key = (
        os.environ.get("GEMINI_API_KEY")
        or os.environ.get("GOOGLE_API_KEY")
        or ""
    ).strip().strip("'").strip('"')

    slices_dir = Path(args.slices_dir)
    output_root = Path(args.output_dir) / args.pool

    targets: List[Tuple[Optional[int], str, Path]] = []
    if args.input_file:
        p = Path(args.input_file).resolve()
        targets.append((None, p.stem, p))
    else:
        slice_nums = parse_slice_numbers(args.slices)
        for num in slice_nums:
            if args.pool == "test":
                sname = f"test_slice_{num:03d}"
                spath = slices_dir / "test_slices" / f"{sname}.txt"
            else:
                sname = f"train_slice_{num:03d}"
                spath = slices_dir / "train_slices" / f"{sname}.txt"
            targets.append((num, sname, spath))

    for snum, sname, spath in targets:
        process_single_slice(
            repo_root=repo_root,
            pool=args.pool,
            slice_index=snum,
            slice_name=sname,
            slice_txt_path=spath,
            output_root=output_root,
            api_key=api_key,
            model=args.model,
            thinking_budget=thinking_budget,
            thinking_level=thinking_level,
            batch_size=args.batch_size,
            max_active_batches=args.max_active_batches,
            poll_interval=args.poll_interval,
            limit=args.limit,
            extract_only=args.extract_only,
            dry_run=args.dry_run,
            export_zemberek_txt=args.export_zemberek_txt,
        )


if __name__ == "__main__":
    main()
