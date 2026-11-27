#pragma once
#include <string>
#include <string_view>
#include <vector>
#include "base/strings/string_util.h"

namespace base {

enum WhitespaceHandling {
  KEEP_WHITESPACE,
  TRIM_WHITESPACE,
};

enum SplitResult {
  SPLIT_WANT_ALL,
  SPLIT_WANT_NONEMPTY,
};

inline std::vector<std::string> SplitString(std::string_view input,
                                            std::string_view separators,
                                            WhitespaceHandling whitespace,
                                            SplitResult result_type) {
  std::vector<std::string> result;
  if (input.empty()) return result;
  size_t start = 0;
  while (start <= input.size()) {
    size_t end = input.find_first_of(separators, start);
    std::string_view piece = (end == std::string_view::npos)
                                 ? input.substr(start)
                                 : input.substr(start, end - start);
    std::string token(piece);
    if (whitespace == TRIM_WHITESPACE) {
      TrimWhitespaceASCII(token, TRIM_ALL, &token);
    }
    if (result_type == SPLIT_WANT_ALL || !token.empty()) {
      result.push_back(std::move(token));
    }
    if (end == std::string_view::npos) break;
    start = end + 1;
  }
  return result;
}

}  // namespace base
