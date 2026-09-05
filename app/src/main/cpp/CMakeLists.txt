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
# Android. Provide llama.cpp with a portable host toolchain. On managed Windows workstations,
# Device Guard can reject unsigned MinGW-produced helpers. A pinned llvm-mingw sysroot lets the
# trusted NDK clang produce an equivalent self-contained Windows helper instead.
set(RPGOS_HOST_TOOLCHAIN_CONFIGURATION "")
if(CMAKE_HOST_WIN32)
    unset(RPGOS_HOST_C CACHE)
    unset(RPGOS_HOST_CXX CACHE)
    set(RPGOS_PINNED_MINGW_ROOT
        "${CMAKE_CURRENT_LIST_DIR}/../../../../.toolchains/llvm-mingw-20251216-ucrt-x86_64")
    if(EXISTS "${RPGOS_PINNED_MINGW_ROOT}/include/c++/v1" AND ANDROID_NDK)
        set(RPGOS_HOST_C "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe")
        set(RPGOS_HOST_CXX "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe")
        set(RPGOS_NDK_CLANG_RESOURCE "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/lib/clang/21")
        set(RPGOS_HOST_RESOURCE "${CMAKE_BINARY_DIR}/rpgos-ndk-host-resource")
        file(MAKE_DIRECTORY "${RPGOS_HOST_RESOURCE}/lib/x86_64-w64-windows-gnu")
        file(COPY_FILE
            "${RPGOS_PINNED_MINGW_ROOT}/lib/clang/21/lib/windows/libclang_rt.builtins-x86_64.a"
            "${RPGOS_HOST_RESOURCE}/lib/x86_64-w64-windows-gnu/libclang_rt.builtins.a"
            ONLY_IF_DIFFERENT)
        file(TO_CMAKE_PATH "${RPGOS_PINNED_MINGW_ROOT}" RPGOS_PINNED_MINGW_ROOT)
        file(TO_CMAKE_PATH "${RPGOS_NDK_CLANG_RESOURCE}" RPGOS_NDK_CLANG_RESOURCE)
        file(TO_CMAKE_PATH "${RPGOS_HOST_RESOURCE}" RPGOS_HOST_RESOURCE)
        set(RPGOS_HOST_COMMON_FLAGS
            "--target=x86_64-w64-windows-gnu -resource-dir=${RPGOS_HOST_RESOURCE} -isystem ${RPGOS_NDK_CLANG_RESOURCE}/include -isystem ${RPGOS_PINNED_MINGW_ROOT}/include -L${RPGOS_PINNED_MINGW_ROOT}/x86_64-w64-mingw32/lib -rtlib=compiler-rt -static")
        set(RPGOS_HOST_CXX_FLAGS
            "--target=x86_64-w64-windows-gnu -resource-dir=${RPGOS_HOST_RESOURCE} -isystem ${RPGOS_PINNED_MINGW_ROOT}/include/c++/v1 -isystem ${RPGOS_NDK_CLANG_RESOURCE}/include -isystem ${RPGOS_PINNED_MINGW_ROOT}/include -L${RPGOS_PINNED_MINGW_ROOT}/x86_64-w64-mingw32/lib -rtlib=compiler-rt -unwindlib=libunwind -stdlib=libc++ -pthread -static")
        set(RPGOS_HOST_TOOLCHAIN_CONFIGURATION
            "set(CMAKE_C_FLAGS \"${RPGOS_HOST_COMMON_FLAGS}\")\nset(CMAKE_CXX_FLAGS \"${RPGOS_HOST_CXX_FLAGS}\")\nset(CMAKE_C_FLAGS_RELEASE \"-O2 -DNDEBUG\")\nset(CMAKE_CXX_FLAGS_RELEASE \"-O2 -DNDEBUG\")\nset(CMAKE_EXE_LINKER_FLAGS \"${RPGOS_HOST_CXX_FLAGS}\")\n")
    else()
        set(RPGOS_HOST_COMPILER_HINTS "")
        if(DEFINED ENV{RPGOS_HOST_TOOLCHAIN_ROOT})
            list(APPEND RPGOS_HOST_COMPILER_HINTS "$ENV{RPGOS_HOST_TOOLCHAIN_ROOT}/bin")
        endif()
        find_program(RPGOS_HOST_C NAMES x86_64-w64-mingw32-clang.exe clang.exe gcc.exe
            HINTS ${RPGOS_HOST_COMPILER_HINTS}
            NO_CMAKE_FIND_ROOT_PATH REQUIRED)
        find_program(RPGOS_HOST_CXX NAMES x86_64-w64-mingw32-clang++.exe clang++.exe g++.exe
            HINTS ${RPGOS_HOST_COMPILER_HINTS}
            NO_CMAKE_FIND_ROOT_PATH REQUIRED)
        file(TO_CMAKE_PATH "${ANDROID_NDK}" RPGOS_ANDROID_NDK_NORMALIZED)
        file(TO_CMAKE_PATH "${RPGOS_HOST_CXX}" RPGOS_HOST_CXX_NORMALIZED)
        string(FIND "${RPGOS_HOST_CXX_NORMALIZED}" "${RPGOS_ANDROID_NDK_NORMALIZED}/" RPGOS_NDK_HOST_COMPILER_OFFSET)
        if(RPGOS_NDK_HOST_COMPILER_OFFSET EQUAL 0)
            message(FATAL_ERROR
                "The Android NDK compiler needs the pinned llvm-mingw sysroot to link the Windows "
                "Vulkan shader generator. Install llvm-mingw 20251216 under .toolchains or set "
                "RPGOS_HOST_TOOLCHAIN_ROOT to a complete Windows host compiler.")
        endif()
    endif()
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
    "${RPGOS_HOST_TOOLCHAIN_CONFIGURATION}"
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
