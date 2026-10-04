cmake_minimum_required(VERSION 3.22)
project(native_sender CXX)

# The patched airplay2-sender-cpp tree, vendored at the repository root as
# airplay2-sender-cpp/. Override with -DRAOP_SRC=/path/to/tree if it lives
# elsewhere. The path is relative to this file so a fresh clone works with no
# setup step at all.
set(RAOP_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../airplay2-sender-cpp"
    CACHE PATH "airplay2-sender-cpp tree")
if(NOT EXISTS "${RAOP_SRC}/CMakeLists.txt")
    message(FATAL_ERROR
        "airplay2-sender-cpp not found at ${RAOP_SRC}.\n"
        "If this is a git clone, fetch the submodule/dependency sources first:\n"
        "    git submodule update --init --recursive")
endif()

add_subdirectory(${RAOP_SRC} ${CMAKE_CURRENT_BINARY_DIR}/raop_build)

add_library(native_sender SHARED native_sender.cpp)
target_link_libraries(native_sender PRIVATE raop_sender android log)

# 16 KB page-size support (required for Android 15+ devices).
target_link_options(native_sender PRIVATE "-Wl,-z,max-page-size=16384")

# Reproducible builds (F-Droid): no embedded absolute build paths, no build-id
# (which otherwise varies per build machine/time), and a deterministic .so.
target_compile_options(native_sender PRIVATE
    "-ffile-prefix-map=${RAOP_SRC}=airplay2-sender-cpp"
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=."
    "-ffile-prefix-map=${CMAKE_CURRENT_BINARY_DIR}=.")
target_link_options(native_sender PRIVATE
    "-Wl,--build-id=none"
    "-Wl,--sort-common"
    "-Wl,--no-rosegment")
set_property(TARGET native_sender APPEND PROPERTY
    LINK_DEPENDS "${RAOP_SRC}/src/raop_sender.cpp")