#pragma once
#include <string>
#include <string_view>
#include <vector>

namespace base {

enum TrimPositions {
  TRIM_NONE = 0,
  TRIM_LEADING = 1 << 0,
  TRIM_TRAILING = 1 << 1,
  TRIM_ALL = TRIM_LEADING | TRIM_TRAILING,
};

inline TrimPositions TrimWhitespaceASCII(std::string_view input,
                                         TrimPositions positions,
                                         std::string* output) {
  const char* ws = " \t\r\n\f\v";
  size_t first = (positions & TRIM_LEADING) ? input.find_first_not_of(ws) : 0;
  if (first == std::string_view::npos) {
    output->clear();
    return positions;
  }
  size_t last = (positions & TRIM_TRAILING) ? input.find_last_not_of(ws)
                                            : input.size() - 1;
  *output = std::string(input.substr(first, last - first + 1));
  return TRIM_NONE;
}

inline void ReplaceSubstringsAfterOffset(std::string* str,
                                         size_t start_offset,
                                         std::string_view find_this,
                                         std::string_view replace_with) {
  if (find_this.empty() || start_offset >= str->size()) return;
  size_t pos = start_offset;
  while ((pos = str->find(find_this, pos)) != std::string::npos) {
    str->replace(pos, find_this.size(), replace_with);
    pos += replace_with.size();
  }
}

inline std::string JoinString(const std::vector<std::string>& parts,
                              std::string_view sep) {
  if (parts.empty()) return std::string();
  std::string out = parts[0];
  for (size_t i = 1; i < parts.size(); ++i) {
    out.append(sep);
    out.append(parts[i]);
  }
  return out;
}

}  // namespace base
