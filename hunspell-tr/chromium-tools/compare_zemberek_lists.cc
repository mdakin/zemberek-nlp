// Large-scale cross-evaluation of Zemberek Java Solved/Unsolved word lists (with frequencies)
// against New Chrome tr-TR-5-0.bdic and Legacy Chrome tr-TR-4-0.bdic under exact HUNSPELL_CHROME_CLIENT.
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "base/containers/span.h"
#include "third_party/hunspell/google/bdict.h"
#include "third_party/hunspell/src/hunspell/hunspell.hxx"

namespace {

enum Cat : uint8_t {
  CAT_STRICT = 0,
  CAT_CASE_APOS = 1,
  CAT_EXT_INF = 2,
  CAT_DYNAMIC = 3,
  CAT_UNSOLVED = 4,
  CAT_COUNT = 5
};

const char* CatName(Cat c) {
  switch (c) {
    case CAT_STRICT: return "STRICT_SPELL_OK";
    case CAT_CASE_APOS: return "CASE_OR_APOS_ONLY";
    case CAT_EXT_INF: return "EXT_OR_INFORMAL";
    case CAT_DYNAMIC: return "DYNAMIC_RUNTIME";
    case CAT_UNSOLVED: return "UNSOLVED";
    default: return "UNKNOWN";
  }
}

struct Entry {
  std::string word;
  uint64_t total_freq = 0;
  uint64_t formal_freq = 0;
  uint64_t forum_freq = 0;
  Cat cat = CAT_UNSOLVED;
  uint8_t new_ok = 0;
  uint8_t old_ok = 0;
};

Cat ParseCat(const std::string& s) {
  if (s == "STRICT_SPELL_OK") return CAT_STRICT;
  if (s == "CASE_OR_APOS_ONLY") return CAT_CASE_APOS;
  if (s == "EXT_OR_INFORMAL") return CAT_EXT_INF;
  if (s == "DYNAMIC_RUNTIME") return CAT_DYNAMIC;
  return CAT_UNSOLVED;
}

void LoadTsv(const std::string& path, std::vector<Entry>* out) {
  std::ifstream f(path);
  if (!f) {
    std::cerr << "Failed to open TSV: " << path << "\n";
    std::exit(1);
  }
  std::string line;
  while (std::getline(f, line)) {
    if (line.empty()) continue;
    size_t t1 = line.find('\t');
    size_t t2 = line.find('\t', t1 + 1);
    size_t t3 = line.find('\t', t2 + 1);
    size_t t4 = line.find('\t', t3 + 1);
    if (t1 == std::string::npos || t4 == std::string::npos) continue;
    Entry e;
    e.word = line.substr(0, t1);
    e.total_freq = std::strtoull(line.c_str() + t1 + 1, nullptr, 10);
    e.formal_freq = std::strtoull(line.c_str() + t2 + 1, nullptr, 10);
    e.forum_freq = std::strtoull(line.c_str() + t3 + 1, nullptr, 10);
    e.cat = ParseCat(line.substr(t4 + 1));
    out->push_back(std::move(e));
  }
}

bool WriteAll(int fd, const uint8_t* buf, size_t len) {
  size_t off = 0;
  while (off < len) {
    ssize_t n = ::write(fd, buf + off, len - off);
    if (n <= 0) return false;
    off += static_cast<size_t>(n);
  }
  return true;
}

bool ReadAll(int fd, uint8_t* buf, size_t len) {
  size_t off = 0;
  while (off < len) {
    ssize_t n = ::read(fd, buf + off, len - off);
    if (n <= 0) return false;
    off += static_cast<size_t>(n);
  }
  return true;
}

// Evaluates `hs.spell()` on all `entries` inside an isolated fork() hierarchy so Hunspell's
// HUNSPELL_CHROME_CLIENT static buffers never collide across dictionaries or threads.
std::vector<uint8_t> EvaluateBDictParallel(const std::string& bdic_path, const std::vector<Entry>& entries) {
  const size_t total = entries.size();
  std::vector<uint8_t> result(total, 0);
  if (total == 0) return result;

  int master_pipe[2];
  if (::pipe(master_pipe) != 0) std::exit(1);

  auto t0 = std::chrono::steady_clock::now();
  pid_t master_child = ::fork();
  if (master_child == 0) {
    ::close(master_pipe[0]);
    int fd = ::open(bdic_path.c_str(), O_RDONLY);
    if (fd < 0) ::_exit(2);
    struct stat st{};
    if (::fstat(fd, &st) != 0 || st.st_size <= 0) ::_exit(2);
    size_t sz = static_cast<size_t>(st.st_size);
    void* p = ::mmap(nullptr, sz, PROT_READ, MAP_PRIVATE, fd, 0);
    if (p == MAP_FAILED) ::_exit(2);
    base::span<const uint8_t> span_bytes(static_cast<const uint8_t*>(p), sz);
    if (!hunspell::BDict::Verify(span_bytes)) ::_exit(3);
    Hunspell hs(span_bytes);

    unsigned int nprocs = 8;
    size_t chunk = (total + nprocs - 1) / nprocs;
    std::vector<int> fds(nprocs, -1);
    std::vector<pid_t> pids(nprocs, -1);

    for (unsigned int wi = 0; wi < nprocs; ++wi) {
      size_t from = wi * chunk;
      size_t to = std::min(total, from + chunk);
      int wpipe[2];
      if (::pipe(wpipe) != 0) ::_exit(4);
      pid_t wp = ::fork();
      if (wp == 0) {
        ::close(wpipe[0]);
        ::close(master_pipe[1]);
        std::vector<uint8_t> local;
        if (to > from) {
          local.resize(to - from);
          for (size_t idx = from; idx < to; ++idx) {
            const std::string& w = entries[idx].word;
            local[idx - from] = (w.size() > 64 || hs.spell(w)) ? 1 : 0;
          }
          WriteAll(wpipe[1], local.data(), local.size());
        }
        ::close(wpipe[1]);
        ::_exit(0);
      }
      ::close(wpipe[1]);
      fds[wi] = wpipe[0];
      pids[wi] = wp;
    }

    std::vector<uint8_t> combined(total, 0);
    for (unsigned int wi = 0; wi < nprocs; ++wi) {
      size_t from = wi * chunk;
      size_t to = std::min(total, from + chunk);
      if (to > from) {
        ReadAll(fds[wi], combined.data() + from, to - from);
      }
      ::close(fds[wi]);
      int wst = 0;
      ::waitpid(pids[wi], &wst, 0);
    }

    WriteAll(master_pipe[1], combined.data(), combined.size());
    ::close(master_pipe[1]);
    ::munmap(p, sz);
    ::close(fd);
    ::_exit(0);
  }

  ::close(master_pipe[1]);
  if (!ReadAll(master_pipe[0], result.data(), total)) {
    std::cerr << "Failed reading results from worker for " << bdic_path << "\n";
    std::exit(1);
  }
  ::close(master_pipe[0]);
  int st = 0;
  ::waitpid(master_child, &st, 0);
  auto t1 = std::chrono::steady_clock::now();
  double sec = std::chrono::duration<double>(t1 - t0).count();
  std::cout << "  Evaluated " << total << " unique words against " << bdic_path
            << " in " << std::fixed << std::setprecision(2) << sec << " s\n";
  return result;
}

struct BucketStats {
  uint64_t types = 0;
  uint64_t types_new_acc = 0;
  uint64_t types_old_acc = 0;

