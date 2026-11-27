#pragma once

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>

namespace base {

template <typename T, std::size_t Extent = std::dynamic_extent>
class span : public std::span<T, Extent> {
 public:
  using Base = std::span<T, Extent>;
  using Base::Base;

  constexpr span() noexcept : Base() {}
  constexpr span(const Base& other) noexcept : Base(other) {}

  template <typename Container,
            typename = std::enable_if_t<
                !std::is_same_v<std::remove_cvref_t<Container>, span> &&
                std::is_convertible_v<decltype(std::data(std::declval<Container&>())), T*>>>
  constexpr span(Container&& c) noexcept
      : Base(std::data(c), std::size(c)) {}

  constexpr span<T> subspan(std::size_t offset,
                            std::size_t count = std::dynamic_extent) const {
    return span<T>(Base::subspan(offset, count));
  }

  template <std::size_t Offset, std::size_t Count = std::dynamic_extent>
  constexpr auto subspan() const {
    auto s = Base::template subspan<Offset, Count>();
    return span<T, decltype(s)::extent>(s);
  }

  template <std::size_t Count>
  constexpr span<T, Count> first() const {
    return span<T, Count>(Base::template first<Count>());
  }

  constexpr span<T> first(std::size_t count) const {
    return span<T>(Base::first(count));
  }

  template <std::size_t Offset>
  constexpr std::pair<span<T, Offset>, span<T>> split_at() const {
    return {first<Offset>(), subspan(Offset)};
  }

  template <typename OtherT, std::size_t OtherExtent>
  constexpr void copy_from(span<OtherT, OtherExtent> other) {
    assert(this->size_bytes() == other.size_bytes());
    std::memcpy(this->data(), other.data(), this->size_bytes());
  }
};

template <typename Container>
constexpr span<const uint8_t> as_byte_span(const Container& c) noexcept {
  return span<const uint8_t>(
      reinterpret_cast<const uint8_t*>(std::data(c)),
      std::size(c) * sizeof(typename std::remove_reference_t<Container>::value_type));
}

template <typename T>
constexpr span<uint8_t, sizeof(T)> byte_span_from_ref(T& value) noexcept {
  return span<uint8_t, sizeof(T)>(reinterpret_cast<uint8_t*>(&value), sizeof(T));
}

}  // namespace base
