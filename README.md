# MC Launcher (Android · Kotlin + C++/NDK)

Launcher multi-instancia de Minecraft: Java Edition para Android arm64, con OpenJDK 8/17/21/25,
renderizadores GL4ES y Zink/Vulkan con salida HDR10, controles táctiles, mandos, cuentas
Microsoft u offline y mods desde Modrinth.

## Estructura

```
app/src/main/
├─ AndroidManifest.xml            GameActivity en proceso ":game", colorMode=hdr, isGame
├─ assets/controls/default_layout.json
├─ assets/runtimes/               (opcional) jre-<N>-aarch64.tar.xz
├─ jniLibs/arm64-v8a/             binarios de terceros (Mesa, gl4es, LWJGL Android, shim GLFW)
├─ cpp/
│  ├─ CMakeLists.txt              compila libmcl_native.so + shaders GLSL→SPIR-V (glslc del NDK)
│  ├─ include/mcl_native.h        estado + API C para el shim GLFW (eventos, contexto, swap)
│  ├─ include/vk_hdr_presenter.h
│  ├─ jni_bridge.cpp              JNI de NativeBridge / LwjglBridge
│  ├─ jvm_launcher.cpp            dlopen(libjvm) + JNI_CreateJavaVM + main()
│  ├─ gl_loader.cpp               "libGL" para LWJGL: gl4es o Mesa EGL/Zink, redirección FBO 0
│  ├─ vk_hdr_presenter.cpp        swapchain A2B10G10R10 + HDR10_ST2084, metadatos ST 2086
│  ├─ input_bridge.cpp            cola de eventos táctil/teclado/mouse/mando
│  └─ shaders/                    triángulo completo + composición PQ / tone mapping
└─ java/com/example/mclauncher/
   ├─ instance/  InstanceConfig.kt, InstanceManager.kt
   ├─ jre/       JreManager.kt          (Java 25: G1, CompactObjectHeaders, Vector API, VThreads)
   ├─ display/   HdrDisplayManager.kt, FramePacer.kt
   ├─ net/       Http.kt, MojangDownloader.kt, ModrinthClient.kt
   ├─ auth/      AccountManager.kt, MicrosoftAuth.kt, TokenVault.kt
   ├─ input/     TouchControlsEngine.kt, GamepadManager.kt, LwjglBridge.kt
   ├─ launch/    MinecraftLauncher.kt, VersionResolver.kt, NativeBridge.kt, LaunchSpec.kt
   └─ ui/        MainActivity.kt (Compose), GameActivity.kt
```

Disco en el dispositivo:

```
files/shared/{versions,libraries,assets}     descargas de Mojang compartidas
files/instances/<id>/instance.json
files/instances/<id>/.minecraft/{mods,resourcepacks,shaderpacks,saves,config}
files/runtimes/java-<N>/
```

## Flujo de arranque

1. `MinecraftLauncher.prepare()`: resuelve vanilla + loader (Fabric/Quilt por meta API),
   descarga y verifica por SHA-1 desde el CDN de Mojang, elige el JRE según
   `javaVersion.majorVersion`, arma classpath y argumentos y escribe `launch_spec.json`.
2. `GameActivity` (proceso `:game`) crea el `SurfaceView`, negocia HDR y arranca HotSpot en un hilo de 8 MB.
3. El shim GLFW de LWJGL llama a `mcl_create_context` / `mcl_swap_buffers` / `mcl_poll_event`.
4. Con Zink, el juego dibuja en FBOs respaldados por memoria Vulkan (`GL_EXT_memory_object_fd`)
   y `HdrPresenter` compone en el swapchain SDR o HDR10.

## Estado

| Pieza | Estado |
|---|---|
| Proyecto Kotlin + C++ | Compila (`./gradlew assembleDebug`, AGP 8.12, NDK r28c) |
| `libglfw.so` (cpp/glfw) | Implementa las 124 funciones de GLFW 3.4; cubre las 116 que pide LWJGL 3.3.3 |
| LWJGL 3.3.3 natives (`liblwjgl*.so`) | Compilados desde el C oficial; todos los símbolos JNI de Linux presentes (salvo io_uring) |
| OpenAL Soft 1.23.1 | Compilado (backend OpenSL ES) |
| FreeType 2.13.2 | Compilado |
| gl4es | Compilado (`libgl4es_114.so`) |
| Mesa 25.3.6 Zink + EGL surfaceless | Compilado (`libEGL_mesa.so` + `libgallium-25.3.6.so`) |
| Java 17 / 21 / 25 | Se descargan del repositorio oficial de Termux (bionic aarch64) con SHA-256; Java 8 → 17 |
| Forge / NeoForge | `ForgeInstaller` ejecuta el instalador oficial en el proceso `:installer` |

