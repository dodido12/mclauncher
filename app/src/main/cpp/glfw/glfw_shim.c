/*
 * libglfw.so para Android: implementa la API pública de GLFW 3.4 (la que LWJGL 3 carga
 * dinámicamente) sobre libmcl_native: cola de eventos de Android, contexto GL de gl4es o
 * Mesa/Zink y presentación Vulkan HDR. Una sola ventana (la SurfaceView del juego).
 *
 * Se compila con los headers oficiales de GLFW (licencia zlib, ver LICENSE-glfw.md).
 */
#define GLFW_INCLUDE_NONE
#include <GLFW/glfw3.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "mcl_glfw", __VA_ARGS__)
#define SHIM_EXPORT __attribute__((visibility("default")))

/* ---------------- API de libmcl_native (ver include/mcl_native.h) ---------------- */
typedef struct { int32_t type, i0, i1, i2, i3; double d0, d1; } mcl_event;
enum { MCL_EV_KEY = 1, MCL_EV_CHAR, MCL_EV_MOUSE_BUTTON, MCL_EV_CURSOR_POS, MCL_EV_SCROLL, MCL_EV_FRAMEBUFFER_SIZE, MCL_EV_FOCUS };
extern int   mcl_poll_event(mcl_event* out);
extern void  mcl_get_window_size(int* w, int* h);
extern void  mcl_get_cursor_pos(double* x, double* y);
extern void  mcl_set_cursor_pos(double x, double y);
extern void  mcl_set_cursor_grabbed(int grabbed);
extern int   mcl_get_gamepad_state(int jid, unsigned char buttons[15], float axes[6]);
extern int   mcl_create_context(int major, int minor, int core);
extern int   mcl_make_current(int current);
extern void  mcl_swap_buffers(void);
extern void  mcl_swap_interval(int interval);
extern void* mcl_get_proc_address(const char* name);
extern float mcl_get_refresh_rate(void);
extern void  mcl_set_clipboard(const char* utf8);
extern const char* mcl_get_clipboard(void);

/* ---------------- Estado ---------------- */
struct GLFWwindow {
    int width, height, shouldClose, contextCreated;
    char title[256];
    void* user;
    int inputModes[8];          /* índice = modo - GLFW_CURSOR */
    char keys[GLFW_KEY_LAST + 1];
    char mouse[GLFW_MOUSE_BUTTON_LAST + 1];
    int ctxMajor, ctxMinor, ctxProfile, ctxForward;
    GLFWwindowposfun posCb; GLFWwindowsizefun sizeCb; GLFWwindowclosefun closeCb; GLFWwindowrefreshfun refreshCb;
    GLFWwindowfocusfun focusCb; GLFWwindowiconifyfun iconifyCb; GLFWwindowmaximizefun maximizeCb;
    GLFWframebuffersizefun fbCb; GLFWwindowcontentscalefun scaleCb;
    GLFWkeyfun keyCb; GLFWcharfun charCb; GLFWcharmodsfun charModsCb; GLFWmousebuttonfun mouseCb;
    GLFWcursorposfun cursorCb; GLFWcursorenterfun enterCb; GLFWscrollfun scrollCb; GLFWdropfun dropCb;
};
struct GLFWmonitor { void* user; };
struct GLFWcursor { int shape; };

static struct GLFWwindow* g_window;
static __thread struct GLFWwindow* g_current;
static struct GLFWmonitor g_monitor;
static GLFWmonitor* g_monitors[1] = { &g_monitor };
static GLFWvidmode g_mode;
static GLFWerrorfun g_errorCb;
static GLFWmonitorfun g_monitorCb;
static GLFWjoystickfun g_joystickCb;
static int g_lastError = GLFW_NO_ERROR;
static const char* g_lastErrorDesc;
static int g_initialized;
static uint64_t g_timerOffset;
static int g_padConnected;
static void* g_joyUser[GLFW_JOYSTICK_LAST + 1];

static struct { int major, minor, profile, forward, visible, resizable, focused; } g_hints;

