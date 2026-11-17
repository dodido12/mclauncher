# GLFW en Java de ZalithLauncher (modificado)

`org/lwjgl/glfw/GLFW.java` viene de `jre_lwjgl3glfw` de ZalithLauncher (GPLv3) con un cambio de
MCLauncher: al quedar lista la entrada se llama al callback de foco de ventana (Minecraft 26.x
ignora los clics si la ventana no está activa).

Para regenerar la clase dentro de `zalith/src/main/assets/zalith/lwjgl-glfw-classes.jar`:

    javac --release 8 -cp zalith/src/main/assets/zalith/lwjgl-glfw-classes.jar -d out org/lwjgl/glfw/GLFW.java
    (cd out && zip -u ../zalith/src/main/assets/zalith/lwjgl-glfw-classes.jar org/lwjgl/glfw/GLFW*.class)
