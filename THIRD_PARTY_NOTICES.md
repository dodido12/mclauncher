# Software de terceros

| Componente | Licencia | Fuente |
|---|---|---|
| **Motor Zalith**: `zalith/src/main/jni` (libpojavexec, exithook, driver_helper, linkerhook, awt), clases `org.lwjgl.glfw.CallbackBridge`, `net.kdt.pojavlaunch.*`, `com.oracle.dalvik.VMLauncher` (reescritas con las mismas firmas) | GPLv3 | https://github.com/ZalithLauncher/ZalithLauncher (basado en PojavLauncher) |
| `lwjgl-glfw-classes.jar` (LWJGL 3.3.6 + GLFW en Java de Pojav) y natives `liblwjgl*.so` 3.3.6 para Android (`zalith/src/main/assets/zalith`) | BSD-3-Clause (LWJGL) / GPLv3 (partes de Pojav) | https://github.com/ZalithLauncher/ZalithLauncher · https://github.com/PojavLauncherTeam/lwjgl3 |
| `MioLibPatcher.jar` (agente Java; incluye Javassist) | GPLv3 / Javassist MPL-1.1, LGPL-2.1 o Apache-2.0 | https://github.com/ZalithLauncher/ZalithLauncher |
| `libOSMesa_8.so` (Mesa 23.0.4 OSMesa + Zink) | MIT | https://gitlab.freedesktop.org/mesa/mesa |
| ByteHook | MIT | https://github.com/bytedance/bhook |
| Mesa 3D (Zink) | MIT | https://gitlab.freedesktop.org/mesa/mesa |
| libdrm (estática, dentro de Mesa) | MIT | https://gitlab.freedesktop.org/mesa/drm |
| gl4es | MIT | https://github.com/ptitSeb/gl4es |
| LWJGL 3 | BSD-3-Clause | https://github.com/LWJGL/lwjgl3 |
| JNA `libjnidispatch.so` (compilado para Android, tomado de ZalithLauncher) | Apache-2.0 o LGPL-2.1 | https://github.com/java-native-access/jna |
| libffi | MIT | https://github.com/libffi/libffi |
| OpenAL Soft | LGPL-2.1-or-later | https://github.com/kcat/openal-soft |
| FreeType | FTL o GPLv2 | https://gitlab.freedesktop.org/freetype/freetype |
| GLFW (solo headers) | zlib | https://github.com/glfw/glfw |
| OpenJDK 25.0.3 para Android (incluido en `assets/runtimes/jre-25`, tomado de ZalithLauncher; compilación de PojavLauncherTeam) | GPLv2 + Classpath Exception (textos en `legal/` dentro del runtime) | https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch · https://github.com/ZalithLauncher/ZalithLauncher · código de OpenJDK: https://github.com/openjdk/jdk25u |
| OpenJDK 17 / 21 (descargado en el dispositivo desde Termux si no va en el APK) | GPLv2 + Classpath Exception | https://github.com/termux/termux-packages |

OpenAL Soft es LGPL: se distribuye como librería dinámica separada y su código fuente está en el enlace.
