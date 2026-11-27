cmake_minimum_required(VERSION 3.16)
project(chromium_hunspell_tools LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_BUILD_TYPE Release)
add_compile_options(-O3 -Wno-deprecated-declarations -Wno-unused-result)

find_package(PkgConfig REQUIRED)
pkg_check_modules(ICU REQUIRED icu-uc icu-i18n)

set(UPSTREAM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/upstream")
set(SHIMS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/shims")

set(HUNSPELL_SRCS
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/affentry.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/affixmgr.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/csutil.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/filemgr.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/hashmgr.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/hunspell.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/hunspelltrace.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/hunzip.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/phonet.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/replist.cxx"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell/suggestmgr.cxx"
)

set(BDICT_SRCS
  "${UPSTREAM_DIR}/third_party/hunspell/google/bdict.cc"
  "${UPSTREAM_DIR}/third_party/hunspell/google/bdict_reader.cc"
  "${UPSTREAM_DIR}/third_party/hunspell/google/bdict_writer.cc"
)

# 1. Exact Chromium convert_dict binary
add_executable(convert_dict
  "${UPSTREAM_DIR}/chrome/tools/convert_dict/convert_dict.cc"
  "${UPSTREAM_DIR}/chrome/tools/convert_dict/aff_reader.cc"
  "${UPSTREAM_DIR}/chrome/tools/convert_dict/dic_reader.cc"
  "${UPSTREAM_DIR}/chrome/tools/convert_dict/hunspell_reader.cc"
  ${BDICT_SRCS}
)
target_include_directories(convert_dict PRIVATE "${SHIMS_DIR}" "${UPSTREAM_DIR}" ${ICU_INCLUDE_DIRS})
target_link_libraries(convert_dict PRIVATE ${ICU_LIBRARIES})

# 2. Exact Chromium HUNSPELL_CHROME_CLIENT BDict inspector & benchmark binary
add_executable(bdict_tool
  "${CMAKE_CURRENT_SOURCE_DIR}/bdict_tool.cc"
  ${BDICT_SRCS}
  ${HUNSPELL_SRCS}
)
target_compile_definitions(bdict_tool PRIVATE HUNSPELL_CHROME_CLIENT HUNSPELL_STATIC OPENOFFICEORG MAXSUGGESTION=5)
target_include_directories(bdict_tool PRIVATE
  "${SHIMS_DIR}"
  "${UPSTREAM_DIR}"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell"
  ${ICU_INCLUDE_DIRS}
)
target_link_libraries(bdict_tool PRIVATE ${ICU_LIBRARIES})

# 3. Upstream Hunspell (.aff + .dic) parity binary (without HUNSPELL_CHROME_CLIENT)
add_executable(upstream_hunspell_tool
  "${CMAKE_CURRENT_SOURCE_DIR}/upstream_hunspell_tool.cc"
  ${HUNSPELL_SRCS}
)
target_compile_definitions(upstream_hunspell_tool PRIVATE HUNSPELL_STATIC)
target_include_directories(upstream_hunspell_tool PRIVATE
  "${SHIMS_DIR}"
  "${UPSTREAM_DIR}"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell"
)

# 4. Large-scale Zemberek Java Solved/Unsolved vs Chrome BDict cross-evaluator
add_executable(compare_zemberek_lists
  "${CMAKE_CURRENT_SOURCE_DIR}/compare_zemberek_lists.cc"
  ${BDICT_SRCS}
  ${HUNSPELL_SRCS}
)
target_compile_definitions(compare_zemberek_lists PRIVATE HUNSPELL_CHROME_CLIENT HUNSPELL_STATIC OPENOFFICEORG MAXSUGGESTION=5)
target_include_directories(compare_zemberek_lists PRIVATE
  "${SHIMS_DIR}"
  "${UPSTREAM_DIR}"
  "${UPSTREAM_DIR}/third_party/hunspell/src/hunspell"
  ${ICU_INCLUDE_DIRS}
)
target_link_libraries(compare_zemberek_lists PRIVATE ${ICU_LIBRARIES})
