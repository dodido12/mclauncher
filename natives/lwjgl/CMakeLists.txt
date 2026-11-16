# Natives de LWJGL 3 para Android/bionic arm64 a partir del código C generado que publica
# el repositorio oficial (modules/*/src/generated/c). Replica config/linux/build.xml.
cmake_minimum_required(VERSION 3.22)
project(lwjgl_android C)

set(LWJGL_SRC "" CACHE PATH "Checkout de github.com/LWJGL/lwjgl3 (tag 3.3.x)")
set(LIBFFI_DIR "" CACHE PATH "Prefijo de libffi compilado para Android (lib/libffi.a)")
set(M ${LWJGL_SRC}/modules/lwjgl)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)
add_compile_options(-O3 -fPIC -pthread -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -Wno-everything)
add_compile_definitions(NDEBUG LWJGL_LINUX LWJGL_arm64 _GNU_SOURCE _FILE_OFFSET_BITS=64)
include_directories(${M}/core/src/main/c ${M}/core/src/main/c/linux)
set(VERSION_SCRIPT ${LWJGL_SRC}/config/linux/version.script)
set(LINK_FLAGS_COMMON "-Wl,--no-undefined -Wl,--version-script,${VERSION_SCRIPT} -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack")

# ---- liblwjgl.so (core) ----
file(GLOB CORE_SRC
    ${M}/core/src/main/c/*.c
    ${M}/core/src/generated/c/*.c
    ${M}/core/src/generated/c/linux/*.c)
# io_uring (LibURing/LibIOURing) choca con los headers del kernel del NDK y Minecraft no lo usa
list(FILTER CORE_SRC EXCLUDE REGEX ".*liburing.*")
add_library(lwjgl SHARED ${CORE_SRC})
target_include_directories(lwjgl PRIVATE
    ${M}/core/src/main/c/libffi ${M}/core/src/main/c/libffi/aarch64
    ${M}/core/src/main/c/linux/liburing ${M}/core/src/main/c/linux/liburing/include)
target_compile_definitions(lwjgl PRIVATE CONFIG_HAVE_MEMFD_CREATE)
target_link_libraries(lwjgl ${LIBFFI_DIR}/lib/libffi.a dl)
set_target_properties(lwjgl PROPERTIES LINK_FLAGS "${LINK_FLAGS_COMMON}")

# ---- liblwjgl_opengl.so ----
file(GLOB GL_SRC ${M}/opengl/src/generated/c/*.c)
list(FILTER GL_SRC EXCLUDE REGEX ".*org_lwjgl_opengl_WGL.c$")
add_library(lwjgl_opengl SHARED ${GL_SRC})
target_include_directories(lwjgl_opengl PRIVATE ${M}/opengl/src/main/c)
set_target_properties(lwjgl_opengl PROPERTIES LINK_FLAGS "${LINK_FLAGS_COMMON}")

# ---- liblwjgl_opengles.so (por si algún mod lo usa) ----
file(GLOB GLES_SRC ${M}/opengles/src/generated/c/*.c)
add_library(lwjgl_opengles SHARED ${GLES_SRC})
target_include_directories(lwjgl_opengles PRIVATE ${M}/opengles/src/main/c)
set_target_properties(lwjgl_opengles PROPERTIES LINK_FLAGS "${LINK_FLAGS_COMMON}")

# ---- liblwjgl_stb.so ----
file(GLOB STB_SRC ${M}/stb/src/generated/c/*.c)
add_library(lwjgl_stb SHARED ${STB_SRC})
target_include_directories(lwjgl_stb SYSTEM PRIVATE ${M}/stb/src/main/c)
target_link_libraries(lwjgl_stb m)
set_target_properties(lwjgl_stb PROPERTIES LINK_FLAGS "${LINK_FLAGS_COMMON}")

# ---- liblwjgl_tinyfd.so (diálogos: en Android devuelven "cancelado") ----
add_library(lwjgl_tinyfd SHARED ${M}/tinyfd/src/main/c/tinyfiledialogs.c ${M}/tinyfd/src/generated/c/org_lwjgl_util_tinyfd_TinyFileDialogs.c)
target_include_directories(lwjgl_tinyfd PRIVATE ${M}/tinyfd/src/main/c)
set_target_properties(lwjgl_tinyfd PROPERTIES LINK_FLAGS "${LINK_FLAGS_COMMON}")
