# NDK_TOOLCHAIN_VERSION := 4.9
APP_PLATFORM := android-21
APP_STL := system
# APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
# NDK r28 (clang 19) convierte en error varias advertencias que el código original
# compilaba con NDK r25; se dejan como advertencias.
APP_CFLAGS += -Wno-error=int-conversion -Wno-error=incompatible-function-pointer-types \
              -Wno-error=implicit-function-declaration -Wno-error=incompatible-pointer-types \
              -Wno-error=implicit-int -Wno-error=return-type
APP_LDFLAGS += -Wl,-z,max-page-size=16384
