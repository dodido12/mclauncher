#!/usr/bin/env bash
# Compila desde el código fuente todas las librerías nativas de terceros que usa el launcher
# y las copia a app/src/main/jniLibs/arm64-v8a/.
#
# Requisitos (host Linux x86_64): Android NDK r28, CMake ≥3.22, Ninja, Meson ≥1.4, Python 3
# con mako/pyyaml/packaging, pkg-config, bison, flex, git, curl, patchelf.
#   pip install meson mako pyyaml packaging patchelf
#   apt install bison flex pkg-config ninja-build
#
# Uso: ANDROID_NDK=/ruta/al/ndk ./natives/build-natives.sh
set -euo pipefail

NDK="${ANDROID_NDK:?Define ANDROID_NDK (p.ej. ~/Android/Sdk/ndk/28.2.13676358)}"
API=29
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
WORK="${WORK:-$HERE/.work}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
TC="$NDK/build/cmake/android.toolchain.cmake"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CMAKE="${CMAKE:-cmake}"
JOBS="${JOBS:-$(nproc)}"

LWJGL_TAG=3.3.3
LIBFFI_VER=3.4.6
OPENAL_TAG=1.23.1
FREETYPE_TAG=VER-2-13-2
LIBDRM_TAG=libdrm-2.4.124
MESA_TAG=mesa-25.3.6

mkdir -p "$WORK" "$OUT"
cd "$WORK"
CM_ANDROID=(-G Ninja -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API
            -DCMAKE_BUILD_TYPE=Release "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")

clone() { [ -d "$2" ] || git clone -q --depth 1 --branch "$3" "$1" "$2"; }

echo "==> libffi $LIBFFI_VER (estática, para liblwjgl)"
if [ ! -f out/libffi/lib/libffi.a ]; then
  [ -d libffi-$LIBFFI_VER ] || curl -sSL "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VER/libffi-$LIBFFI_VER.tar.gz" | tar xz
  ( cd libffi-$LIBFFI_VER && CC="$BIN/aarch64-linux-android$API-clang" AR="$BIN/llvm-ar" RANLIB="$BIN/llvm-ranlib" \
      ./configure -q --host=aarch64-linux-android --prefix="$WORK/out/libffi" --disable-shared --enable-static \
      --with-pic --disable-docs --disable-multi-os-directory && make -s -j"$JOBS" && make -s install )
fi

echo "==> LWJGL $LWJGL_TAG (liblwjgl, opengl, opengles, stb, tinyfd)"
clone https://github.com/LWJGL/lwjgl3.git lwjgl3 "$LWJGL_TAG"
"$CMAKE" -S "$HERE/lwjgl" -B b-lwjgl "${CM_ANDROID[@]}" -DLWJGL_SRC="$WORK/lwjgl3" -DLIBFFI_DIR="$WORK/out/libffi" >/dev/null
"$CMAKE" --build b-lwjgl -j"$JOBS"
cp b-lwjgl/liblwjgl*.so "$OUT/"

echo "==> FreeType $FREETYPE_TAG"
clone https://gitlab.freedesktop.org/freetype/freetype.git freetype "$FREETYPE_TAG"
"$CMAKE" -S freetype -B b-ft "${CM_ANDROID[@]}" -DBUILD_SHARED_LIBS=ON -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON \
  -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON >/dev/null
"$CMAKE" --build b-ft -j"$JOBS"
cp b-ft/libfreetype.so "$OUT/"

echo "==> OpenAL Soft $OPENAL_TAG (backend OpenSL ES)"
clone https://github.com/kcat/openal-soft.git openal-soft "$OPENAL_TAG"
"$CMAKE" -S openal-soft -B b-al "${CM_ANDROID[@]}" -DANDROID_STL=c++_static -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF \
  -DALSOFT_TESTS=OFF -DALSOFT_INSTALL=OFF -DALSOFT_BACKEND_OPENSL=ON -DALSOFT_REQUIRE_OPENSL=ON -DALSOFT_EMBED_HRTF_DATA=ON >/dev/null
"$CMAKE" --build b-al -j"$JOBS"
cp b-al/libopenal.so "$OUT/"

echo "==> gl4es (GL 2.1 → GLES)"
clone https://github.com/ptitSeb/gl4es.git gl4es master
"$CMAKE" -S gl4es -B b-gl4es "${CM_ANDROID[@]}" -DANDROID=ON -DNOX11=ON -DUSE_ANDROID_LOG=ON -DGLX_STUBS=ON -DDEFAULT_ES=2 >/dev/null
"$CMAKE" --build b-gl4es -j"$JOBS"
cp gl4es/lib/libGL.so.1 "$OUT/libgl4es_114.so"
patchelf --set-soname libgl4es_114.so "$OUT/libgl4es_114.so"

echo "==> libdrm $LIBDRM_TAG (estática, la exige Mesa)"
clone https://gitlab.freedesktop.org/mesa/drm.git libdrm "$LIBDRM_TAG"
CROSS="$WORK/android-aarch64.ini"
sed "s#^NDK_BIN = .*#NDK_BIN = '$BIN'#; s#/home/claude/mclauncher/natives/mesa/stubs#$HERE/mesa/stubs#g" "$HERE/mesa/android-aarch64.ini" > "$CROSS"
if [ ! -f out/drm/lib/libdrm.a ]; then
  meson setup b-drm libdrm --cross-file "$CROSS" --buildtype=release --prefix="$WORK/out/drm" --default-library=static \
    -Dintel=disabled -Dradeon=disabled -Damdgpu=disabled -Dnouveau=disabled -Dvmwgfx=disabled -Domap=disabled \
    -Dexynos=disabled -Dfreedreno=disabled -Dtegra=disabled -Dvc4=disabled -Detnaviv=disabled -Dcairo-tests=disabled \
    -Dman-pages=disabled -Dvalgrind=disabled -Dtests=false -Dinstall-test-programs=false >/dev/null
  ninja -C b-drm install >/dev/null
fi

echo "==> Mesa $MESA_TAG (Zink + EGL surfaceless) — tarda bastante"
clone https://gitlab.freedesktop.org/mesa/mesa.git mesa "$MESA_TAG"
export PKG_CONFIG_LIBDIR="$WORK/out/drm/lib/pkgconfig"
[ -d b-mesa ] || meson setup b-mesa mesa --cross-file "$CROSS" --buildtype=release \
  -Dplatforms= -Degl=enabled -Dgbm=disabled -Dglx=disabled -Dopengl=true -Dgles1=disabled -Dgles2=enabled \
  -Dgallium-drivers=zink -Dvulkan-drivers= -Dllvm=disabled -Dxmlconfig=disabled -Dzstd=disabled -Dexpat=disabled \
  -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled -Dandroid-libbacktrace=disabled -Dglvnd=disabled
ninja -C b-mesa -j"$JOBS"
# libEGL depende (DT_NEEDED) de libgallium-<ver>.so, que lleva Zink y la glapi
cp b-mesa/src/egl/libEGL.so.1.0.0 "$OUT/libEGL_mesa.so"
patchelf --set-soname libEGL_mesa.so --remove-rpath "$OUT/libEGL_mesa.so"
cp b-mesa/src/gallium/targets/dri/libgallium-*.so "$OUT/"

"$BIN/llvm-strip" --strip-unneeded "$OUT"/*.so
echo "Listo. Librerías en $OUT:"
ls -la "$OUT"
