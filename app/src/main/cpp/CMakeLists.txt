cmake_minimum_required(VERSION 3.22.1)
project(mcl_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Shaders GLSL → SPIR-V embebido (formato C) con el glslc que trae el NDK
# ---------------------------------------------------------------------------
file(GLOB _glslc_candidates "${ANDROID_NDK}/shader-tools/*/glslc" "${ANDROID_NDK}/shader-tools/*/glslc.exe")
find_program(GLSLC NAMES glslc HINTS ${_glslc_candidates} PATHS ENV PATH)
if(NOT GLSLC)
    list(GET _glslc_candidates 0 GLSLC)
endif()
if(NOT GLSLC)
    message(FATAL_ERROR "glslc no encontrado (NDK/shader-tools o Vulkan SDK)")
endif()

set(SHADER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shaders)
set(SHADER_OUT ${CMAKE_CURRENT_BINARY_DIR}/shaders)
file(MAKE_DIRECTORY ${SHADER_OUT})
set(SHADER_INCS "")
foreach(SRC fullscreen.vert present_hdr.frag)
    set(OUT ${SHADER_OUT}/${SRC}.inc)
    add_custom_command(
        OUTPUT ${OUT}
        COMMAND ${GLSLC} --target-env=vulkan1.1 -O -mfmt=c -o ${OUT} ${SHADER_DIR}/${SRC}
        DEPENDS ${SHADER_DIR}/${SRC}
        COMMENT "glslc ${SRC}")
    list(APPEND SHADER_INCS ${OUT})
endforeach()
add_custom_target(mcl_shaders DEPENDS ${SHADER_INCS})

# ---------------------------------------------------------------------------
# libmcl_native.so : JNI + lanzador HotSpot + "libGL" para LWJGL + presentador Vulkan
# ---------------------------------------------------------------------------
add_library(mcl_native SHARED
    jni_bridge.cpp
    jvm_launcher.cpp
    input_bridge.cpp
    gl_loader.cpp
    vk_hdr_presenter.cpp)

add_dependencies(mcl_native mcl_shaders)
target_include_directories(mcl_native PRIVATE include ${SHADER_OUT})
target_compile_options(mcl_native PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -fvisibility=hidden)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(egl-lib EGL)
find_library(nativewindow-lib nativewindow)
find_library(dl-lib dl)

target_link_libraries(mcl_native
    ${android-lib} ${log-lib} ${vulkan-lib} ${egl-lib} ${nativewindow-lib} ${dl-lib})

# Exportar solo la API C (JNI_*, Java_*, mcl_*, glX*) y mantener símbolos pequeños
target_link_options(mcl_native PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)

# ---------------------------------------------------------------------------
# libglfw.so : implementación de GLFW 3.4 sobre libmcl_native (la carga LWJGL)
# ---------------------------------------------------------------------------
add_library(glfw SHARED glfw/glfw_shim.c)
set_target_properties(glfw PROPERTIES C_STANDARD 11)
target_include_directories(glfw PRIVATE glfw/include)
target_compile_options(glfw PRIVATE -Wall -fvisibility=hidden)
target_link_libraries(glfw mcl_native ${log-lib})
target_link_options(glfw PRIVATE -Wl,-z,max-page-size=16384)

# ---------------------------------------------------------------------------
# Binarios de terceros (NO se compilan aquí). Copia los .so arm64-v8a a
# app/src/main/jniLibs/arm64-v8a/ :
#   libEGL_mesa.so (+ libglapi.so)   Mesa con -Dgallium-drivers=zink -Dplatforms= -Degl=enabled
#   libgl4es_114.so                  gl4es (MIT)
#   liblwjgl*.so, libglfw.so (shim)  LWJGL 3 compilado para Android/bionic + shim GLFW
#                                    que llama a mcl_poll_event / mcl_create_context /
#                                    mcl_swap_buffers (ver include/mcl_native.h)
# ---------------------------------------------------------------------------
