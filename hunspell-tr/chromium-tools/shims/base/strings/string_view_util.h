#pragma once
#include <cstdint>
#include <string_view>
#include "base/containers/span.h"

namespace base {
inline std::string_view as_string_view(span<const uint8_t> s) noexcept {
  return std::string_view(reinterpret_cast<const char*>(s.data()), s.size());
}
}  // namespace base
