#pragma once
#include <cstdarg>
#include <cstdio>
#include <string>
#include <vector>

namespace base {
inline std::string StringPrintf(const char* format, ...) {
  va_list ap;
  va_start(ap, format);
  va_list ap_copy;
  va_copy(ap_copy, ap);
  int len = std::vsnprintf(nullptr, 0, format, ap);
  va_end(ap);
  if (len <= 0) {
    va_end(ap_copy);
    return std::string();
  }
  std::vector<char> buf(static_cast<size_t>(len) + 1);
  std::vsnprintf(buf.data(), buf.size(), format, ap_copy);
  va_end(ap_copy);
  return std::string(buf.data(), static_cast<size_t>(len));
}
}  // namespace base
