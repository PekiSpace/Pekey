# Hlasové zadávání Pekey: whisper.cpp (MIT) + vlastní JNI.
# Zdroj whisper.cpp stáhne build na GitHubu do složky whisper.cpp (viz .github/workflows/build.yml).
cmake_minimum_required(VERSION 3.22)
project(pekeyvoice C CXX)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp whisper EXCLUDE_FROM_ALL)

add_library(pekeyvoice SHARED voice_jni.cpp)
target_link_libraries(pekeyvoice whisper log)
target_compile_options(pekeyvoice PRIVATE -O3)
