package net.kdt.pojavlaunch;

import androidx.annotation.Keep;
import dalvik.annotation.optimization.CriticalNative;

/** libpojavexec lo usa para saber si ART admite @CriticalNative (input_bridge_v3.c). */
@Keep
public class CriticalNativeTest {
    @CriticalNative
    public static native void testCriticalNative(int arg0, int arg1);
    public static void invokeTest() { testCriticalNative(0, 0); }
}
