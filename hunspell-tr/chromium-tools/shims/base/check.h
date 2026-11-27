#pragma once
#include <cassert>
#include <cstdlib>
#include <iostream>

namespace base {
struct DummyLogStream {
  bool fatal = false;
  ~DummyLogStream() {
    if (fatal) {
      std::cerr << std::endl;
      std::abort();
    }
  }
  template <typename T>
  DummyLogStream& operator<<(const T& v) {
    if (fatal) std::cerr << v;
    return *this;
  }
};
}  // namespace base

#define CHECK(cond) \
  (::base::DummyLogStream{!(cond)})
#define DCHECK(cond) \
  (::base::DummyLogStream{false})
#define NOTREACHED() \
  (::base::DummyLogStream{true})