static void setError(int code, const char* desc) {
    g_lastError = code; g_lastErrorDesc = desc;
    if (g_errorCb) g_errorCb(code, desc);
}

static uint64_t nowNs(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

/* ---------------- Init ---------------- */
SHIM_EXPORT int glfwInit(void) {
    if (g_initialized) return GLFW_TRUE;
    g_timerOffset = nowNs();
    glfwDefaultWindowHints();
    g_initialized = 1;
    LOG("glfwInit (shim Android)");
    return GLFW_TRUE;
}
SHIM_EXPORT void glfwTerminate(void) {
    if (g_window) glfwDestroyWindow(g_window);
    g_initialized = 0;
}
SHIM_EXPORT void glfwInitHint(int hint, int value) { (void)hint; (void)value; }
SHIM_EXPORT void glfwInitAllocator(const GLFWallocator* a) { (void)a; }
SHIM_EXPORT void glfwInitVulkanLoader(void* loader) { (void)loader; }   /* PFN_vkGetInstanceProcAddr */
SHIM_EXPORT void glfwGetVersion(int* major, int* minor, int* rev) {
    if (major) *major = 3; if (minor) *minor = 4; if (rev) *rev = 0;
}
SHIM_EXPORT const char* glfwGetVersionString(void) { return "3.4.0 Android mcl-shim EGL/Vulkan"; }
SHIM_EXPORT int glfwGetError(const char** desc) {
    int e = g_lastError;
    if (desc) *desc = e ? g_lastErrorDesc : NULL;
    g_lastError = GLFW_NO_ERROR; g_lastErrorDesc = NULL;
    return e;
}
SHIM_EXPORT GLFWerrorfun glfwSetErrorCallback(GLFWerrorfun cb) { GLFWerrorfun o = g_errorCb; g_errorCb = cb; return o; }
SHIM_EXPORT int glfwGetPlatform(void) { return GLFW_PLATFORM_NULL; }
SHIM_EXPORT int glfwPlatformSupported(int p) { return p == GLFW_PLATFORM_NULL; }

/* ---------------- Monitores (uno: el panel del teléfono) ---------------- */
static void refreshMode(void) {
    int w = 0, h = 0; mcl_get_window_size(&w, &h);
    if (w <= 0 || h <= 0) { w = 1920; h = 1080; }
    g_mode.width = w; g_mode.height = h;
    g_mode.redBits = g_mode.greenBits = g_mode.blueBits = 8;
    float hz = mcl_get_refresh_rate();
    g_mode.refreshRate = hz > 0 ? (int)(hz + 0.5f) : 60;
}
SHIM_EXPORT GLFWmonitor** glfwGetMonitors(int* count) { if (count) *count = 1; return g_monitors; }
SHIM_EXPORT GLFWmonitor* glfwGetPrimaryMonitor(void) { return &g_monitor; }
SHIM_EXPORT void glfwGetMonitorPos(GLFWmonitor* m, int* x, int* y) { (void)m; if (x) *x = 0; if (y) *y = 0; }
SHIM_EXPORT void glfwGetMonitorWorkarea(GLFWmonitor* m, int* x, int* y, int* w, int* h) {
    (void)m; refreshMode();
    if (x) *x = 0; if (y) *y = 0; if (w) *w = g_mode.width; if (h) *h = g_mode.height;
}
SHIM_EXPORT void glfwGetMonitorPhysicalSize(GLFWmonitor* m, int* w, int* h) { (void)m; if (w) *w = 160; if (h) *h = 72; }
SHIM_EXPORT void glfwGetMonitorContentScale(GLFWmonitor* m, float* x, float* y) { (void)m; if (x) *x = 1.f; if (y) *y = 1.f; }
SHIM_EXPORT const char* glfwGetMonitorName(GLFWmonitor* m) { (void)m; return "Android Display"; }
SHIM_EXPORT void glfwSetMonitorUserPointer(GLFWmonitor* m, void* p) { m->user = p; }
SHIM_EXPORT void* glfwGetMonitorUserPointer(GLFWmonitor* m) { return m->user; }
SHIM_EXPORT GLFWmonitorfun glfwSetMonitorCallback(GLFWmonitorfun cb) { GLFWmonitorfun o = g_monitorCb; g_monitorCb = cb; return o; }
SHIM_EXPORT const GLFWvidmode* glfwGetVideoModes(GLFWmonitor* m, int* count) { (void)m; refreshMode(); if (count) *count = 1; return &g_mode; }
SHIM_EXPORT const GLFWvidmode* glfwGetVideoMode(GLFWmonitor* m) { (void)m; refreshMode(); return &g_mode; }
SHIM_EXPORT void glfwSetGamma(GLFWmonitor* m, float g) { (void)m; (void)g; }
SHIM_EXPORT const GLFWgammaramp* glfwGetGammaRamp(GLFWmonitor* m) { (void)m; return NULL; }
SHIM_EXPORT void glfwSetGammaRamp(GLFWmonitor* m, const GLFWgammaramp* r) { (void)m; (void)r; }

/* ---------------- Ventana ---------------- */
SHIM_EXPORT void glfwDefaultWindowHints(void) {
    memset(&g_hints, 0, sizeof g_hints);
    g_hints.major = 1; g_hints.minor = 0; g_hints.profile = GLFW_OPENGL_ANY_PROFILE;
    g_hints.visible = g_hints.resizable = g_hints.focused = 1;
}
SHIM_EXPORT void glfwWindowHint(int hint, int value) {
    switch (hint) {
        case GLFW_CONTEXT_VERSION_MAJOR: g_hints.major = value; break;
        case GLFW_CONTEXT_VERSION_MINOR: g_hints.minor = value; break;
        case GLFW_OPENGL_PROFILE: g_hints.profile = value; break;
        case GLFW_OPENGL_FORWARD_COMPAT: g_hints.forward = value; break;
        case GLFW_VISIBLE: g_hints.visible = value; break;
        case GLFW_RESIZABLE: g_hints.resizable = value; break;
        case GLFW_FOCUSED: g_hints.focused = value; break;
        default: break;
    }
}
SHIM_EXPORT void glfwWindowHintString(int hint, const char* v) { (void)hint; (void)v; }

SHIM_EXPORT GLFWwindow* glfwCreateWindow(int width, int height, const char* title, GLFWmonitor* mon, GLFWwindow* share) {
    (void)mon; (void)share;
    if (g_window) { setError(GLFW_PLATFORM_ERROR, "Android: solo se admite una ventana"); return NULL; }
    struct GLFWwindow* w = calloc(1, sizeof *w);
    int sw = 0, sh = 0; mcl_get_window_size(&sw, &sh);
    w->width = sw > 0 ? sw : width; w->height = sh > 0 ? sh : height;
    if (title) strncpy(w->title, title, sizeof w->title - 1);
    w->inputModes[GLFW_CURSOR - GLFW_CURSOR] = GLFW_CURSOR_NORMAL;
    w->ctxMajor = g_hints.major; w->ctxMinor = g_hints.minor;
    w->ctxProfile = g_hints.profile; w->ctxForward = g_hints.forward;
    g_window = w;
    LOG("glfwCreateWindow %dx%d \"%s\" GL %d.%d perfil=0x%x", w->width, w->height, w->title, w->ctxMajor, w->ctxMinor, w->ctxProfile);
    return w;
}
SHIM_EXPORT void glfwDestroyWindow(GLFWwindow* w) {
    if (!w) return;
    if (g_current == w) { mcl_make_current(0); g_current = NULL; }
    if (g_window == w) g_window = NULL;
    free(w);
}
SHIM_EXPORT int glfwWindowShouldClose(GLFWwindow* w) { return w->shouldClose; }
SHIM_EXPORT void glfwSetWindowShouldClose(GLFWwindow* w, int v) { w->shouldClose = v; }
SHIM_EXPORT const char* glfwGetWindowTitle(GLFWwindow* w) { return w->title; }
SHIM_EXPORT void glfwSetWindowTitle(GLFWwindow* w, const char* t) { if (t) { strncpy(w->title, t, sizeof w->title - 1); w->title[sizeof w->title - 1] = 0; } }
SHIM_EXPORT void glfwSetWindowIcon(GLFWwindow* w, int n, const GLFWimage* i) { (void)w; (void)n; (void)i; }
SHIM_EXPORT void glfwGetWindowPos(GLFWwindow* w, int* x, int* y) { (void)w; if (x) *x = 0; if (y) *y = 0; }
SHIM_EXPORT void glfwSetWindowPos(GLFWwindow* w, int x, int y) { (void)w; (void)x; (void)y; }
SHIM_EXPORT void glfwGetWindowSize(GLFWwindow* w, int* x, int* y) { if (x) *x = w->width; if (y) *y = w->height; }
SHIM_EXPORT void glfwSetWindowSizeLimits(GLFWwindow* w, int a, int b, int c, int d) { (void)w; (void)a; (void)b; (void)c; (void)d; }
SHIM_EXPORT void glfwSetWindowAspectRatio(GLFWwindow* w, int n, int d) { (void)w; (void)n; (void)d; }
SHIM_EXPORT void glfwSetWindowSize(GLFWwindow* w, int x, int y) { (void)w; (void)x; (void)y; }   /* la superficie la decide Android */
SHIM_EXPORT void glfwGetFramebufferSize(GLFWwindow* w, int* x, int* y) { if (x) *x = w->width; if (y) *y = w->height; }
SHIM_EXPORT void glfwGetWindowFrameSize(GLFWwindow* w, int* l, int* t, int* r, int* b) { (void)w; if (l) *l = 0; if (t) *t = 0; if (r) *r = 0; if (b) *b = 0; }
SHIM_EXPORT void glfwGetWindowContentScale(GLFWwindow* w, float* x, float* y) { (void)w; if (x) *x = 1.f; if (y) *y = 1.f; }
SHIM_EXPORT float glfwGetWindowOpacity(GLFWwindow* w) { (void)w; return 1.f; }
SHIM_EXPORT void glfwSetWindowOpacity(GLFWwindow* w, float o) { (void)w; (void)o; }
SHIM_EXPORT void glfwIconifyWindow(GLFWwindow* w) { (void)w; }
SHIM_EXPORT void glfwRestoreWindow(GLFWwindow* w) { (void)w; }
SHIM_EXPORT void glfwMaximizeWindow(GLFWwindow* w) { (void)w; }
SHIM_EXPORT void glfwShowWindow(GLFWwindow* w) { (void)w; }
SHIM_EXPORT void glfwHideWindow(GLFWwindow* w) { (void)w; }
SHIM_EXPORT void glfwFocusWindow(GLFWwindow* w) { (void)w; }
SHIM_EXPORT void glfwRequestWindowAttention(GLFWwindow* w) { (void)w; }
SHIM_EXPORT GLFWmonitor* glfwGetWindowMonitor(GLFWwindow* w) { (void)w; return &g_monitor; }   /* siempre "pantalla completa" */
SHIM_EXPORT void glfwSetWindowMonitor(GLFWwindow* w, GLFWmonitor* m, int x, int y, int ww, int hh, int r) {
    (void)w; (void)m; (void)x; (void)y; (void)ww; (void)hh; (void)r;
}
SHIM_EXPORT int glfwGetWindowAttrib(GLFWwindow* w, int a) {
    switch (a) {
        case GLFW_FOCUSED: case GLFW_VISIBLE: case GLFW_HOVERED: case GLFW_MAXIMIZED: case GLFW_DECORATED: return GLFW_TRUE;
        case GLFW_RESIZABLE: return GLFW_FALSE;
        case GLFW_ICONIFIED: case GLFW_FLOATING: case GLFW_TRANSPARENT_FRAMEBUFFER: case GLFW_AUTO_ICONIFY: return GLFW_FALSE;
        case GLFW_CLIENT_API: return GLFW_OPENGL_API;
        case GLFW_CONTEXT_CREATION_API: return GLFW_EGL_CONTEXT_API;
        case GLFW_CONTEXT_VERSION_MAJOR: return w->ctxMajor > 1 ? w->ctxMajor : 4;
        case GLFW_CONTEXT_VERSION_MINOR: return w->ctxMajor > 1 ? w->ctxMinor : 6;
        case GLFW_CONTEXT_REVISION: return 0;
        case GLFW_OPENGL_PROFILE: return w->ctxProfile;
        case GLFW_OPENGL_FORWARD_COMPAT: return w->ctxForward;
        default: return 0;
    }
}
SHIM_EXPORT void glfwSetWindowAttrib(GLFWwindow* w, int a, int v) { (void)w; (void)a; (void)v; }
SHIM_EXPORT void glfwSetWindowUserPointer(GLFWwindow* w, void* p) { w->user = p; }
SHIM_EXPORT void* glfwGetWindowUserPointer(GLFWwindow* w) { return w->user; }

#define CB_SETTER(fn, field, type) \
    SHIM_EXPORT type fn(GLFWwindow* w, type cb) { type o = w->field; w->field = cb; return o; }
CB_SETTER(glfwSetWindowPosCallback, posCb, GLFWwindowposfun)
CB_SETTER(glfwSetWindowSizeCallback, sizeCb, GLFWwindowsizefun)
CB_SETTER(glfwSetWindowCloseCallback, closeCb, GLFWwindowclosefun)
CB_SETTER(glfwSetWindowRefreshCallback, refreshCb, GLFWwindowrefreshfun)
CB_SETTER(glfwSetWindowFocusCallback, focusCb, GLFWwindowfocusfun)
CB_SETTER(glfwSetWindowIconifyCallback, iconifyCb, GLFWwindowiconifyfun)
CB_SETTER(glfwSetWindowMaximizeCallback, maximizeCb, GLFWwindowmaximizefun)
CB_SETTER(glfwSetFramebufferSizeCallback, fbCb, GLFWframebuffersizefun)
CB_SETTER(glfwSetWindowContentScaleCallback, scaleCb, GLFWwindowcontentscalefun)
CB_SETTER(glfwSetKeyCallback, keyCb, GLFWkeyfun)
CB_SETTER(glfwSetCharCallback, charCb, GLFWcharfun)
CB_SETTER(glfwSetCharModsCallback, charModsCb, GLFWcharmodsfun)
CB_SETTER(glfwSetMouseButtonCallback, mouseCb, GLFWmousebuttonfun)
CB_SETTER(glfwSetCursorPosCallback, cursorCb, GLFWcursorposfun)
CB_SETTER(glfwSetCursorEnterCallback, enterCb, GLFWcursorenterfun)
CB_SETTER(glfwSetScrollCallback, scrollCb, GLFWscrollfun)
CB_SETTER(glfwSetDropCallback, dropCb, GLFWdropfun)

/* ---------------- Eventos ---------------- */
static void pollJoystickConnection(void) {
    unsigned char b[15]; float a[6];
    int c = mcl_get_gamepad_state(0, b, a);
    if (c != g_padConnected) {
        g_padConnected = c;
        if (g_joystickCb) g_joystickCb(GLFW_JOYSTICK_1, c ? GLFW_CONNECTED : GLFW_DISCONNECTED);
    }
}

static int dispatchEvents(void) {
    mcl_event e; int n = 0;
    struct GLFWwindow* w = g_window;
    while (mcl_poll_event(&e)) {
        ++n;
        if (!w) continue;
        switch (e.type) {
            case MCL_EV_KEY:
                if (e.i0 >= 0 && e.i0 <= GLFW_KEY_LAST) w->keys[e.i0] = (char)(e.i2 != GLFW_RELEASE);
                if (w->keyCb) w->keyCb(w, e.i0, e.i1, e.i2, e.i3);
                break;
            case MCL_EV_CHAR:
                if (w->charCb) w->charCb(w, (unsigned int)e.i0);
                if (w->charModsCb) w->charModsCb(w, (unsigned int)e.i0, e.i1);
                break;
            case MCL_EV_MOUSE_BUTTON:
                if (e.i0 >= 0 && e.i0 <= GLFW_MOUSE_BUTTON_LAST) w->mouse[e.i0] = (char)e.i1;
                if (w->mouseCb) w->mouseCb(w, e.i0, e.i1, e.i2);
                break;
            case MCL_EV_CURSOR_POS:
                if (w->cursorCb) w->cursorCb(w, e.d0, e.d1);
                break;
            case MCL_EV_SCROLL:
                if (w->scrollCb) w->scrollCb(w, e.d0, e.d1);
                break;
            case MCL_EV_FRAMEBUFFER_SIZE:
                if (e.i0 > 0 && e.i1 > 0 && (e.i0 != w->width || e.i1 != w->height)) {
                    w->width = e.i0; w->height = e.i1;
                    if (w->sizeCb) w->sizeCb(w, e.i0, e.i1);
                    if (w->fbCb) w->fbCb(w, e.i0, e.i1);
                }
                break;
            case MCL_EV_FOCUS:
                if (w->focusCb) w->focusCb(w, e.i0);
                break;
            default: break;
        }
    }
    pollJoystickConnection();
    return n;
}
SHIM_EXPORT void glfwPollEvents(void) { dispatchEvents(); }
SHIM_EXPORT void glfwWaitEventsTimeout(double timeout) {
    uint64_t end = nowNs() + (uint64_t)(timeout * 1e9);
    while (!dispatchEvents() && nowNs() < end) usleep(1000);
}
SHIM_EXPORT void glfwWaitEvents(void) { while (!dispatchEvents()) usleep(1000); }
SHIM_EXPORT void glfwPostEmptyEvent(void) {}

/* ---------------- Entrada ---------------- */
SHIM_EXPORT int glfwGetInputMode(GLFWwindow* w, int mode) {
    int i = mode - GLFW_CURSOR;
    return (i >= 0 && i < 8) ? w->inputModes[i] : 0;
}
SHIM_EXPORT void glfwSetInputMode(GLFWwindow* w, int mode, int value) {
    int i = mode - GLFW_CURSOR;
    if (i < 0 || i >= 8) return;
    w->inputModes[i] = value;
    if (mode == GLFW_CURSOR) {
        int grab = value == GLFW_CURSOR_DISABLED || value == GLFW_CURSOR_CAPTURED;
        mcl_set_cursor_grabbed(grab);
        if (!grab) mcl_set_cursor_pos(w->width / 2.0, w->height / 2.0);
    }
}
SHIM_EXPORT int glfwRawMouseMotionSupported(void) { return GLFW_TRUE; }

SHIM_EXPORT const char* glfwGetKeyName(int key, int scancode) {
    (void)scancode;
    static char buf[2];
    if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z) { buf[0] = (char)('a' + key - GLFW_KEY_A); buf[1] = 0; return buf; }
    if ((key >= GLFW_KEY_0 && key <= GLFW_KEY_9) || key == GLFW_KEY_APOSTROPHE || key == GLFW_KEY_COMMA ||
        key == GLFW_KEY_MINUS || key == GLFW_KEY_PERIOD || key == GLFW_KEY_SLASH || key == GLFW_KEY_SEMICOLON ||
        key == GLFW_KEY_EQUAL || key == GLFW_KEY_LEFT_BRACKET || key == GLFW_KEY_BACKSLASH ||
        key == GLFW_KEY_RIGHT_BRACKET || key == GLFW_KEY_GRAVE_ACCENT) { buf[0] = (char)key; buf[1] = 0; return buf; }
    return NULL;
}
SHIM_EXPORT int glfwGetKeyScancode(int key) { return key; }
SHIM_EXPORT int glfwGetKey(GLFWwindow* w, int key) { return (key >= 0 && key <= GLFW_KEY_LAST) ? w->keys[key] : GLFW_RELEASE; }
SHIM_EXPORT int glfwGetMouseButton(GLFWwindow* w, int b) { return (b >= 0 && b <= GLFW_MOUSE_BUTTON_LAST) ? w->mouse[b] : GLFW_RELEASE; }
SHIM_EXPORT void glfwGetCursorPos(GLFWwindow* w, double* x, double* y) { (void)w; double cx, cy; mcl_get_cursor_pos(&cx, &cy); if (x) *x = cx; if (y) *y = cy; }
SHIM_EXPORT void glfwSetCursorPos(GLFWwindow* w, double x, double y) { (void)w; mcl_set_cursor_pos(x, y); }
SHIM_EXPORT GLFWcursor* glfwCreateCursor(const GLFWimage* img, int x, int y) { (void)img; (void)x; (void)y; return calloc(1, sizeof(struct GLFWcursor)); }
SHIM_EXPORT GLFWcursor* glfwCreateStandardCursor(int shape) { struct GLFWcursor* c = calloc(1, sizeof *c); c->shape = shape; return c; }
SHIM_EXPORT void glfwDestroyCursor(GLFWcursor* c) { free(c); }
SHIM_EXPORT void glfwSetCursor(GLFWwindow* w, GLFWcursor* c) { (void)w; (void)c; }

