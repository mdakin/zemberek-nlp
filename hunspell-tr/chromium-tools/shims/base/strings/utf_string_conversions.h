#pragma once
#include <string>
#include <string_view>
#include <unicode/unistr.h>

namespace base {
inline std::string UTF16ToUTF8(std::u16string_view utf16) {
  icu::UnicodeString ustr(false, utf16.data(), static_cast<int32_t>(utf16.size()));
  std::string out;
  ustr.toUTF8String(out);
  return out;
}
inline std::u16string UTF8ToUTF16(std::string_view utf8) {
  icu::UnicodeString ustr = icu::UnicodeString::fromUTF8(
      icu::StringPiece(utf8.data(), static_cast<int32_t>(utf8.size())));
  return std::u16string(ustr.getBuffer(), static_cast<size_t>(ustr.length()));
}
}  // namespace base
