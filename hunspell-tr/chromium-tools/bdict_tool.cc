// Standalone Chromium HUNSPELL_CHROME_CLIENT BDict inspector, unit-tester & benchmark harness.
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <atomic>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <unicode/normlzr.h>
#include <unicode/unistr.h>

#include "base/containers/span.h"
#include "third_party/hunspell/google/bdict.h"
#include "third_party/hunspell/google/bdict_reader.h"
#include "third_party/hunspell/src/hunspell/hunspell.hxx"

namespace {

struct MappedFile {
  int fd = -1;
  const uint8_t* data = nullptr;
  size_t size = 0;

  bool Open(const std::string& path) {
    fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;
    struct stat st{};
    if (::fstat(fd, &st) != 0 || st.st_size <= 0) {
      ::close(fd);
      fd = -1;
      return false;
    }
    size = static_cast<size_t>(st.st_size);
    void* p = ::mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (p == MAP_FAILED) {
      ::close(fd);
      fd = -1;
      return false;
    }
    data = static_cast<const uint8_t*>(p);
    return true;
  }

  ~MappedFile() {
    if (data && size > 0) ::munmap(const_cast<uint8_t*>(data), size);
    if (fd >= 0) ::close(fd);
  }
};

long GetRssKiB() {
  std::ifstream f("/proc/self/statm");
  long total_pages = 0, rss_pages = 0;
  if (f >> total_pages >> rss_pages) {
    return rss_pages * (::sysconf(_SC_PAGESIZE) / 1024);
  }
  return 0;
}

// Replicate SpellcheckWordIterator::Normalize (UNORM_NFKC) from Chrome renderer.
std::string ChromeNormalizeNFKC(const std::string& utf8_word) {
  icu::UnicodeString input = icu::UnicodeString::fromUTF8(
      icu::StringPiece(utf8_word.data(), static_cast<int32_t>(utf8_word.size())));
  UErrorCode status = U_ZERO_ERROR;
  icu::UnicodeString output;
  icu::Normalizer::normalize(input, UNORM_NFKC, 0, output, status);
  if (U_FAILURE(status)) return utf8_word;
  std::string res;
  output.toUTF8String(res);
  return res;
}

void InspectBDict(const std::string& path, base::span<const uint8_t> bytes) {
  bool md5_ok = hunspell::BDict::Verify(bytes);
  hunspell::BDictReader reader;
  bool init_ok = reader.Init(bytes);
  std::cout << "=== BDict Inspection: " << path << " ===\n";
  std::cout << "  File size:        " << bytes.size() << " B ("
            << (bytes.size() / (1024.0 * 1024.0)) << " MiB, "
            << (bytes.size() / 1e6) << " MB)\n";
  std::cout << "  BDict::Verify():  " << (md5_ok ? "PASS (MD5 + header valid)" : "FAIL") << "\n";
  std::cout << "  Reader::Init():   " << (init_ok ? "PASS" : "FAIL") << "\n";
  if (!md5_ok || !init_ok) {
    std::exit(1);
  }

  const auto* hdr = reinterpret_cast<const hunspell::BDict::Header*>(bytes.data());
  const auto* ahdr = reinterpret_cast<const hunspell::BDict::AffHeader*>(
      bytes.data() + hdr->aff_offset);

  size_t af_count = 0, max_af_len = 0, max_flags_in_af = 0;
  auto af_it = reader.GetAfLineIterator();
  while (auto line = af_it.Advance()) {
    if (af_count > 0) {  // First line emitted by BDictWriter is "AF <count>"
      max_af_len = std::max(max_af_len, line->size());
      size_t commas = static_cast<size_t>(std::count(line->begin(), line->end(), ','));
      size_t nflags = line->size() > 3 ? (commas + 1) : 0;
      max_flags_in_af = std::max(max_flags_in_af, nflags);
    }
    af_count++;
  }
  size_t real_af_groups = af_count > 0 ? af_count - 1 : 0;

  size_t sfx_lines = 0;
  auto sfx_it = reader.GetAffixLineIterator();
  while (sfx_it.Advance()) sfx_lines++;

  size_t rep_pairs = 0;
  auto rep_it = reader.GetReplacementIterator();
  std::string_view r1, r2;
  while (rep_it.GetNext(&r1, &r2)) rep_pairs++;

  size_t other_lines = 0;
  auto oth_it = reader.GetOtherLineIterator();
  while (oth_it.Advance()) other_lines++;

  size_t word_count = 0, max_word_bytes = 0, max_homonyms = 0, inline_leaves = 0, supp_leaves = 0;
  auto word_it = reader.GetAllWordIterator();
  char wbuf[256];
  int affix_ids[hunspell::BDict::MAX_AFFIXES_PER_WORD];
  while (int n = word_it.Advance(wbuf, affix_ids)) {
    word_count++;
    size_t wl = std::strlen(wbuf);
    max_word_bytes = std::max(max_word_bytes, wl);
    max_homonyms = std::max(max_homonyms, static_cast<size_t>(n));
    if (n == 1 && affix_ids[0] <= hunspell::BDict::LEAF_NODE_MAX_FIRST_AFFIX_ID) {
      inline_leaves++;
    } else {
      supp_leaves++;
    }
  }

  std::cout << "  Sections:\n";
  std::cout << "    AF groups:      " << (ahdr->affix_rule_offset - ahdr->affix_group_offset)
            << " B (" << real_af_groups << " unique AF groups, max AF line=" << max_af_len
            << " B, max flags/AF=" << max_flags_in_af << ")\n";
  std::cout << "    Affix rules:    " << (ahdr->rep_offset - ahdr->affix_rule_offset)
            << " B (" << sfx_lines << " PFX/SFX lines)\n";
  std::cout << "    REP table:      " << (ahdr->other_offset - ahdr->rep_offset)
            << " B (" << rep_pairs << " REP pairs)\n";
  std::cout << "    Other commands: " << (hdr->dic_offset - ahdr->other_offset)
            << " B (" << other_lines << " lines)\n";
  std::cout << "    Dic trie:       " << (bytes.size() - hdr->dic_offset)
            << " B (" << word_count << " unique stems, max stem=" << max_word_bytes
            << " B, max homonyms=" << max_homonyms << ")\n";
  std::cout << "    Leaf packing:   " << inline_leaves << " inline 2B leaves ("
            << (word_count ? (100.0 * inline_leaves / word_count) : 100.0)
            << "%), " << supp_leaves << " supplementary leaves\n";
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 3) {
    std::cerr << "Usage:\n"
              << "  bdict_tool --inspect <file.bdic>\n"
              << "  bdict_tool --query <file.bdic> <word1> [word2 ...]\n"
              << "  bdict_tool --bench <file.bdic> [--valid <valid.txt>] [--invalid <invalid.txt>] [--suggest <pairs.tsv>] [--json <out.json>]\n";
    return 1;
  }

