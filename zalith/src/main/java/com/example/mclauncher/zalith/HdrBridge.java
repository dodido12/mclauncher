package com.example.mclauncher.zalith;

/** Ajuste en vivo de la salida HDR (hdr_output.c en libpojavexec). */
public final class HdrBridge {
    private HdrBridge() {}

    /**
     * @param paper    escala del blanco SDR (1.0 ≈ 203 nits, el blanco de referencia del sistema)
     * @param headroom cuántas veces más brillantes que el blanco pueden ser las altas luces
     * @param knee     luminancia (0..1) donde empiezan a expandirse las altas luces
     */
    public static native void setParams(float paper, float headroom, float knee);

    static { System.loadLibrary("pojavexec"); }
}
