#pragma once
#include <cstdint>
#include <cstdlib>
#include <limits>

namespace base {
template <typename T>
class CheckedNumeric {
 public:
  explicit CheckedNumeric(T v) : val_(v), valid_(true) {}
  CheckedNumeric& operator+=(T rhs) {
    if (valid_) {
      if (std::numeric_limits<T>::max() - val_ < rhs) {
        valid_ = false;
      } else {
        val_ += rhs;
      }
    }
    return *this;
  }
  bool IsValid() const { return valid_; }
  T ValueOrDie() const {
    if (!valid_) std::abort();
    return val_;
  }

 private:
  T val_;
  bool valid_;
};
}  // namespace base