  std::string mode = argv[1];
  std::string bdic_path = argv[2];

  MappedFile mf;
  if (!mf.Open(bdic_path)) {
    std::cerr << "Failed to open/mmap: " << bdic_path << "\n";
    return 1;
  }
  base::span<const uint8_t> span_bytes(mf.data, mf.size);

  if (mode == "--inspect") {
    InspectBDict(bdic_path, span_bytes);
    long rss_before = GetRssKiB();
    auto t0 = std::chrono::steady_clock::now();
    Hunspell hs(span_bytes);
    auto t1 = std::chrono::steady_clock::now();
    long rss_after = GetRssKiB();
    double init_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    std::cout << "  Hunspell Init:    " << init_ms << " ms, RSS delta: +"
              << (rss_after - rss_before) << " KiB (total RSS=" << rss_after << " KiB)\n";
    return 0;
  }

  if (mode == "--query") {
    if (!hunspell::BDict::Verify(span_bytes)) {
      std::cerr << "BDict::Verify failed!\n";
      return 1;
    }
    Hunspell hs(span_bytes);
    for (int i = 3; i < argc; ++i) {
      std::string raw = argv[i];
      std::string w = ChromeNormalizeNFKC(raw);
      // Match HunspellEngine::CheckSpelling (kMaxCheckedLen = 64)
      bool ok = (w.size() > 64) ? true : hs.spell(w);
      std::cout << raw << " -> " << (ok ? "VALID" : "MISSPELLED");
      if (!ok && w.size() <= 24) {
        auto sugs = hs.suggest(w);
        std::cout << " | suggestions (" << sugs.size() << "): ";
        for (size_t j = 0; j < std::min<size_t>(5, sugs.size()); ++j) {
          if (j) std::cout << ", ";
          std::cout << sugs[j];
        }
      }
      std::cout << "\n";
    }
    return 0;
  }