/* ---------------- Mandos (jid 0 = mando activo de Android) ---------------- */
static unsigned char g_btn[15 + 4]; static float g_axes[6]; static unsigned char g_hat[1];
static int readPad(int jid) {
    if (jid != GLFW_JOYSTICK_1) return 0;
    unsigned char b[15];
    int c = mcl_get_gamepad_state(0, b, g_axes);
    memcpy(g_btn, b, 15);
    /* hat a partir del D-pad: up=11 right=12 down=13 left=14 (layout GLFW gamepad) */
    g_hat[0] = (unsigned char)((b[11] ? GLFW_HAT_UP : 0) | (b[12] ? GLFW_HAT_RIGHT : 0) | (b[13] ? GLFW_HAT_DOWN : 0) | (b[14] ? GLFW_HAT_LEFT : 0));
    memcpy(g_btn + 15, b + 11, 4);
    return c;
}
SHIM_EXPORT int glfwJoystickPresent(int jid) { return readPad(jid); }
SHIM_EXPORT const float* glfwGetJoystickAxes(int jid, int* n) { if (!readPad(jid)) { if (n) *n = 0; return NULL; } if (n) *n = 6; return g_axes; }
SHIM_EXPORT const unsigned char* glfwGetJoystickButtons(int jid, int* n) { if (!readPad(jid)) { if (n) *n = 0; return NULL; } if (n) *n = 19; return g_btn; }
SHIM_EXPORT const unsigned char* glfwGetJoystickHats(int jid, int* n) { if (!readPad(jid)) { if (n) *n = 0; return NULL; } if (n) *n = 1; return g_hat; }
SHIM_EXPORT const char* glfwGetJoystickName(int jid) { return readPad(jid) ? "Android Gamepad" : NULL; }
SHIM_EXPORT const char* glfwGetJoystickGUID(int jid) { return readPad(jid) ? "03000000de280000ff11000001000000" : NULL; }   /* GUID estilo XInput */
SHIM_EXPORT void glfwSetJoystickUserPointer(int jid, void* p) { if (jid >= 0 && jid <= GLFW_JOYSTICK_LAST) g_joyUser[jid] = p; }
SHIM_EXPORT void* glfwGetJoystickUserPointer(int jid) { return (jid >= 0 && jid <= GLFW_JOYSTICK_LAST) ? g_joyUser[jid] : NULL; }
SHIM_EXPORT int glfwJoystickIsGamepad(int jid) { return readPad(jid); }
SHIM_EXPORT GLFWjoystickfun glfwSetJoystickCallback(GLFWjoystickfun cb) { GLFWjoystickfun o = g_joystickCb; g_joystickCb = cb; return o; }
SHIM_EXPORT int glfwUpdateGamepadMappings(const char* s) { (void)s; return GLFW_TRUE; }
SHIM_EXPORT const char* glfwGetGamepadName(int jid) { return readPad(jid) ? "Android Gamepad" : NULL; }
SHIM_EXPORT int glfwGetGamepadState(int jid, GLFWgamepadstate* st) {
    if (!readPad(jid)) return GLFW_FALSE;
    memcpy(st->buttons, g_btn, 15);
    memcpy(st->axes, g_axes, sizeof st->axes);
    return GLFW_TRUE;
}