  uint64_t tot_tok = 0;
  uint64_t tot_new_acc = 0;
  uint64_t tot_old_acc = 0;

  uint64_t frm_tok = 0;
  uint64_t frm_new_acc = 0;
  uint64_t frm_old_acc = 0;

  uint64_t web_tok = 0;
  uint64_t web_new_acc = 0;
  uint64_t web_old_acc = 0;

  void Add(const Entry& e) {
    types++;
    tot_tok += e.total_freq;
    frm_tok += e.formal_freq;
    web_tok += e.forum_freq;
    if (e.new_ok) {
      types_new_acc++;
      tot_new_acc += e.total_freq;
      frm_new_acc += e.formal_freq;
      web_new_acc += e.forum_freq;
    }
    if (e.old_ok) {
      types_old_acc++;
      tot_old_acc += e.total_freq;
      frm_old_acc += e.formal_freq;
      web_old_acc += e.forum_freq;
    }
  }
};

double Pct(uint64_t num, uint64_t den) {
  return den == 0 ? 0.0 : (100.0 * static_cast<double>(num) / static_cast<double>(den));
}

void DumpTopDisagreements(
    const std::string& out_path,
    const std::vector<Entry>& all,
    auto predicate,
    size_t limit = 1000) {
  std::vector<const Entry*> picked;
  picked.reserve(4096);
  for (const auto& e : all) {
    if (predicate(e)) picked.push_back(&e);
  }
  std::sort(picked.begin(), picked.end(), [](const Entry* a, const Entry* b) {
    if (a->total_freq != b->total_freq) return a->total_freq > b->total_freq;
    return a->word < b->word;
  });
  std::ofstream out(out_path);
  out << "word\ttotal_freq\tformal_freq\tforum_freq\tzemberek_cat\tnew_5_0_ok\tlegacy_4_0_ok\n";
  for (size_t i = 0; i < std::min(limit, picked.size()); ++i) {
    const Entry* e = picked[i];
    out << e->word << "\t" << e->total_freq << "\t" << e->formal_freq << "\t"
        << e->forum_freq << "\t" << CatName(e->cat) << "\t"
        << int(e->new_ok) << "\t" << int(e->old_ok) << "\n";
  }
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 5) {
    std::cerr << "Usage: compare_zemberek_lists <solved.tsv> <unsolved.tsv> <new.bdic> <legacy.bdic> [out_dir]\n";
    return 1;
  }
  std::string solved_tsv = argv[1];
  std::string unsolved_tsv = argv[2];
  std::string new_bdic = argv[3];
  std::string old_bdic = argv[4];
  std::string out_dir = argc >= 6 ? argv[5] : ".";

