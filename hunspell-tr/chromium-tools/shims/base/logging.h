#pragma once
#include <cstdlib>
#include <iostream>

namespace logging {
struct LogMessage {
  bool fatal;
  explicit LogMessage(const char* level, bool is_fatal) : fatal(is_fatal) {
    std::cerr << "[" << level << "] ";
  }
  ~LogMessage() {
    std::cerr << std::endl;
    if (fatal) std::abort();
  }
  template <typename T>
  LogMessage& operator<<(const T& v) {
    std::cerr << v;
    return *this;
  }
};
}  // namespace logging

#define LOG_INFO ::logging::LogMessage("INFO", false)
#define LOG_WARNING ::logging::LogMessage("WARNING", false)
#define LOG_ERROR ::logging::LogMessage("ERROR", false)
#define LOG_FATAL ::logging::LogMessage("FATAL", true)
#define LOG(severity) LOG_##severity
