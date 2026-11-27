// Upstream Hunspell (.aff + .dic) parity checker using the exact same Hunspell 1.7.5 sources
// compiled WITHOUT HUNSPELL_CHROME_CLIENT (matching Firefox / LibreOffice / macOS NSSpellChecker).
#include <algorithm>
#include <iostream>
#include <string>
#include <vector>

#include "third_party/hunspell/src/hunspell/hunspell.hxx"

int main(int argc, char** argv) {
  if (argc < 4) {
    std::cerr << "Usage: upstream_hunspell_tool <file.aff> <file.dic> <word1> [word2 ...]\n";
    return 1;
  }
  Hunspell hs(argv[1], argv[2]);
  for (int i = 3; i < argc; ++i) {
    std::string w = argv[i];
    bool ok = hs.spell(w);
    std::cout << w << " -> " << (ok ? "VALID" : "MISSPELLED");
    if (!ok) {
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