  std::vector<Entry> all;
  all.reserve(2000000);
  LoadTsv(solved_tsv, &all);
  size_t solved_cnt = all.size();
  LoadTsv(unsolved_tsv, &all);
  std::cout << "Loaded " << solved_cnt << " Zemberek-SOLVED words + "
            << (all.size() - solved_cnt) << " Zemberek-UNSOLVED words (total=" << all.size() << ")\n";

  auto new_flags = EvaluateBDictParallel(new_bdic, all);
  auto old_flags = EvaluateBDictParallel(old_bdic, all);
  for (size_t i = 0; i < all.size(); ++i) {
    all[i].new_ok = new_flags[i];
    all[i].old_ok = old_flags[i];
  }

  BucketStats by_cat[CAT_COUNT];
  BucketStats all_solved, all_unsolved;
  uint64_t new_old_agree_tok = 0, grand_tot_tok = 0;

  for (const auto& e : all) {
    by_cat[e.cat].Add(e);
    if (e.cat == CAT_UNSOLVED) all_unsolved.Add(e);
    else all_solved.Add(e);
    grand_tot_tok += e.total_freq;
    if (e.new_ok == e.old_ok) new_old_agree_tok += e.total_freq;
  }

  std::ostringstream rep;
  rep << std::fixed << std::setprecision(2);
  rep << "\n========================================================================================\n";
  rep << "  ZEMBEREK JAVA 0.20.1 vs. NEW tr-TR-5-0.bdic (4.30 MB) vs. LEGACY tr-TR-4-0.bdic (9.64 MB)\n";
  rep << "========================================================================================\n";
  rep << "Grand Total Evaluated: " << all.size() << " unique word types | " << grand_tot_tok
      << " running tokens (Formal: " << (all_solved.frm_tok + all_unsolved.frm_tok)
      << " | Forum: " << (all_solved.web_tok + all_unsolved.web_tok) << ")\n\n";

