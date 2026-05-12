cmake_minimum_required(VERSION 3.22.1)
project(VulkanRenderer)

# adrenotools explicitly rejects non-arm64 ABIs with a FATAL_ERROR in its CMakeLists.
# The Vulkan compositor itself also targets arm64 devices only.
# For armeabi-v7a builds, this file is a no-op.
if(NOT ${CMAKE_ANDROID_ARCH_ABI} STREQUAL "arm64-v8a")
    message(STATUS "vulkan_renderer: skipping non-arm64 ABI (${CMAKE_ANDROID_ARCH_ABI})")
    return()
endif()

set(RENDERER_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../winlator)
set(ADRENOTOOLS_DIR  ${CMAKE_CURRENT_SOURCE_DIR}/../extras/adrenotools)

# adrenotools is needed for Phase B (Turnip driver injection on Adreno).
# In Phase A it is linked but its entry-point (adrenotools_open_libvulkan) is only
# called when a custom Turnip driver path is configured. On Mali and other non-Adreno
# GPUs the standard system Vulkan driver is used instead.
add_subdirectory(${ADRENOTOOLS_DIR} ${CMAKE_CURRENT_BINARY_DIR}/adrenotools_build)

# SPIR-V shader headers are pre-compiled and checked into the source tree alongside
# their .vert / .frag sources. glslangValidator is NOT required at build time.
# To regenerate after shader changes, run from the repo root:
#
#   GLSLANG="$ANDROID_SDK_ROOT/emulator/lib64/vulkan/glslangValidator"
#   SRC=app/src/main/cpp/winlator
#   $GLSLANG -V --vn window_vert_code -x -o $SRC/window_vert.h $SRC/window.vert
#   $GLSLANG -V --vn window_frag_code -x -o $SRC/window_frag.h $SRC/window.frag

add_library(vulkan_renderer SHARED
    ${RENDERER_SRC_DIR}/vulkan_jni.cpp
    ${RENDERER_SRC_DIR}/VulkanRendererContext.cpp
)

target_include_directories(vulkan_renderer PRIVATE
    # Finds VulkanRendererContext.h, window_vert.h, window_frag.h
    ${RENDERER_SRC_DIR}
)

target_compile_options(vulkan_renderer PRIVATE
    -std=c++17
    -fvisibility=hidden
    -Wall
    -Wextra
)

target_link_libraries(vulkan_renderer
    log
    android
    vulkan
    adrenotools
    dl
    atomic
)
