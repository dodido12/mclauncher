// "libGL" que ve LWJGL + gestión de contexto y presentación para cada renderizador.
//
//  GL4ES : EGL del sistema (GLES 3) sobre la ventana + gl4es (GL 2.1 → GLES). SDR.
//  ZINK  : Mesa EGL (plataforma surfaceless) con driver Zink → GL 4.6 sobre Vulkan.
//          El framebuffer 0 del juego se redirige a FBOs cuyas texturas son memoria
//          Vulkan importada (GL_EXT_memory_object_fd). HdrPresenter compone y presenta
//          en un swapchain SDR o HDR10 (10 bits, Rec.2020/PQ).
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include "mcl_native.h"
#include "vk_hdr_presenter.h"

#define LOG_TAG "mcl_gl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- Constantes de GL de escritorio / EXT_memory_object (no todas están en headers GLES) ---
#define MCL_GL_RGBA16F 0x881A
#define MCL_GL_RGBA8 0x8058
#define MCL_GL_DEPTH24_STENCIL8 0x88F0
#define MCL_GL_DEPTH_STENCIL_ATTACHMENT 0x821A
#define MCL_GL_DRAW_FRAMEBUFFER_BINDING 0x8CA6
#define MCL_GL_READ_FRAMEBUFFER_BINDING 0x8CAA
#define MCL_GL_READ_FRAMEBUFFER 0x8CA8
#define MCL_GL_DRAW_FRAMEBUFFER 0x8CA9
#define MCL_GL_BACK 0x0405
#define MCL_GL_FRONT 0x0404
#define MCL_GL_BACK_LEFT 0x0402
#define MCL_GL_FRONT_LEFT 0x0400
#define MCL_GL_TEXTURE_TILING_EXT 0x9580
#define MCL_GL_DEDICATED_MEMORY_OBJECT_EXT 0x9581
#define MCL_GL_OPTIMAL_TILING_EXT 0x9584
#define MCL_GL_HANDLE_TYPE_OPAQUE_FD_EXT 0x9586
#define MCL_GL_LAYOUT_SHADER_READ_ONLY_EXT 0x9591
#define MCL_GL_DEVICE_UUID_EXT 0x9597
#define MCL_EGL_PLATFORM_SURFACELESS_MESA 0x31DD

