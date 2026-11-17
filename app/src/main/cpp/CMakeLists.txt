# Nativní knihovny Pekey:
#  - jni_latinime: otevřený AOSP LatinIME (Apache-2.0) pro návrhy, opravy a předpověď slov
#  - pekeyvoice: hlasové zadávání přes whisper.cpp (MIT), jen když je stažený zdroj whisper.cpp
cmake_minimum_required(VERSION 3.22)
project(pekeynative C CXX)

set(CMAKE_CXX_STANDARD 17)

# ---- LatinIME ----
file(GLOB_RECURSE LATINIME_CORE ${CMAKE_CURRENT_SOURCE_DIR}/latinime/src/*.cpp)
file(GLOB LATINIME_JNI ${CMAKE_CURRENT_SOURCE_DIR}/latinime/*.cpp)
add_library(jni_latinime SHARED ${LATINIME_JNI} ${LATINIME_CORE})
target_include_directories(jni_latinime PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/latinime
    ${CMAKE_CURRENT_SOURCE_DIR}/latinime/src)
target_compile_options(jni_latinime PRIVATE
    -Wno-unused-parameter -Wno-unused-function
    -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections -O2)
target_link_libraries(jni_latinime log)
target_link_options(jni_latinime PRIVATE -Wl,--gc-sections)

# ---- hlas (whisper.cpp) ----
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/CMakeLists.txt)
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
endif()
