package net.kdt.pojavlaunch;

import androidx.annotation.Keep;

/** Registro de la JVM (stdout/stderr) a archivo, implementado en stdio_is.c. */
@Keep
public class Logger {
    public static native void appendToLog(String text);
    /** Redirige stdout/stderr del proceso al archivo indicado (lo vacía antes). */
    public static native void begin(String logFilePath);
    public interface eventLogListener { void onEventLogged(String text); }
    public static native void setLogListener(eventLogListener logListener);
    static { System.loadLibrary("pojavexec"); }
}