/* ---------------- Portapapeles ---------------- */
SHIM_EXPORT void glfwSetClipboardString(GLFWwindow* w, const char* s) { (void)w; mcl_set_clipboard(s ? s : ""); }
SHIM_EXPORT const char* glfwGetClipboardString(GLFWwindow* w) { (void)w; return mcl_get_clipboard(); }

/* ---------------- Tiempo ---------------- */
SHIM_EXPORT double glfwGetTime(void) { return (double)(nowNs() - g_timerOffset) / 1e9; }
SHIM_EXPORT void glfwSetTime(double t) { g_timerOffset = nowNs() - (uint64_t)(t * 1e9); }
SHIM_EXPORT uint64_t glfwGetTimerValue(void) { return nowNs(); }
SHIM_EXPORT uint64_t glfwGetTimerFrequency(void) { return 1000000000ull; }

/* ---------------- Contexto ---------------- */
SHIM_EXPORT void glfwMakeContextCurrent(GLFWwindow* w) {
    if (w && !w->contextCreated) {
        int core = w->ctxProfile == GLFW_OPENGL_CORE_PROFILE;
        int major = w->ctxMajor > 1 ? w->ctxMajor : 0;   /* 0 = el máximo que dé el driver */
        if (!mcl_create_context(major, w->ctxMinor, core)) {
            setError(GLFW_VERSION_UNAVAILABLE, "No se pudo crear el contexto OpenGL (ver logcat mcl_gl)");
            return;
        }
        w->contextCreated = 1;
    }
    mcl_make_current(w != NULL);
    g_current = w;
}
SHIM_EXPORT GLFWwindow* glfwGetCurrentContext(void) { return g_current; }
SHIM_EXPORT void glfwSwapBuffers(GLFWwindow* w) { (void)w; mcl_swap_buffers(); }
SHIM_EXPORT void glfwSwapInterval(int i) { mcl_swap_interval(i); }
SHIM_EXPORT GLFWglproc glfwGetProcAddress(const char* name) { return (GLFWglproc)mcl_get_proc_address(name); }

