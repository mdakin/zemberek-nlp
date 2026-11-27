#pragma once
#include <cstdint>
#include "base/containers/span.h"

namespace base {
inline constexpr uint16_t U16FromLittleEndian(span<const uint8_t, 2u> bytes) {
  return static_cast<uint16_t>(bytes[0]) |
         (static_cast<uint16_t>(bytes[1]) << 8);
}
inline constexpr uint32_t U32FromLittleEndian(span<const uint8_t, 4u> bytes) {
  return static_cast<uint32_t>(bytes[0]) |
         (static_cast<uint32_t>(bytes[1]) << 8) |
         (static_cast<uint32_t>(bytes[2]) << 16) |
         (static_cast<uint32_t>(bytes[3]) << 24);
}
}  // namespace base