  auto PrintRow = [&](const char* label, const BucketStats& b) {
    rep << "Bucket: " << label << "\n";
    rep << "  Unique Types:   " << std::setw(10) << b.types
        << " | New Accepted: " << std::setw(9) << b.types_new_acc << " (" << std::setw(6) << Pct(b.types_new_acc, b.types) << "%)"
        << " | Legacy Accepted: " << std::setw(9) << b.types_old_acc << " (" << std::setw(6) << Pct(b.types_old_acc, b.types) << "%)\n";
    rep << "  All Tokens:     " << std::setw(10) << b.tot_tok
        << " | New Accepted: " << std::setw(9) << b.tot_new_acc << " (" << std::setw(6) << Pct(b.tot_new_acc, b.tot_tok) << "%)"
        << " | Legacy Accepted: " << std::setw(9) << b.tot_old_acc << " (" << std::setw(6) << Pct(b.tot_old_acc, b.tot_tok) << "%)\n";
    rep << "  Formal Tokens:  " << std::setw(10) << b.frm_tok
        << " | New Accepted: " << std::setw(9) << b.frm_new_acc << " (" << std::setw(6) << Pct(b.frm_new_acc, b.frm_tok) << "%)"
        << " | Legacy Accepted: " << std::setw(9) << b.frm_old_acc << " (" << std::setw(6) << Pct(b.frm_old_acc, b.frm_tok) << "%)\n";
    rep << "  Forum Tokens:   " << std::setw(10) << b.web_tok
        << " | New Accepted: " << std::setw(9) << b.web_new_acc << " (" << std::setw(6) << Pct(b.web_new_acc, b.web_tok) << "%)"
        << " | Legacy Accepted: " << std::setw(9) << b.web_old_acc << " (" << std::setw(6) << Pct(b.web_old_acc, b.web_tok) << "%)\n\n";
  };

  rep << "--- PART A: RAW 2-LIST COMPARISON (SOLVED vs UNSOLVED IN ZEMBEREK JAVA) ---\n\n";
  PrintRow("LIST 1: ALL ZEMBEREK-SOLVED", all_solved);
  PrintRow("LIST 2: ALL ZEMBEREK-UNSOLVED", all_unsolved);

  uint64_t raw_agree_new_tok = all_solved.tot_new_acc + (all_unsolved.tot_tok - all_unsolved.tot_new_acc);
  uint64_t raw_agree_old_tok = all_solved.tot_old_acc + (all_unsolved.tot_tok - all_unsolved.tot_old_acc);
  uint64_t raw_agree_new_types = all_solved.types_new_acc + (all_unsolved.types - all_unsolved.types_new_acc);
  uint64_t raw_agree_old_types = all_solved.types_old_acc + (all_unsolved.types - all_unsolved.types_old_acc);

  rep << "Overall Raw Agreement with Zemberek JavaTurkishMorphology (Solved=Accept, Unsolved=Reject):\n";
  rep << "  Token-weighted agreement: New tr-TR-5-0.bdic = " << Pct(raw_agree_new_tok, grand_tot_tok)
      << "% | Legacy tr-TR-4-0.bdic = " << Pct(raw_agree_old_tok, grand_tot_tok) << "%\n";
  rep << "  Unique-type agreement:    New tr-TR-5-0.bdic = " << Pct(raw_agree_new_types, all.size())
      << "% | Legacy tr-TR-4-0.bdic = " << Pct(raw_agree_old_types, all.size()) << "%\n\n";

