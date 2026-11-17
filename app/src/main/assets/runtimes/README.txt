Runtimes OpenJDK para Android (bionic) incluidos en el APK, uno por carpeta:

  runtimes/jre-<N>/universal.tar.xz   clases, lib/modules, conf, legal (independiente de la CPU)
  runtimes/jre-<N>/bin-arm64.tar.xz   librerías nativas aarch64 (lib/server/libjvm.so, lib/*.so)
  runtimes/jre-<N>/version            identificador; si cambia, el launcher reinstala el runtime

Es el mismo formato que usan ZalithLauncher / PojavLauncher. jre-25 viene de ZalithLauncher
(OpenJDK 25.0.3, GPLv2 + Classpath Exception; licencias en legal/ dentro del runtime).
Para las versiones de Java que no estén aquí, JreManager descarga OpenJDK del repositorio de Termux.
