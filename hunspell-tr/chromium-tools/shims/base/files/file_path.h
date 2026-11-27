#pragma once
#include <string>
#include "base/compiler_specific.h"

namespace base {
class FilePath {
 public:
  FilePath() = default;
  explicit FilePath(const std::string& p) : path_(p) {}
  explicit FilePath(const char* p) : path_(p ? p : "") {}

  const std::string& value() const { return path_; }

  FilePath ReplaceExtension(const std::string& ext) const {
    std::string norm_ext = ext;
    if (!norm_ext.empty() && norm_ext[0] != '.') {
      norm_ext = "." + norm_ext;
    }
    size_t slash = path_.find_last_of("/\\");
    size_t dot = path_.find_last_of('.');
    if (dot != std::string::npos && (slash == std::string::npos || dot > slash)) {
      return FilePath(path_.substr(0, dot) + norm_ext);
    }
    return FilePath(path_ + norm_ext);
  }

 private:
  std::string path_;
};
}  // namespace base