## Si el juego no abre

Toca **Registro** en la instancia. Muestra el código de salida, las líneas de logcat del proceso del juego
(HotSpot, Mesa/Zink, gl4es, LWJGL), el final de `.minecraft/logs/latest.log` y el `hs_err_pid*.log` si la JVM crasheó.
El botón **Copiar** lo deja en el portapapeles para compartirlo.

GitHub Actions (`.github/workflows/build.yml`) compila el APK debug en cada push; descárgalo desde la pestaña Actions.

Para recompilar todas las librerías nativas desde cero: `ANDROID_NDK=... ./natives/build-natives.sh`.

## Pasos que siguen dependiendo de ti

| Qué | Por qué |
|---|---|
| Registrar una app en Azure (cuentas personales, "public client flows") y poner `MSA_CLIENT_ID=...` en `gradle.properties` | El login de Microsoft necesita tu propio client ID |
| Pedir a Mojang que apruebe ese client ID para `api.minecraftservices.com` | Sin aprobación, `login_with_xbox` responde 403 |
| Cambiar `MODRINTH_UA` en `app/build.gradle.kts` | Modrinth exige un User-Agent que identifique tu proyecto |
| Probar en el teléfono | Nada se ha ejecutado aún en un dispositivo real |

### Límites conocidos

- **gl4es** solo da OpenGL 2.1: sirve para Minecraft ≤ 1.16. Desde 1.17 usa Zink.
- **LWJGL** se fuerza a 3.3.3 (Minecraft 1.13 → 1.21.x). Si una versión futura exige LWJGL más nuevo,
  cambia `LWJGL_VERSION` y `LWJGL_TAG` y recompila los natives.
- **Minecraft 1.12 y anteriores** usan LWJGL 2: no están soportados.

## HDR: qué hace y qué no

- **Lo que sí hace:** pide `COLOR_MODE_HDR`, un `SurfaceView` RGBA_1010102, `Window.setDesiredHdrHeadroom` (Android 15+),
  escucha el `hdrSdrRatio` del panel (Android 14+) y crea un swapchain `A2B10G10R10 + VK_COLOR_SPACE_HDR10_ST2084_EXT`
  con metadatos estáticos (`vkSetHdrMetadataEXT`).
- **Modo `inverse_tonemap`:** el juego sigue en SDR. El presentador expande las altas luces hacia el pico del panel,
  y la UI queda a 203 nits.
- **Modo `shader_linear`:** las imágenes compartidas son RGBA16F. Necesitas un shaderpack de Iris que escriba
  luminancia lineal sin limitar (1.0 = blanco de papel) en su pase final. Los shaderpacks normales hacen tone mapping
  a [0,1], así que en este modo se verían igual que en SDR.
- **HDR10+:** se detecta, pero Android no tiene API pública para metadatos dinámicos en superficies renderizadas por la app.
  Se envía HDR10 estático.

## Poco F8 Ultra / chip de pantalla de HyperOS

El MEMC y la super resolución del coprocesador de pantalla de Xiaomi no tienen API pública: los activa Game Turbo
por app. El launcher hace lo que sí está a su alcance:

- Se declara como juego (`appCategory="game"`, `isGame`, `game_mode_config`).
- Fija la tasa de refresco con `Surface.setFrameRate`.
- Informa la carga de cada frame con `PerformanceHintManager`.
- Sincroniza con `Choreographer`.
- Ofrece `renderScale` vía `setFixedSize`, para que el escalador de hardware haga el reescalado.

## Aspectos legales

- El APK no contiene archivos de Mojang. Todo se descarga desde `launchermeta.mojang.com` / `resources.download.minecraft.net`
  en el dispositivo del usuario.
- Por defecto, los perfiles offline solo se habilitan después de iniciar sesión con una cuenta Microsoft que tenga el juego
  (`AccountManager.requireOwnershipForOffline`).
- Respeta las licencias de los binarios de terceros: OpenJDK (GPLv2+CE), Mesa/gl4es (MIT), LWJGL (BSD).
- No uses "Minecraft" en el nombre del launcher ni en su ícono (directrices de marca de Mojang).
