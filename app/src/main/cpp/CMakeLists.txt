cmake_minimum_required(VERSION 3.22.1)
project(rpf_viewport LANGUAGES CXX)
find_program(PYTHON python3 REQUIRED)
set(GLSLC "${ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
set(GENERATED "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GENERATED}")
add_custom_command(OUTPUT "${GENERATED}/shaders.h"
    COMMAND "${GLSLC}" "${CMAKE_CURRENT_SOURCE_DIR}/shaders/scene.vert" -o "${GENERATED}/scene.vert.spv"
    COMMAND "${GLSLC}" "${CMAKE_CURRENT_SOURCE_DIR}/shaders/scene.frag" -o "${GENERATED}/scene.frag.spv"
    COMMAND "${PYTHON}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_shaders.py" "${GENERATED}"
    DEPENDS shaders/scene.vert shaders/scene.frag embed_shaders.py VERBATIM)
add_library(rpf_viewport SHARED viewport.cpp "${GENERATED}/shaders.h")
target_include_directories(rpf_viewport PRIVATE "${GENERATED}")
target_compile_definitions(rpf_viewport PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_compile_options(rpf_viewport PRIVATE -Wall -Wextra -Wno-missing-field-initializers -Werror=return-type)
target_link_options(rpf_viewport PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(rpf_viewport android log vulkan)
