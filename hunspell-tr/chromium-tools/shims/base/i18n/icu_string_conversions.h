#pragma once
#include <string>
#include <string_view>
#include <vector>
#include <unicode/ucnv.h>

namespace base {

class OnStringConversionError {
 public:
  enum Type {
    FAIL,
    SKIP,
    SUBSTITUTE,
  };
};

inline bool CodepageToUTF16(std::string_view encoded,
                            const char* charset_name,
                            OnStringConversionError::Type on_error,
                            std::u16string* wide) {
  UErrorCode status = U_ZERO_ERROR;
  UConverter* conv = ucnv_open(charset_name, &status);
  if (U_FAILURE(status) || !conv) {
    return false;
  }
  if (on_error == OnStringConversionError::FAIL) {
    UErrorCode cb_status = U_ZERO_ERROR;
    ucnv_setToUCallBack(conv, UCNV_TO_U_CALLBACK_STOP, nullptr, nullptr, nullptr, &cb_status);
  }
  int32_t cap = static_cast<int32_t>(encoded.size()) * 2 + 4;
  std::vector<UChar> buf(static_cast<size_t>(cap));
  int32_t len = ucnv_toUChars(conv, buf.data(), cap, encoded.data(),
                              static_cast<int32_t>(encoded.size()), &status);
  ucnv_close(conv);
  if (U_FAILURE(status)) {
    return false;
  }
  wide->assign(buf.data(), static_cast<size_t>(len));
  return true;
}

}  // namespace base
