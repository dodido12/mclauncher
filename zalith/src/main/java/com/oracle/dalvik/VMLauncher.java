package com.oracle.dalvik;

/** Lanza HotSpot con JLI_Launch (jre_launcher.c de libpojavexec). argv[0] = "java". */
public final class VMLauncher {
    private VMLauncher() {}
    public static native int launchJVM(String[] args);
    static { System.loadLibrary("pojavexec"); }
}
