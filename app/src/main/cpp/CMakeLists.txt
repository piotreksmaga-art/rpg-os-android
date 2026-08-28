cmake_minimum_required(VERSION 3.31.6)

project(rpgos_ai_runtime LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_RUN_TESTS OFF CACHE BOOL "" FORCE)
set(VK_USE_PLATFORM_ANDROID_KHR ON CACHE BOOL "" FORCE)

# FindVulkan does not search the Android NDK shader-tools directory by default.
find_program(RPGOS_NDK_GLSLC glslc
    HINTS "${ANDROID_NDK}/shader-tools"
    PATH_SUFFIXES windows-x86_64 linux-x86_64 darwin-x86_64 darwin-arm64
    NO_DEFAULT_PATH
)
if(RPGOS_NDK_GLSLC)
    set(Vulkan_GLSLC_EXECUTABLE "${RPGOS_NDK_GLSLC}" CACHE FILEPATH "Android NDK glslc" FORCE)
endif()
set(Vulkan_INCLUDE_DIR
    "${CMAKE_CURRENT_LIST_DIR}/third_party/Vulkan-Headers/include"
    CACHE PATH "Vendored Khronos Vulkan C and C++ headers" FORCE)
set(SPIRV-Headers_DIR "${CMAKE_CURRENT_LIST_DIR}/cmake" CACHE PATH "Vendored Khronos SPIR-V headers" FORCE)
# llama.cpp's Vulkan source includes SPIR-V headers directly; make the vendored
# include root visible to every backend target created by its subdirectory.
include_directories(SYSTEM "${CMAKE_CURRENT_LIST_DIR}/third_party/SPIRV-Headers/include")

# Vulkan shader generation runs on the build host while the runtime itself is cross-compiled for
# Android. Provide llama.cpp with a portable host toolchain. Windows needs an actual MinGW/MSVC
# host compiler; the clang executable in the Android NDK intentionally does not ship Windows SDK
# libraries and therefore cannot link the shader generator.
if(CMAKE_HOST_WIN32)
    unset(RPGOS_HOST_C CACHE)
    unset(RPGOS_HOST_CXX CACHE)
    find_program(RPGOS_HOST_C NAMES x86_64-w64-mingw32-clang.exe clang.exe gcc.exe
        NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    find_program(RPGOS_HOST_CXX NAMES x86_64-w64-mingw32-clang++.exe clang++.exe g++.exe
        NO_CMAKE_FIND_ROOT_PATH REQUIRED)
else()
    find_program(RPGOS_HOST_C NAMES cc gcc clang NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    find_program(RPGOS_HOST_CXX NAMES c++ g++ clang++ NO_CMAKE_FIND_ROOT_PATH REQUIRED)
endif()
set(RPGOS_HOST_TOOLCHAIN "${CMAKE_BINARY_DIR}/rpgos-vulkan-host-toolchain.cmake")
file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" RPGOS_HOST_NINJA)
file(WRITE "${RPGOS_HOST_TOOLCHAIN}"
    "set(CMAKE_BUILD_TYPE Release)\n"
    "set(CMAKE_C_COMPILER \"${RPGOS_HOST_C}\")\n"
    "set(CMAKE_CXX_COMPILER \"${RPGOS_HOST_CXX}\")\n"
    "set(CMAKE_MAKE_PROGRAM \"${RPGOS_HOST_NINJA}\" CACHE FILEPATH \"\" FORCE)\n"
)
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${RPGOS_HOST_TOOLCHAIN}" CACHE FILEPATH "Host Vulkan shader generator toolchain" FORCE)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_LIST_DIR}/third_party/llama.cpp)
add_subdirectory(${LLAMA_CPP_DIR} llama.cpp-build EXCLUDE_FROM_ALL)

add_library(rpgos_ai_runtime SHARED rpgos_llama_jni.cpp)
target_include_directories(rpgos_ai_runtime PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)
target_link_libraries(rpgos_ai_runtime PRIVATE llama android log)
