# Post-`0.19.0` Normalization Status & Fork PR Workflow

## 1. Git Rebase & Repository Setup (`mdakin/zemberek-nlp` vs. `ahmetaa/zemberek-nlp`)

- **Rebase Status:** Local `master` is now cleanly fast-forwarded to **`upstream/master` (`f82437ad`, Zemberek `0.19.0`)** with all of our post-`0.19.0` `normalization/v2` code simplifications preserved cleanly on top (`4` modified `.java` files: **`+232 / -1,100` lines**, plus [shorthands.tsv](file:///home/dndara/work/zemberek-nlp/normalization/src/main/resources/normalization/v2/shorthands.tsv)).
- **Current Remote Layout (Already Using Your Fork!):**
  - `origin` → `https://github.com/mdakin/zemberek-nlp.git` *(your personal fork, authenticated as `mdakin`)*
  - `upstream` → `https://github.com/ahmetaa/zemberek-nlp.git` *(`ahmetaa`'s main repository)*
  - **Only catch today:** `gh repo set-default` was pointing to `ahmetaa/zemberek-nlp`, and `origin/master` on GitHub is 8 commits behind `0.19.0`.

### Options for Working & Opening PRs on Your Own Fork (`mdakin/zemberek-nlp`)

| Option | How It Works | Setup Needed | Pros / Trade-Offs |
| :--- | :--- | :--- | :--- |
| **Option 1 *(Recommended)*: Fork-First (`master` + Feature PRs on `mdakin`)** | Set `gh` default repo to `mdakin/zemberek-nlp` and push `0.19.0` to `origin/master`. Every `feat/*` branch pushes to `origin` and opens PRs into **`mdakin/zemberek-nlp:master`**. When a feature is ready for `ahmetaa`, open an upstream PR explicitly (`gh pr create -R ahmetaa/zemberek-nlp --head mdakin:<branch>`). | **2 commands** (`git push origin master` + `gh repo set-default mdakin/zemberek-nlp`) | Zero noise on `ahmetaa/zemberek-nlp`; full PR review/diff/history UI on your own GitHub fork. *(Note: if you merge experimental PRs into `mdakin:master` that you don't send upstream, cherry-pick onto a clean branch off `upstream/master` when sending to `ahmetaa`.)* |
| **Option 2 *(Cleanest Upstream Separation)*: `dev` Integration Branch on `mdakin`** | Keep `mdakin:master` strictly identical to `upstream/master` (`0.19.0`), create `mdakin:dev` (or `v2-next`) on your fork, and target all experimental PRs on your fork against **`mdakin:dev`**. For upstream PRs to `ahmetaa`, branch off `master` and cherry-pick/squash the finalized commits. | **3 commands** (`gh repo set-default mdakin/zemberek-nlp`, push `master` + `dev`) | `mdakin:master` never diverges from `ahmetaa:master`, so upstream PRs are guaranteed 1-click clean while `mdakin:dev` accumulates all your ongoing experiments and PRs. |
| **Option 3: Per-Command `-R` Flag Only** | Leave `gh` default as `ahmetaa/zemberek-nlp` and pass `-R mdakin/zemberek-nlp` manually on fork PRs. | 0 commands | Error-prone — omitting `-R` accidentally opens a PR on `ahmetaa/zemberek-nlp`. |

---

## 2. Where Normalization `v2` Stands Today (`0.19.0` Shipped vs. Current Ready State)

### 2.1 Architecture, Data & Footprint Comparison

| Dimension | Shipped in `0.19.0` (`#381` / `#383`) | Current Post-`0.19.0` State *(Verified Locally)* | Improvement |
| :--- | :---: | :---: | :--- |
| **Clean Language Model** | `1.8M` uncurated sentences (`v5` MPHF) **+ `SmoothLm`** (`lm.2gram.slm.gz`) | **`8.0M` Curated (`75/25`)** (`6.0M` news/wiki + `2.0M` subtitles/onedio, `v5(6/9/18)` MPHF, **`No SmoothLm`**) | **`4.4×` clean scale**; removes `SmoothLm` (`-14..20 MiB` heap RAM, `-3.4 MiB` runtime dependency) |
| **Parallel Training Pool** | `75,000` sentences (`v3.0`/`v3.1` + `1.8K` synthetic `D-Lite` calibration) | **`85,000` real sentences** (`75K` `v3` + **`10K` `v4.0` Heavy**, **`0` synthetic calibration**) | `+10K` `v4.0` Heavy pairs (`+15.4%` multi-edit `LOO` rewrites); cleaner weights without synthetic calibration |
| **Held-Out Evaluation** | `test_slice_01` (`v3.1`), `nasty_5k`, `MultiLexNorm`, `GECTurk`, `Synth-*` | All previous **+ `test_slice_02_v4_hhigh`** (`5,000` Heavy sentences, `gemini-4-hydrogen HIGH` + Prompt `v4.0`) | Eliminates `v3.1` gold-standard artifacts (unit lowercase & missing internal `.!?` caps) |
| **Runtime Generator Code (`normalization/.../v2/`)** | `4,569` LOC across 4 generator classes (`8,733` LOC package total); 24K lexicon scan at startup | **`3,525` LOC** across 4 generator classes (`7,868` LOC package total) + [shorthands.tsv](file:///home/dndara/work/zemberek-nlp/normalization/src/main/resources/normalization/v2/shorthands.tsv) (`153` entries) | **`-1,044` LOC (`-22.9%`)** in modular generators (`-1,372` LOC in `experiment`), faster startup (`0` lexicon scan), generalized `1,333` data-mined suffix rules, fixed `.!?` `computeSentenceStarts()` |
| **Runtime Model Disk Size** | `knowledge.bin.gz` (`4.0 MiB`) + `perceptron.bin.gz` (`2.6 MiB`) + `lm.2gram.slm.gz` (`3.4 MiB`) = **`10.0 MiB`** | `knowledge.bin.gz` (`~5.6 MiB`) + `perceptron.bin.gz` (`~2.6 MiB`) + **`0` `SmoothLm`** = **`~8.2 MiB`** | **`-1.8 MiB` smaller active runtime footprint** (`No SmoothLm`) despite `4.4×` larger clean corpus |

### 2.2 Benchmark Progression Across Suites

| Benchmark | Legacy `TurkishSentenceNormalizer` | `0.19.0` Shipped (`75K + 1.8M + SmoothLm`) | Current Post-`0.19.0` (`85K + 8M Curated v5, No SmoothLm, Simplified`) |
| :--- | :---: | :---: | :---: |
| **`test_slice_02_v4`** (`5K` Heavy `v4.0`) | *~53%* | `~79.6%` *(~55.1% `SentEM-Ca`)* | **`80.53%` W-ERR** (`68.10%` Lo / **`57.46%` Ca** · `1.337%` FPR) |
| **`test_slice_01`** (`5K` Heavy `v3.1`) | `54.09%` | `80.89%` (`69.08%` Lo / `59.44%` Ca) | **`81.07%` W-ERR** (`68.88%` Lo / `57.64%` Ca)* |
| **`nasty_5k`** (`5K` Adversarial Social) | `62.24%` | `90.30%` (`61.42%` Lo) | **`90.61%` W-ERR** (**`62.36%` Lo** · `0.000%` FPR) |
| **`MultiLexNorm`** *(External Academic)* | `47.51%` | `67.22%` (`44.76%` Lo / `16.78%` Ca) | **`67.43%` W-ERR** (**`46.15%` Lo** / **`17.48%` Ca**) |
| **`GECTurk-Clitic`** *(Formal `BOUN`)* | `19.65%` | `47.94%` (`46.94%` Lo) | **`48.09%` W-ERR** (**`47.14%` Lo** / **`31.36%` Ca**) |
| **`Synth-Asciified`** (`1K` Clean Asciified) | `84.89%` | `97.28%` (`87.20%` Lo / `83.90%` Ca) | **`97.50%` W-ERR** (**`88.30%` Lo** / **`86.90%` Ca** · `0.000%` FPR) |
| **`Synth-Typo-d1`** (`1K` Typos) | `74.42%` | `82.02%` | **`81.23%` W-ERR** (`73.80%` Lo · `0.000%` FPR) |

*\*Note on `test_slice_01` `SentEM-Ca`: `test_slice_01` was labeled with Prompt `v3.1`, which left second sentences lowercase after `.!?`; fixing `computeSentenceStarts()` for proper Turkish orthography gained **`+2.16 pp` `SentEM-Ca`** on `v4.0` `test_slice_02_v4` and **`+3.00 pp` `SentEM-Ca`** on `Synth-Asciified`.*

---

## 3. Exact Inventory: What Is Applied vs. Pending Before Creating a PR

1. **Already in Working Tree (`normalization/` & `experiment/`):**
   - Modular `normalization/src/main/java/zemberek/normalization/v2/` (`ShorthandExpander`, `SingleTokenRewriteGenerator`, `SplitMergeSpanGenerator`, `SpanCandidateGenerator`, `shorthands.tsv`) — rebased cleanly on `0.19.0`, nullable `SmoothLm` supported, `-1,044` LOC removed.
   - `experiment/src/main/java/zemberek/normalization/v2/` (`SpanCandidateGenerator`, `ParallelCorpusMiner`, `NormV2ModelStore`, `NormalizationV2BenchmarkRunner`) — synced with `-1,372` LOC removed, generalized suffix induction, and `test_slice_02_v4` fingerprint guard.
2. **One Step Remaining to Bundle into a Ready-to-Merge PR:**
   - `normalization/src/main/resources/normalization/v2/{knowledge.bin.gz, perceptron.bin.gz}` in the working tree are still the `0.19.0` (`75K + 1.8M + SmoothLm`) binaries, and [SpanLatticePerceptronNormalizer.java](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/v2/SpanLatticePerceptronNormalizer.java#L135-L138) `createWithDefaults(morphology)` still calls `loadDefaultSmoothLm()`.
   - Exporting the new **`85K + 8M Curated v5(6/9/18)`** `knowledge.bin.gz` + `perceptron.bin.gz`, switching `createWithDefaults(morphology)` to `createWithDefaults(morphology, null)` (zero `SmoothLm` loaded!), and running `SpanLatticePerceptronNormalizerTest` will complete the full end-to-end package (~4 minutes).
