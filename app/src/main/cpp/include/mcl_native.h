// Estado compartido de libmcl_native y API C exportada para el shim GLFW de LWJGL.
#pragma once
#include <android/native_window.h>
#include <atomic>
#include <cstdint>
#include <mutex>

#define MCL_EXPORT extern "C" __attribute__((visibility("default")))

namespace mcl {

enum class Renderer : int { GL4ES = 0, ZINK = 1, ZINK_HDR = 2 };
enum class HdrMode : int { INVERSE_TONEMAP = 0, SHADER_LINEAR = 1 };

struct HdrParams {
    bool enabled = false;
    HdrMode mode = HdrMode::INVERSE_TONEMAP;
    float paperWhiteNits = 203.f;
    float peakNits = 1000.f;
    float minNits = 0.005f;
    float maxFall = 400.f;
    float hdrSdrRatio = 1.f;   // headroom actual que permite el sistema (API 34+)
};

struct State {
    std::mutex mtx;
    ANativeWindow* window = nullptr;   // ref adquirida con ANativeWindow_fromSurface
    std::atomic<int> width{0}, height{0};
    std::atomic<bool> windowDirty{false};
    Renderer renderer = Renderer::GL4ES;
    HdrParams hdr;
    std::atomic<float> targetHz{60.f};
    std::atomic<int64_t> lastVsyncNs{0}, vsyncPeriodNs{16'666'667};
    std::atomic<bool> cursorGrabbed{false};
};

State& state();

// Callbacks hacia Kotlin (implementados en jni_bridge.cpp)
void notifyRenderThreadStarted(int tid);
void notifyFrameWork(int64_t ns);
void notifyGameExited(int code);

}  // namespace mcl

// ---------- Eventos de entrada (consumidos por el shim GLFW) ----------
enum mcl_event_type : int32_t {
    MCL_EV_KEY = 1, MCL_EV_CHAR = 2, MCL_EV_MOUSE_BUTTON = 3,
    MCL_EV_CURSOR_POS = 4, MCL_EV_SCROLL = 5, MCL_EV_FRAMEBUFFER_SIZE = 6, MCL_EV_FOCUS = 7
};

struct mcl_event {
    int32_t type;
    int32_t i0, i1, i2, i3;   // key/scancode/action/mods | button/action/mods | codepoint/mods | w/h
    double d0, d1;            // x/y | dx/dy
};

MCL_EXPORT int  mcl_poll_event(mcl_event* out);                 // 1 si había evento
MCL_EXPORT void mcl_get_window_size(int* w, int* h);
MCL_EXPORT void mcl_get_cursor_pos(double* x, double* y);
MCL_EXPORT void mcl_set_cursor_grabbed(int grabbed);
MCL_EXPORT void mcl_set_cursor_pos(double x, double y);       // sin evento (glfwSetCursorPos)
MCL_EXPORT float mcl_get_refresh_rate(void);
MCL_EXPORT void mcl_set_clipboard(const char* utf8);
MCL_EXPORT const char* mcl_get_clipboard(void);               // válido hasta la próxima llamada
MCL_EXPORT int  mcl_get_gamepad_state(int jid, unsigned char buttons[15], float axes[6]);

// ---------- Contexto GL / presentación (gl_loader.cpp) ----------
MCL_EXPORT int   mcl_create_context(int glMajor, int glMinor, int coreProfile);
MCL_EXPORT int   mcl_make_current(int current);
MCL_EXPORT void  mcl_swap_buffers(void);
MCL_EXPORT void  mcl_swap_interval(int interval);
MCL_EXPORT void* mcl_get_proc_address(const char* name);
// LWJGL (Linux) busca estas dos en la "libGL" configurada:
MCL_EXPORT void* glXGetProcAddress(const unsigned char* name);
MCL_EXPORT void* glXGetProcAddressARB(const unsigned char* name);

// Uso interno de input_bridge.cpp
namespace mcl::input {
void push(const mcl_event& e);
void key(int key, int scancode, int action, int mods);
void character(int cp, int mods);
void mouseButton(int b, int action, int mods);
void cursorPos(double x, double y);
void cursorDelta(double dx, double dy);
void scroll(double dx, double dy);
void gamepad(int jid, const unsigned char* buttons, const float* axes, bool connected);
void framebufferSize(int w, int h);
}