  if (mode == "--bench") {
    InspectBDict(bdic_path, span_bytes);
    long rss_before = GetRssKiB();
    auto t_init0 = std::chrono::steady_clock::now();
    Hunspell hs(span_bytes);
    auto t_init1 = std::chrono::steady_clock::now();
    long rss_after = GetRssKiB();
    double init_ms = std::chrono::duration<double, std::milli>(t_init1 - t_init0).count();

    std::string valid_path, invalid_path, suggest_path, dump_fp_path, dump_fa_path;
    for (int i = 3; i < argc; ++i) {
      std::string arg = argv[i];
      if (arg == "--valid" && i + 1 < argc) valid_path = argv[++i];
      else if (arg == "--invalid" && i + 1 < argc) invalid_path = argv[++i];
      else if (arg == "--suggest" && i + 1 < argc) suggest_path = argv[++i];
      else if (arg == "--dump-fp" && i + 1 < argc) dump_fp_path = argv[++i];
      else if (arg == "--dump-fa" && i + 1 < argc) dump_fa_path = argv[++i];
    }

    std::cout << "  Hunspell Init:    " << init_ms << " ms, RSS delta: +"
              << (rss_after - rss_before) << " KiB\n";

    if (!valid_path.empty()) {
      std::ifstream vf(valid_path);
      std::vector<std::string> words;
      std::string line;
      while (std::getline(vf, line)) {
        if (!line.empty() && line[0] != '#') {
          size_t tab = line.find('\t');
          words.push_back(ChromeNormalizeNFKC(tab == std::string::npos ? line : line.substr(0, tab)));
        }
      }
      size_t accepted = 0;
      std::vector<std::string> fps;
      auto t0 = std::chrono::steady_clock::now();
      for (const auto& w : words) {
        bool ok = (w.size() > 64) ? true : hs.spell(w);
        if (ok) accepted++;
        else fps.push_back(w);
      }
      auto t1 = std::chrono::steady_clock::now();
      double us_per_word = words.empty() ? 0.0 :
          std::chrono::duration<double, std::micro>(t1 - t0).count() / words.size();
      double recall = words.empty() ? 0.0 : (100.0 * accepted / words.size());
      double fp_rate = 100.0 - recall;
      std::cout << "  [VALID CORPUS] " << valid_path << ": "
                << accepted << " / " << words.size() << " accepted ("
                << recall << "% recall, " << fp_rate << "% FP red-underline), "
                << us_per_word << " us/word\n";
      if (!dump_fp_path.empty()) {
        std::ofstream df(dump_fp_path);
        for (const auto& w : fps) df << w << "\n";
      }
    }

    if (!invalid_path.empty()) {
      std::ifstream ivf(invalid_path);
      std::vector<std::string> bad_words;
      std::string line;
      while (std::getline(ivf, line)) {
        if (!line.empty() && line[0] != '#') {
          size_t tab = line.find('\t');
          bad_words.push_back(ChromeNormalizeNFKC(tab == std::string::npos ? line : line.substr(0, tab)));
        }
      }
      size_t rejected = 0;
      std::vector<std::string> false_accepts;
      for (const auto& w : bad_words) {
        bool ok = (w.size() > 64) ? true : hs.spell(w);
        if (!ok) rejected++;
        else false_accepts.push_back(w);
      }
      double rej_rate = bad_words.empty() ? 0.0 : (100.0 * rejected / bad_words.size());
      std::cout << "  [INVALID TRAPS] " << invalid_path << ": "
                << rejected << " / " << bad_words.size() << " rejected ("
                << rej_rate << "% true-negative precision)\n";
      if (!dump_fa_path.empty()) {
        std::ofstream df(dump_fa_path);
        for (const auto& w : false_accepts) df << w << "\n";
      }
    }

    if (!suggest_path.empty()) {
      std::ifstream sf(suggest_path);
      std::vector<std::pair<std::string, std::string>> pairs;
      std::string line;
      while (std::getline(sf, line)) {
        if (line.empty() || line[0] == '#') continue;
        size_t sep = line.find('\t');
        bool gold_first = false;
        if (sep == std::string::npos) {
          sep = line.find(',');
          gold_first = true;
        }
        if (sep == std::string::npos) continue;
        std::string a = line.substr(0, sep);
        std::string b = line.substr(sep + 1);
        std::string wrong = gold_first ? b : a;
        std::string gold = gold_first ? a : b;
        if (wrong == "input" || gold.empty() || wrong.empty()) continue;
        pairs.emplace_back(ChromeNormalizeNFKC(wrong), ChromeNormalizeNFKC(gold));
      }
      std::vector<std::pair<std::string, std::string>> elig;
      for (const auto& [wrong, gold] : pairs) {
        if (wrong.size() <= 24) elig.emplace_back(wrong, gold);
      }
      size_t evaluated = elig.size();
      unsigned int hw = std::thread::hardware_concurrency();
      unsigned int nprocs = std::max(1u, std::min(8u, hw > 1 ? hw / 2 : 1u));
      if (evaluated < 64) nprocs = 1;
      auto t0 = std::chrono::steady_clock::now();
      std::vector<int> read_fds;
      std::vector<pid_t> pids;
      for (unsigned int pid_idx = 0; pid_idx < nprocs; ++pid_idx) {
        int pfd[2];
        if (::pipe(pfd) != 0) std::exit(1);
        pid_t child = ::fork();
        if (child == 0) {
          ::close(pfd[0]);
          size_t l_det = 0, l_t1 = 0, l_t5 = 0;
          for (size_t idx = pid_idx; idx < evaluated; idx += nprocs) {
            const auto& [wrong, gold] = elig[idx];
            if (!hs.spell(wrong)) l_det++;
            auto sugs = hs.suggest(wrong);
            for (size_t k = 0; k < std::min<size_t>(5, sugs.size()); ++k) {
              if (sugs[k] == gold) {
                if (k == 0) l_t1++;
                l_t5++;
                break;
              }
            }
          }
          size_t stats[3] = {l_det, l_t1, l_t5};
          (void)::write(pfd[1], stats, sizeof(stats));
          ::close(pfd[1]);
          ::_exit(0);
        }
        ::close(pfd[1]);
        read_fds.push_back(pfd[0]);
        pids.push_back(child);
      }
      size_t detected = 0, top1 = 0, top5 = 0;
      for (size_t i = 0; i < pids.size(); ++i) {
        size_t stats[3] = {0, 0, 0};
        if (::read(read_fds[i], stats, sizeof(stats)) == sizeof(stats)) {
          detected += stats[0];
          top1 += stats[1];
          top5 += stats[2];
        }
        ::close(read_fds[i]);
        int st = 0;
        ::waitpid(pids[i], &st, 0);
      }
      auto t1 = std::chrono::steady_clock::now();
      double wall_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
      double cpu_ms_per_sug = evaluated == 0 ? 0.0 : (wall_ms * nprocs) / evaluated;
      std::cout << "  [SUGGESTIONS] " << suggest_path << " (n=" << evaluated
                << ", <=24B, workers=" << nprocs << "):\n"
                << "    Detected as misspelled: " << detected << " / " << evaluated
                << " (" << (evaluated ? 100.0 * detected / evaluated : 0.0) << "%)\n"
                << "    Top-1 accuracy:         " << top1 << " / " << evaluated
                << " (" << (evaluated ? 100.0 * top1 / evaluated : 0.0) << "%)\n"
                << "    Top-5 accuracy:         " << top5 << " / " << evaluated
                << " (" << (evaluated ? 100.0 * top5 / evaluated : 0.0) << "%)\n"
                << "    Wall time:              " << wall_ms << " ms (~" << cpu_ms_per_sug
                << " worker-ms/word)\n";
    }
    return 0;
  }

  std::cerr << "Unknown mode: " << mode << "\n";
  return 1;
}
