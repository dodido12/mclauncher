package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.util.Log;
import androidx.annotation.Keep;

/**
 * Natives de libpojavexec (utils.c, egl_bridge.c, stdio_is.c, exit_hook.c) con los nombres
 * que espera el código de ZalithLauncher/PojavLauncher.
 */
@Keep
public final class JREUtils {
    private JREUtils() {}

    /** Lo llama libpojavexec (nominal_exit) cuando el juego termina con código != 0. */
    public interface ExitListener { void onExit(int code, boolean isSignal); }
    public static volatile ExitListener exitListener;

    @Keep
    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        Log.e("JREUtils", "La JVM terminó con código " + code + (isSignal ? " (señal)" : ""));
        ExitListener l = exitListener;
        if (l != null) l.onExit(code, isSignal);
    }

    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeGameExitHook();
    public static native void setupExitMethod(Context context);

    static {
        System.loadLibrary("exithook");
        System.loadLibrary("pojavexec");
    }
}
