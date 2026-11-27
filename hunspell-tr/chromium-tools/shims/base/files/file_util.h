#pragma once
#include <cstdio>
#include "base/files/file_path.h"

namespace base {
inline FILE* OpenFile(const FilePath& filename, const char* mode) {
  return std::fopen(filename.value().c_str(), mode);
}
inline bool CloseFile(FILE* file) {
  if (!file) return true;
  return std::fclose(file) == 0;
}
}  // namespace base
