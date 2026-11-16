#version 450
// Composición final: SDR/escena lineal → Rec.2020 + PQ (ST 2084) para HDR10,
// o tone mapping a SDR si el panel/superficie no es HDR.
layout(set = 0, binding = 0) uniform sampler2D uFrame;
layout(push_constant) uniform PC {
    vec4 rot;
    vec4 hdr;        // x=paperWhite, y=peak, z=headroom, w=minNits
    ivec4 modes;     // x=hdrMode, y=swapchainIsHdr, z=inputIsFloat
} pc;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

// BT.709 → BT.2020 (lineal)
const mat3 kRec709To2020 = mat3(
    0.6274, 0.0691, 0.0164,
    0.3293, 0.9195, 0.0880,
    0.0433, 0.0114, 0.8956);

vec3 srgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}
vec3 linearToSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

// SMPTE ST 2084 (PQ), entrada en nits absolutos
vec3 pqEncode(vec3 nits) {
    const float m1 = 0.1593017578125, m2 = 78.84375;
    const float c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
    vec3 y = pow(clamp(nits / 10000.0, 0.0, 1.0), vec3(m1));
    return pow((c1 + c2 * y) / (1.0 + c3 * y), vec3(m2));
}

// Compresión suave de altas luces hacia el pico (curva tipo BT.2390 simplificada)
vec3 rolloff(vec3 nits, float knee, float peak) {
    float m = max(max(nits.r, nits.g), nits.b);
    if (m <= knee) return nits;
    float t = (m - knee) / max(peak - knee, 1.0);
    float compressed = knee + (peak - knee) * (t / (1.0 + t));
    return nits * (compressed / m);
}

void main() {
    vec3 c = texture(uFrame, vUv).rgb;
    float paperWhite = pc.hdr.x;
    float peak = min(pc.hdr.y, paperWhite * max(pc.hdr.z, 1.0));   // respeta el headroom del sistema

    if (pc.modes.y == 0) {                       // ---- salida SDR ----
        if (pc.modes.z == 1) {                   // escena lineal → Reinhard extendido + sRGB
            float wp = peak / paperWhite;
            c = c * (1.0 + c / (wp * wp)) / (1.0 + c);
            outColor = vec4(linearToSrgb(clamp(c, 0.0, 1.0)), 1.0);
        } else {
            outColor = vec4(c, 1.0);             // SDR 8 bits: passthrough exacto
        }
        return;
    }

    vec3 lin;                                    // ---- salida HDR10 ----
    if (pc.modes.x == 0) {
        // Expansión inversa conservadora: UI y medios tonos quedan en paperWhite,
        // solo lo cercano al blanco se expande hacia el pico.
        lin = srgbToLinear(c);
        float l = max(max(lin.r, lin.g), lin.b);
        float boost = 1.0 + (peak / paperWhite - 1.0) * smoothstep(0.75, 1.0, l) * 0.6;
        lin *= paperWhite * boost;
    } else {
        // Shaderpack escribe luminancia lineal relativa (1.0 = paperWhite)
        lin = max(c, vec3(0.0)) * paperWhite;
    }
    lin = rolloff(kRec709To2020 * lin, peak * 0.75, peak);
    outColor = vec4(pqEncode(max(lin, vec3(pc.hdr.w))), 1.0);
}
