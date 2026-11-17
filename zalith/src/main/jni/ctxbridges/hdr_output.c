// Salida HDR para el bridge OSMesa (Zink) de libpojavexec. Parte de MCLauncher (GPLv3).
//
// OSMesa dibuja SDR (sRGB, 8 bits). Con MCL_HDR=1 la ventana se configura como
// R16G16B16A16_FLOAT + ADATASPACE_SCRGB_LINEAR (scRGB: 1.0 = blanco SDR, >1.0 = más brillo
// que el blanco de papel) y cada cuadro se convierte con una tabla:
//   sRGB 8 bits -> lineal -> expansión de altas luces hasta MCL_HDR_HEADROOM -> half float
// Los medios tonos (<60 % de luminancia lineal) quedan igual que en SDR; solo el cielo,
// el sol, la lava, las antorchas, etc. suben hacia el pico del panel.
#include "hdr_output.h"

#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define TAG "mcl_hdr"
#define HDR_THREADS 4

static int g_enabled = -1;
static uint16_t g_lut[256];
static uint8_t* g_src = NULL;
static int g_srcW = 0, g_srcH = 0;

static uint16_t to_half(float f) {
    __fp16 h = (__fp16) f;
    uint16_t out;
    memcpy(&out, &h, sizeof out);
    return out;
}

static float srgb_to_linear(float c) {
    return c <= 0.04045f ? c / 12.92f : powf((c + 0.055f) / 1.055f, 2.4f);
}

/**
 * paper   = escala del blanco SDR (1.0 = blanco de referencia del sistema, ~203 nits)
 * headroom = cuánto más que el blanco pueden subir las altas luces (pico / blanco)
 * knee    = luminancia lineal (0..1) donde empieza la expansión de altas luces
 */
static void build_lut(float paper, float headroom, float knee) {
    uint16_t lut[256];
    if (knee > 0.98f) knee = 0.98f;
    for (int i = 0; i < 256; ++i) {
        float lin = srgb_to_linear(i / 255.0f);
        // smoothstep(knee, 1.0, lin): 0 en medios tonos, 1 en el blanco
        float t = (lin - knee) / (1.0f - knee);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        t = t * t * (3.0f - 2.0f * t);
        float gain = 1.0f + (headroom - 1.0f) * t * t;
        lut[i] = to_half(lin * gain * paper);
    }
    memcpy(g_lut, lut, sizeof lut);   // copia de una vez: el hilo de conversión ve la tabla vieja o la nueva
}

static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

static float env_float(const char* name, float def) {
    const char* v = getenv(name);
    return v ? strtof(v, NULL) : def;
}

bool hdr_enabled(void) {
    if (g_enabled < 0) {
        const char* e = getenv("MCL_HDR");
        g_enabled = (e && strcmp(e, "1") == 0) ? 1 : 0;
        if (g_enabled) {
            float paper = clampf(env_float("MCL_HDR_PAPER", 1.0f), 0.3f, 4.0f);
            float headroom = clampf(env_float("MCL_HDR_HEADROOM", 2.5f), 1.0f, 16.0f);
            float knee = clampf(env_float("MCL_HDR_KNEE", 0.6f), 0.2f, 0.98f);
            build_lut(paper, headroom, knee);
            __android_log_print(ANDROID_LOG_INFO, TAG, "HDR activo (scRGB FP16): blanco x%.2f, altas luces x%.2f, inicio %.2f",
                                paper, headroom, knee);
        }
    }
    return g_enabled == 1;
}

void hdr_configure_window(struct ANativeWindow* w) {
    if (!w) return;
    int r1 = ANativeWindow_setBuffersGeometry(w, 0, 0, AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT);
    int r2 = ANativeWindow_setBuffersDataSpace(w, ADATASPACE_SCRGB_LINEAR);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Ventana FP16 (%d) + SCRGB_LINEAR (%d)", r1, r2);
}

uint8_t* hdr_source_buffer(int w, int h) {
    if (w <= 0 || h <= 0) return NULL;
    if (w != g_srcW || h != g_srcH || g_src == NULL) {
        free(g_src);
        g_src = calloc((size_t) w * h, 4);
        g_srcW = g_src ? w : 0;
        g_srcH = g_src ? h : 0;
        __android_log_print(ANDROID_LOG_INFO, TAG, "Buffer de origen %dx%d", w, h);
    }
    return g_src;
}

int hdr_source_width(void) { return g_srcW; }
int hdr_source_height(void) { return g_srcH; }

typedef struct {
    const ANativeWindow_Buffer* dst;
    int y0, y1, w;
} job_t;

static void* convert_rows(void* arg) {
    const job_t* j = arg;
    const ANativeWindow_Buffer* dst = j->dst;
    const uint64_t alpha = (uint64_t) 0x3C00 << 48;   // 1.0 en half
    for (int y = j->y0; y < j->y1; ++y) {
        const uint8_t* s = g_src + (size_t) y * g_srcW * 4;
        if (dst->format == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) {
            uint64_t* d = (uint64_t*) dst->bits + (size_t) y * dst->stride;
            for (int x = 0; x < j->w; ++x, s += 4)
                d[x] = (uint64_t) g_lut[s[0]] | ((uint64_t) g_lut[s[1]] << 16) | ((uint64_t) g_lut[s[2]] << 32) | alpha;
        } else {
            // El sistema no aceptó FP16: copia SDR directa (RGBA/RGBX 8888)
            memcpy((uint8_t*) dst->bits + (size_t) y * dst->stride * 4, s, (size_t) j->w * 4);
        }
    }
    return NULL;
}

void hdr_present(const ANativeWindow_Buffer* dst) {
    if (!g_src || !dst || !dst->bits) return;
    int w = dst->width < g_srcW ? dst->width : g_srcW;
    int h = dst->height < g_srcH ? dst->height : g_srcH;
    pthread_t th[HDR_THREADS];
    job_t jobs[HDR_THREADS];
    int started = 0;
    for (int i = 0; i < HDR_THREADS; ++i) {
        jobs[i] = (job_t){dst, h * i / HDR_THREADS, h * (i + 1) / HDR_THREADS, w};
        if (i == HDR_THREADS - 1 || pthread_create(&th[i], NULL, convert_rows, &jobs[i]) != 0) {
            convert_rows(&jobs[i]);   // el último trozo (o si falla el hilo) en este hilo
        } else {
            started |= 1 << i;
        }
    }
    for (int i = 0; i < HDR_THREADS; ++i) if (started & (1 << i)) pthread_join(th[i], NULL);
}

/** Calibración en vivo desde el juego (com.example.mclauncher.zalith.HdrBridge). */
#include <jni.h>
JNIEXPORT void JNICALL Java_com_example_mclauncher_zalith_HdrBridge_setParams(JNIEnv* env, jclass clazz,
                                                                              jfloat paper, jfloat headroom, jfloat knee) {
    (void) env; (void) clazz;
    if (g_enabled != 1) return;   // sin consultar MCL_HDR: puede llamarse antes de que arranque la JVM
    build_lut(clampf(paper, 0.3f, 4.0f), clampf(headroom, 1.0f, 16.0f), clampf(knee, 0.2f, 0.98f));
    __android_log_print(ANDROID_LOG_INFO, TAG, "Calibración: blanco x%.2f, altas luces x%.2f, inicio %.2f", paper, headroom, knee);
}