namespace {

enum class Backend { NONE, GL4ES, ZINK };
Backend gBackend = Backend::NONE;
void* gGlLib = nullptr;

// ---------- EGL del sistema (GL4ES) ----------
EGLDisplay gSysDpy = EGL_NO_DISPLAY;
EGLSurface gSysSurf = EGL_NO_SURFACE;
EGLContext gSysCtx = EGL_NO_CONTEXT;
EGLConfig gSysCfg = nullptr;
using GetProcFn = void* (*)(const char*);
GetProcFn gGl4esGetProc = nullptr;

// ---------- Mesa EGL (Zink) ----------
struct MesaEgl {
    decltype(&eglGetProcAddress) GetProcAddress;
    decltype(&eglInitialize) Initialize;
    decltype(&eglBindAPI) BindAPI;
    decltype(&eglChooseConfig) ChooseConfig;
    decltype(&eglCreateContext) CreateContext;
    decltype(&eglMakeCurrent) MakeCurrent;
    decltype(&eglQueryString) QueryString;
    PFNEGLGETPLATFORMDISPLAYEXTPROC GetPlatformDisplayEXT;
} M{};
EGLDisplay gMesaDpy = EGL_NO_DISPLAY;
EGLContext gMesaCtx = EGL_NO_CONTEXT;

// ---------- Funciones GL reales (Mesa) ----------
#define GLFN(ret, name, ...) using PFN_##name = ret (*)(__VA_ARGS__); PFN_##name r_##name = nullptr;
GLFN(void, glBindFramebuffer, GLenum, GLuint)
GLFN(void, glGetIntegerv, GLenum, GLint*)
GLFN(void, glDrawBuffer, GLenum)
GLFN(void, glReadBuffer, GLenum)
GLFN(void, glGenFramebuffers, GLsizei, GLuint*)
GLFN(void, glDeleteFramebuffers, GLsizei, const GLuint*)
GLFN(void, glFramebufferTexture2D, GLenum, GLenum, GLenum, GLuint, GLint)
GLFN(void, glGenRenderbuffers, GLsizei, GLuint*)
GLFN(void, glDeleteRenderbuffers, GLsizei, const GLuint*)
GLFN(void, glBindRenderbuffer, GLenum, GLuint)
GLFN(void, glRenderbufferStorage, GLenum, GLenum, GLsizei, GLsizei)
GLFN(void, glFramebufferRenderbuffer, GLenum, GLenum, GLenum, GLuint)
GLFN(GLenum, glCheckFramebufferStatus, GLenum)
GLFN(void, glGenTextures, GLsizei, GLuint*)
GLFN(void, glDeleteTextures, GLsizei, const GLuint*)
GLFN(void, glBindTexture, GLenum, GLuint)
GLFN(void, glTexParameteri, GLenum, GLenum, GLint)
GLFN(void, glFlush, void)
GLFN(void, glFinish, void)
GLFN(void, glCreateMemoryObjectsEXT, GLsizei, GLuint*)
GLFN(void, glDeleteMemoryObjectsEXT, GLsizei, const GLuint*)
GLFN(void, glMemoryObjectParameterivEXT, GLuint, GLenum, const GLint*)
GLFN(void, glImportMemoryFdEXT, GLuint, GLuint64, GLenum, GLint)
GLFN(void, glTexStorageMem2DEXT, GLenum, GLsizei, GLenum, GLsizei, GLsizei, GLuint, GLuint64)
GLFN(void, glGenSemaphoresEXT, GLsizei, GLuint*)
GLFN(void, glDeleteSemaphoresEXT, GLsizei, const GLuint*)
GLFN(void, glImportSemaphoreFdEXT, GLuint, GLenum, GLint)
GLFN(void, glSignalSemaphoreEXT, GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*)
GLFN(void, glWaitSemaphoreEXT, GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*)
GLFN(void, glGetUnsignedBytevEXT, GLenum, GLubyte*)
#undef GLFN

struct Target { GLuint tex = 0, mem = 0, fbo = 0, depth = 0, semGl = 0, semVk = 0; bool first = true; };
std::vector<Target> gTargets;
constexpr uint32_t kTargets = 3;
uint32_t gCur = 0;
int gTargetW = 0, gTargetH = 0;
bool gDrawIsDefault = true, gReadIsDefault = true;
int64_t gLastSwapEnd = 0;

int64_t nowNs() { timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec * 1'000'000'000LL + ts.tv_nsec; }
GLuint curFbo() { return gTargets.empty() ? 0 : gTargets[gCur].fbo; }

std::string nativeLib(const char* name) {
    const char* dir = getenv("MCL_NATIVE_DIR");
    return std::string(dir ? dir : ".") + "/" + name;
}

ANativeWindow* waitForWindow() {
    for (int i = 0; i < 2000; ++i) {   // hasta ~20 s
        { std::lock_guard<std::mutex> l(mcl::state().mtx); if (mcl::state().window) return mcl::state().window; }
        usleep(10'000);
    }
    return nullptr;
}

// ---------------- Hooks de framebuffer 0 (solo Zink) ----------------
void hook_glBindFramebuffer(GLenum target, GLuint fb) {
    bool def = fb == 0;
    if (target == GL_FRAMEBUFFER || target == MCL_GL_DRAW_FRAMEBUFFER) gDrawIsDefault = def;
    if (target == GL_FRAMEBUFFER || target == MCL_GL_READ_FRAMEBUFFER) gReadIsDefault = def;
    r_glBindFramebuffer(target, def ? curFbo() : fb);
}
void hook_glGetIntegerv(GLenum pname, GLint* data) {
    r_glGetIntegerv(pname, data);
    if ((pname == MCL_GL_DRAW_FRAMEBUFFER_BINDING || pname == MCL_GL_READ_FRAMEBUFFER_BINDING) && (GLuint)*data == curFbo())
        *data = 0;
}
GLenum mapDefaultBuffer(GLenum b) {
    return (b == MCL_GL_BACK || b == MCL_GL_FRONT || b == MCL_GL_BACK_LEFT || b == MCL_GL_FRONT_LEFT) ? GL_COLOR_ATTACHMENT0 : b;
}
void hook_glDrawBuffer(GLenum b) { r_glDrawBuffer(gDrawIsDefault ? mapDefaultBuffer(b) : b); }
void hook_glReadBuffer(GLenum b) { r_glReadBuffer(gReadIsDefault ? mapDefaultBuffer(b) : b); }

// ---------------- Zink ----------------
template <typename T> void loadGl(T& fn, const char* name) {
    fn = reinterpret_cast<T>(M.GetProcAddress(name));
    if (!fn) LOGE("GL: falta %s", name);
}

void loadMesaGl() {
#define L(n) loadGl(r_##n, #n)
    L(glBindFramebuffer); L(glGetIntegerv); L(glDrawBuffer); L(glReadBuffer); L(glGenFramebuffers); L(glDeleteFramebuffers);
    L(glFramebufferTexture2D); L(glGenRenderbuffers); L(glDeleteRenderbuffers); L(glBindRenderbuffer); L(glRenderbufferStorage);
    L(glFramebufferRenderbuffer); L(glCheckFramebufferStatus); L(glGenTextures); L(glDeleteTextures); L(glBindTexture);
    L(glTexParameteri); L(glFlush); L(glFinish); L(glCreateMemoryObjectsEXT); L(glDeleteMemoryObjectsEXT);
    L(glMemoryObjectParameterivEXT); L(glImportMemoryFdEXT); L(glTexStorageMem2DEXT); L(glGenSemaphoresEXT);
    L(glDeleteSemaphoresEXT); L(glImportSemaphoreFdEXT); L(glSignalSemaphoreEXT); L(glWaitSemaphoreEXT); L(glGetUnsignedBytevEXT);
#undef L
}

void destroyTargets() {
    if (gTargets.empty()) return;
    r_glFinish();
    for (auto& t : gTargets) {
        r_glDeleteFramebuffers(1, &t.fbo); r_glDeleteRenderbuffers(1, &t.depth); r_glDeleteTextures(1, &t.tex);
        r_glDeleteMemoryObjectsEXT(1, &t.mem); r_glDeleteSemaphoresEXT(1, &t.semGl); r_glDeleteSemaphoresEXT(1, &t.semVk);
    }
    gTargets.clear();
}

bool createTargets(int w, int h) {
    auto& st = mcl::state();
    auto& pres = mcl::HdrPresenter::instance();
    const bool floatFmt = pres.isHdrActive() && st.hdr.mode == mcl::HdrMode::SHADER_LINEAR;
    std::vector<mcl::SharedImageFds> fds;
    if (!pres.createSharedImages(kTargets, (uint32_t)w, (uint32_t)h, floatFmt, fds)) return false;

    gTargets.resize(kTargets);
    for (uint32_t i = 0; i < kTargets; ++i) {
        Target& t = gTargets[i];
        GLint one = GL_TRUE;
        r_glCreateMemoryObjectsEXT(1, &t.mem);
        r_glMemoryObjectParameterivEXT(t.mem, MCL_GL_DEDICATED_MEMORY_OBJECT_EXT, &one);
        r_glImportMemoryFdEXT(t.mem, fds[i].size, MCL_GL_HANDLE_TYPE_OPAQUE_FD_EXT, fds[i].memoryFd);   // GL toma el fd

        r_glGenTextures(1, &t.tex);
        r_glBindTexture(GL_TEXTURE_2D, t.tex);
        r_glTexParameteri(GL_TEXTURE_2D, MCL_GL_TEXTURE_TILING_EXT, MCL_GL_OPTIMAL_TILING_EXT);
        r_glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, floatFmt ? MCL_GL_RGBA16F : MCL_GL_RGBA8, w, h, t.mem, 0);
        r_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        r_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        r_glGenRenderbuffers(1, &t.depth);
        r_glBindRenderbuffer(GL_RENDERBUFFER, t.depth);
        r_glRenderbufferStorage(GL_RENDERBUFFER, MCL_GL_DEPTH24_STENCIL8, w, h);

        r_glGenFramebuffers(1, &t.fbo);
        r_glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
        r_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
        r_glFramebufferRenderbuffer(GL_FRAMEBUFFER, MCL_GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, t.depth);
        GLenum status = r_glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) { LOGE("FBO %u incompleto: 0x%x", i, status); return false; }

        r_glGenSemaphoresEXT(1, &t.semGl); r_glImportSemaphoreFdEXT(t.semGl, MCL_GL_HANDLE_TYPE_OPAQUE_FD_EXT, fds[i].glDoneFd);
        r_glGenSemaphoresEXT(1, &t.semVk); r_glImportSemaphoreFdEXT(t.semVk, MCL_GL_HANDLE_TYPE_OPAQUE_FD_EXT, fds[i].vkDoneFd);
    }
    gCur = 0; gTargetW = w; gTargetH = h;
    r_glBindTexture(GL_TEXTURE_2D, 0);
    r_glBindFramebuffer(GL_FRAMEBUFFER, gTargets[0].fbo);   // "framebuffer 0" inicial
    gDrawIsDefault = gReadIsDefault = true;
    return true;
}

bool createZinkContext(int major, int minor, bool core) {
    gGlLib = dlopen(nativeLib("libEGL_mesa.so").c_str(), RTLD_NOW | RTLD_LOCAL);   // LOCAL: no pisar el libEGL del sistema
    if (!gGlLib) { LOGE("No se pudo cargar Mesa: %s", dlerror()); return false; }
    M.GetProcAddress = (decltype(M.GetProcAddress))dlsym(gGlLib, "eglGetProcAddress");
    M.Initialize = (decltype(M.Initialize))dlsym(gGlLib, "eglInitialize");
    M.BindAPI = (decltype(M.BindAPI))dlsym(gGlLib, "eglBindAPI");
    M.ChooseConfig = (decltype(M.ChooseConfig))dlsym(gGlLib, "eglChooseConfig");
    M.CreateContext = (decltype(M.CreateContext))dlsym(gGlLib, "eglCreateContext");
    M.MakeCurrent = (decltype(M.MakeCurrent))dlsym(gGlLib, "eglMakeCurrent");
    M.QueryString = (decltype(M.QueryString))dlsym(gGlLib, "eglQueryString");
    M.GetPlatformDisplayEXT = (PFNEGLGETPLATFORMDISPLAYEXTPROC)M.GetProcAddress("eglGetPlatformDisplayEXT");
    if (!M.GetPlatformDisplayEXT) { LOGE("Mesa sin eglGetPlatformDisplayEXT"); return false; }

    gMesaDpy = M.GetPlatformDisplayEXT(MCL_EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    EGLint maj, min;
    if (!M.Initialize(gMesaDpy, &maj, &min)) { LOGE("eglInitialize (Mesa) falló"); return false; }
    LOGI("Mesa EGL %d.%d — %s", maj, min, M.QueryString(gMesaDpy, EGL_VENDOR));
    M.BindAPI(EGL_OPENGL_API);

    const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                              EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig cfg; EGLint n = 0;
    if (!M.ChooseConfig(gMesaDpy, cfgAttr, &cfg, 1, &n) || n == 0) { LOGE("Sin EGLConfig GL en Mesa"); return false; }
    const EGLint ctxAttr[] = {
        EGL_CONTEXT_MAJOR_VERSION_KHR, major, EGL_CONTEXT_MINOR_VERSION_KHR, minor,
        EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, core ? EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR : EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR,
        EGL_NONE};
    gMesaCtx = M.CreateContext(gMesaDpy, cfg, EGL_NO_CONTEXT, ctxAttr);
    if (gMesaCtx == EGL_NO_CONTEXT) { LOGE("eglCreateContext GL %d.%d falló", major, minor); return false; }
    if (!M.MakeCurrent(gMesaDpy, EGL_NO_SURFACE, EGL_NO_SURFACE, gMesaCtx)) { LOGE("MakeCurrent surfaceless falló"); return false; }
    loadMesaGl();

    // Presentador Vulkan sobre la ventana real
    ANativeWindow* win = waitForWindow();
    if (!win) { LOGE("Sin ventana"); return false; }
    auto& st = mcl::state();
    bool wantHdr = st.renderer == mcl::Renderer::ZINK_HDR && st.hdr.enabled;
    auto& pres = mcl::HdrPresenter::instance();
    if (!pres.init(win, wantHdr, st.hdr, true)) return false;

    // La memoria OPAQUE_FD solo se comparte dentro del mismo dispositivo/driver Vulkan
    uint8_t glUuid[16]{}, vkDev[16]{}, vkDrv[16]{};
    r_glGetUnsignedBytevEXT(MCL_GL_DEVICE_UUID_EXT, glUuid);
    pres.deviceUuids(vkDev, vkDrv);
    if (std::memcmp(glUuid, vkDev, 16) != 0) LOGE("¡UUID de dispositivo GL ≠ Vulkan! La importación de memoria puede fallar.");

    st.windowDirty = false;
    return createTargets(st.width, st.height);
}

void swapZink() {
    auto& st = mcl::state();
    auto& pres = mcl::HdrPresenter::instance();
    Target& t = gTargets[gCur];
    const GLenum layout = MCL_GL_LAYOUT_SHADER_READ_ONLY_EXT;
    r_glSignalSemaphoreEXT(t.semGl, 0, nullptr, 1, &t.tex, &layout);
    r_glFlush();
    pres.present(gCur);

    // Cambios de superficie (rotación, multiventana, vuelta del background)
    if (st.windowDirty.exchange(false)) {
        ANativeWindow* w; { std::lock_guard<std::mutex> l(st.mtx); w = st.window; }
        pres.onWindowResized(w);
        if (st.width != gTargetW || st.height != gTargetH) {
            for (uint32_t i = 0; i < gTargets.size(); ++i) pres.waitIdleForImage(i);
            destroyTargets();
            createTargets(st.width, st.height);
            return;
        }
    }

    gCur = (gCur + 1) % kTargets;
    Target& next = gTargets[gCur];
    if (!next.first) r_glWaitSemaphoreEXT(next.semVk, 0, nullptr, 1, &next.tex, &layout);   // Vulkan terminó de leerla
    next.first = false;
    // Rebind transparente si el juego tenía "el framebuffer 0" activo
    if (gDrawIsDefault) r_glBindFramebuffer(MCL_GL_DRAW_FRAMEBUFFER, next.fbo);
    if (gReadIsDefault) r_glBindFramebuffer(MCL_GL_READ_FRAMEBUFFER, next.fbo);
}

// ---------------- GL4ES ----------------
bool createGl4esContext() {
    gGlLib = dlopen(nativeLib("libgl4es_114.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!gGlLib) { LOGE("No se pudo cargar gl4es: %s", dlerror()); return false; }
    gGl4esGetProc = (GetProcFn)dlsym(gGlLib, "glXGetProcAddress");
    if (!gGl4esGetProc) gGl4esGetProc = (GetProcFn)dlsym(gGlLib, "gl4es_GetProcAddress");

    ANativeWindow* win = waitForWindow();
    if (!win) return false;
    gSysDpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(gSysDpy, nullptr, nullptr);
    const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                              EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                              EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8, EGL_NONE};
    EGLint n = 0;
    if (!eglChooseConfig(gSysDpy, cfgAttr, &gSysCfg, 1, &n) || n == 0) { LOGE("Sin EGLConfig GLES3"); return false; }
    gSysSurf = eglCreateWindowSurface(gSysDpy, gSysCfg, win, nullptr);
    const EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    gSysCtx = eglCreateContext(gSysDpy, gSysCfg, EGL_NO_CONTEXT, ctxAttr);
    if (gSysSurf == EGL_NO_SURFACE || gSysCtx == EGL_NO_CONTEXT) { LOGE("EGL GLES3 falló: 0x%x", eglGetError()); return false; }
    return eglMakeCurrent(gSysDpy, gSysSurf, gSysSurf, gSysCtx) == EGL_TRUE;
}

void swapGl4es() {
    auto& st = mcl::state();
    if (st.windowDirty.exchange(false)) {
        ANativeWindow* w; { std::lock_guard<std::mutex> l(st.mtx); w = st.window; }
        eglMakeCurrent(gSysDpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (gSysSurf != EGL_NO_SURFACE) eglDestroySurface(gSysDpy, gSysSurf);
        gSysSurf = EGL_NO_SURFACE;
        if (!w) w = waitForWindow();   // app en segundo plano: esperar
        gSysSurf = eglCreateWindowSurface(gSysDpy, gSysCfg, w, nullptr);
        eglMakeCurrent(gSysDpy, gSysSurf, gSysSurf, gSysCtx);
    }
    eglSwapBuffers(gSysDpy, gSysSurf);
}

}  // namespace

// ======================= API exportada =======================

MCL_EXPORT int mcl_create_context(int glMajor, int glMinor, int coreProfile) {
    const char* r = getenv("MCL_RENDERER");
    bool zink = r && std::strncmp(r, "zink", 4) == 0;
    gBackend = zink ? Backend::ZINK : Backend::GL4ES;
    LOGI("Creando contexto GL %d.%d %s con %s", glMajor, glMinor, coreProfile ? "core" : "compat", zink ? "Zink" : "gl4es");
    bool ok = zink ? createZinkContext(glMajor > 0 ? glMajor : 4, glMajor > 0 ? glMinor : 6, coreProfile != 0) : createGl4esContext();
    if (!ok) gBackend = Backend::NONE;
    gLastSwapEnd = nowNs();
    return ok ? 1 : 0;
}

MCL_EXPORT int mcl_make_current(int current) {
    if (gBackend == Backend::ZINK) return M.MakeCurrent(gMesaDpy, EGL_NO_SURFACE, EGL_NO_SURFACE, current ? gMesaCtx : EGL_NO_CONTEXT);
    if (gBackend == Backend::GL4ES) return current ? eglMakeCurrent(gSysDpy, gSysSurf, gSysSurf, gSysCtx)
                                                   : eglMakeCurrent(gSysDpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return 0;
}

MCL_EXPORT void mcl_swap_buffers(void) {
    const int64_t workNs = nowNs() - gLastSwapEnd;   // tiempo de CPU del frame (antes de bloquear en vsync)
    if (gBackend == Backend::ZINK) swapZink();
    else if (gBackend == Backend::GL4ES) swapGl4es();
    mcl::notifyFrameWork(workNs);
    gLastSwapEnd = nowNs();
}

MCL_EXPORT void mcl_swap_interval(int interval) {
    if (gBackend == Backend::GL4ES) eglSwapInterval(gSysDpy, interval);
    // Zink: el ritmo lo fija el modo de presentación del swapchain (FIFO/MAILBOX)
}

MCL_EXPORT void* mcl_get_proc_address(const char* name) {
    if (!name) return nullptr;
    if (gBackend == Backend::ZINK) {
        if (!std::strcmp(name, "glBindFramebuffer") || !std::strcmp(name, "glBindFramebufferEXT")) return (void*)hook_glBindFramebuffer;
        if (!std::strcmp(name, "glGetIntegerv")) return (void*)hook_glGetIntegerv;
        if (!std::strcmp(name, "glDrawBuffer")) return (void*)hook_glDrawBuffer;
        if (!std::strcmp(name, "glReadBuffer")) return (void*)hook_glReadBuffer;
        return (void*)M.GetProcAddress(name);
    }
    if (gBackend == Backend::GL4ES || gGlLib) {
        if (gGl4esGetProc) if (void* p = gGl4esGetProc(name)) return p;
        return dlsym(gGlLib, name);
    }
    return nullptr;
}

MCL_EXPORT void* glXGetProcAddress(const unsigned char* name) { return mcl_get_proc_address(reinterpret_cast<const char*>(name)); }
MCL_EXPORT void* glXGetProcAddressARB(const unsigned char* name) { return mcl_get_proc_address(reinterpret_cast<const char*>(name)); }