  // Strict TDK Spellchecker Agreement: STRICT_SPELL_OK = Accept; CASE_OR_APOS_ONLY + EXT_OR_INFORMAL + UNSOLVED = Reject (excluding DYNAMIC_RUNTIME or evaluating separately)
  const auto& s_ok = by_cat[CAT_STRICT];
  uint64_t strict_neg_tok = by_cat[CAT_CASE_APOS].tot_tok + by_cat[CAT_EXT_INF].tot_tok + all_unsolved.tot_tok;
  uint64_t strict_neg_rej_new =
      (by_cat[CAT_CASE_APOS].tot_tok - by_cat[CAT_CASE_APOS].tot_new_acc) +
      (by_cat[CAT_EXT_INF].tot_tok - by_cat[CAT_EXT_INF].tot_new_acc) +
      (all_unsolved.tot_tok - all_unsolved.tot_new_acc);
  uint64_t strict_neg_rej_old =
      (by_cat[CAT_CASE_APOS].tot_tok - by_cat[CAT_CASE_APOS].tot_old_acc) +
      (by_cat[CAT_EXT_INF].tot_tok - by_cat[CAT_EXT_INF].tot_old_acc) +
      (all_unsolved.tot_tok - all_unsolved.tot_old_acc);
  uint64_t strict_eval_tok = s_ok.tot_tok + strict_neg_tok;

  rep << "Strict Zemberek TurkishSpellChecker Agreement (STRICT_SPELL_OK=Accept, Case/Ext/Unsolved=Reject):\n";
  rep << "  Token-weighted agreement: New tr-TR-5-0.bdic = "
      << Pct(s_ok.tot_new_acc + strict_neg_rej_new, strict_eval_tok)
      << "% | Legacy tr-TR-4-0.bdic = "
      << Pct(s_ok.tot_old_acc + strict_neg_rej_old, strict_eval_tok) << "%\n\n";

  rep << "--- PART B: GRANULAR BREAKDOWN OF LIST 1 (4 ZEMBEREK SOLVED SUB-BUCKETS) ---\n\n";
  PrintRow("1A. STRICT_SPELL_OK (Static Lexicon + Valid Case & Apostrophe)", by_cat[CAT_STRICT]);
  PrintRow("1B. CASE_OR_APOS_ONLY (Lowercase Proper / Bad Apostrophe)", by_cat[CAT_CASE_APOS]);
  PrintRow("1C. EXT_OR_INFORMAL (non-tdk.dict / Spoken Informal)", by_cat[CAT_EXT_INF]);
  PrintRow("1D. DYNAMIC_RUNTIME (UnidentifiedTokenAnalyzer Proper/Numeral)", by_cat[CAT_DYNAMIC]);

  std::cout << rep.str();
  {
    std::ofstream sf(out_dir + "/comparison_summary.txt");
    sf << rep.str();
  }

  DumpTopDisagreements(out_dir + "/disagree_strict_missed_by_new.tsv", all,
      [](const Entry& e) { return e.cat == CAT_STRICT && !e.new_ok; });
  DumpTopDisagreements(out_dir + "/disagree_strict_missed_by_legacy.tsv", all,
      [](const Entry& e) { return e.cat == CAT_STRICT && !e.old_ok; });
  DumpTopDisagreements(out_dir + "/disagree_unsolved_accepted_by_new.tsv", all,
      [](const Entry& e) { return e.cat == CAT_UNSOLVED && e.new_ok; });
  DumpTopDisagreements(out_dir + "/disagree_unsolved_accepted_by_legacy.tsv", all,
      [](const Entry& e) { return e.cat == CAT_UNSOLVED && e.old_ok; });
  DumpTopDisagreements(out_dir + "/disagree_ext_accepted_by_new.tsv", all,
      [](const Entry& e) { return e.cat == CAT_EXT_INF && e.new_ok; });
  DumpTopDisagreements(out_dir + "/disagree_ext_rejected_by_new.tsv", all,
      [](const Entry& e) { return e.cat == CAT_EXT_INF && !e.new_ok; });
  DumpTopDisagreements(out_dir + "/disagree_dynamic_missed_by_new.tsv", all,
      [](const Entry& e) { return e.cat == CAT_DYNAMIC && !e.new_ok; });
  DumpTopDisagreements(out_dir + "/both_unsolved_top1000.tsv", all,
      [](const Entry& e) { return e.cat == CAT_UNSOLVED && !e.new_ok; });

  std::cout << "Saved summary and 8 top-1000 disagreement TSVs to " << out_dir << "\n";
  return 0;
}