typedef const unsigned char* (*PFN_getStringi)(unsigned int, unsigned int);
typedef void (*PFN_getIntegerv)(unsigned int, int*);
SHIM_EXPORT int glfwExtensionSupported(const char* ext) {
    PFN_getStringi getStringi = (PFN_getStringi)mcl_get_proc_address("glGetStringi");
    PFN_getIntegerv getIntegerv = (PFN_getIntegerv)mcl_get_proc_address("glGetIntegerv");
    if (!getStringi || !getIntegerv || !ext) return GLFW_FALSE;
    int n = 0; getIntegerv(0x821D /* GL_NUM_EXTENSIONS */, &n);
    for (int i = 0; i < n; ++i) {
        const unsigned char* e = getStringi(0x1F03 /* GL_EXTENSIONS */, (unsigned)i);
        if (e && strcmp((const char*)e, ext) == 0) return GLFW_TRUE;
    }
    return GLFW_FALSE;
}

/* ---------------- Vulkan (no expuesto al juego: Zink lo usa por debajo) ---------------- */
SHIM_EXPORT int glfwVulkanSupported(void) { return GLFW_FALSE; }
SHIM_EXPORT const char** glfwGetRequiredInstanceExtensions(uint32_t* count) { if (count) *count = 0; return NULL; }
SHIM_EXPORT void* glfwGetInstanceProcAddress(void* instance, const char* name) { (void)instance; (void)name; return NULL; }
SHIM_EXPORT int glfwGetPhysicalDevicePresentationSupport(void* inst, void* dev, uint32_t qf) { (void)inst; (void)dev; (void)qf; return GLFW_FALSE; }
SHIM_EXPORT int glfwCreateWindowSurface(void* inst, GLFWwindow* w, const void* alloc, void* surface) {
    (void)inst; (void)w; (void)alloc; (void)surface; return -7; /* VK_ERROR_EXTENSION_NOT_PRESENT */
}
