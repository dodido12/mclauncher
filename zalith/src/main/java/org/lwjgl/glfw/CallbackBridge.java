package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Keep;

import dalvik.annotation.optimization.CriticalNative;

/**
 * Lado Android (ART) del puente de entrada de PojavLauncher/ZalithLauncher. libpojavexec
 * registra los natives de esta clase en JNI_OnLoad (input_bridge_v3.c) y llama a
 * accessAndroidClipboard / onGrabStateChanged desde la JVM del juego.
 * Hay otra clase con el mismo nombre dentro de la JVM (lwjgl-glfw-classes.jar).
 */
@Keep
public class CallbackBridge {
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static volatile float mouseX, mouseY;
    private static volatile boolean grabbing = false;

    /** Context de la actividad del juego, para el portapapeles. */
    public static volatile Context appContext;

    public interface GrabListener { void onGrabState(boolean isGrabbing); }
    public static volatile GrabListener grabListener;

    public static boolean isGrabbing() { return grabbing; }

    public static void sendCursorPos(float x, float y) {
        mouseX = x; mouseY = y;
        nativeSendCursorPos(x, y);
    }
    public static void sendKey(int key, int scancode, int action, int mods) { nativeSendKey(key, scancode, action, mods); }
    public static void sendChar(char c, int mods) { nativeSendCharMods(c, mods); nativeSendChar(c); }
    public static void sendMouseButton(int button, int action, int mods) { nativeSendMouseButton(button, action, mods); }
    public static void sendScroll(double x, double y) { nativeSendScroll(x, y); }
    public static void sendUpdateWindowSize(int w, int h) { nativeSendScreenSize(w, h); }

    // Llamado desde la JVM del juego
    @Keep
    public static String accessAndroidClipboard(int type, String copy) {
        Context ctx = appContext;
        if (ctx == null) return type == CLIPBOARD_PASTE ? "" : null;
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        switch (type) {
            case CLIPBOARD_COPY:
                cm.setPrimaryClip(ClipData.newPlainText("Minecraft", copy));
                return null;
            case CLIPBOARD_PASTE:
                if (cm.hasPrimaryClip() && cm.getPrimaryClipDescription() != null
                        && cm.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                        && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                    return t == null ? "" : t.toString();
                }
                return "";
            case CLIPBOARD_OPEN:
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(copy)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {}
                return null;
            default:
                return null;
        }
    }

    // Llamado desde la JVM del juego cuando captura/suelta el cursor
    @Keep
    private static void onGrabStateChanged(boolean isGrabbing) {
        grabbing = isGrabbing;
        GrabListener l = grabListener;
        if (l != null) l.onGrabState(isGrabbing);
    }

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);
    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    @Keep public static native void nativeSetWindowAttrib(int attrib, int value);
    @Keep public static native int getCurrentFps();

    static { System.loadLibrary("pojavexec"); }
}
