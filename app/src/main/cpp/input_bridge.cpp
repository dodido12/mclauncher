// Cola de eventos de entrada Android → shim GLFW (hilo del juego).
#include "mcl_native.h"
#include <array>
#include <cstring>
#include <deque>

namespace {
std::mutex gQ;
std::deque<mcl_event> gEvents;
constexpr size_t kMaxEvents = 4096;

double gCursorX = 0, gCursorY = 0;

std::mutex gPad;
struct Pad { bool connected = false; unsigned char b[15]{}; float a[6]{0, 0, 0, 0, -1, -1}; };
std::array<Pad, 4> gPads;
}  // namespace

namespace mcl::input {

void push(const mcl_event& e) {
    std::lock_guard<std::mutex> l(gQ);
    if (gEvents.size() >= kMaxEvents) gEvents.pop_front();   // nunca bloquear al hilo de UI
    gEvents.push_back(e);
}

void key(int k, int sc, int action, int mods) { push({MCL_EV_KEY, k, sc, action, mods, 0, 0}); }
void character(int cp, int mods) { push({MCL_EV_CHAR, cp, mods, 0, 0, 0, 0}); }
void mouseButton(int b, int action, int mods) { push({MCL_EV_MOUSE_BUTTON, b, action, mods, 0, 0, 0}); }

void cursorPos(double x, double y) {
    { std::lock_guard<std::mutex> l(gQ); gCursorX = x; gCursorY = y; }
    push({MCL_EV_CURSOR_POS, 0, 0, 0, 0, x, y});
}

// Con el cursor capturado el juego solo mira la diferencia entre posiciones,
// así que mantenemos un cursor virtual sin límites.
void cursorDelta(double dx, double dy) {
    double x, y;
    {
        std::lock_guard<std::mutex> l(gQ);
        gCursorX += dx; gCursorY += dy;
        if (!mcl::state().cursorGrabbed) {   // en menús: limitar a la ventana
            int w = mcl::state().width, h = mcl::state().height;
            if (gCursorX < 0) gCursorX = 0; if (gCursorY < 0) gCursorY = 0;
            if (w > 0 && gCursorX > w - 1) gCursorX = w - 1;
            if (h > 0 && gCursorY > h - 1) gCursorY = h - 1;
        }
        x = gCursorX; y = gCursorY;
    }
    push({MCL_EV_CURSOR_POS, 0, 0, 0, 0, x, y});
}

void scroll(double dx, double dy) { push({MCL_EV_SCROLL, 0, 0, 0, 0, dx, dy}); }
void framebufferSize(int w, int h) { push({MCL_EV_FRAMEBUFFER_SIZE, w, h, 0, 0, 0, 0}); }

void gamepad(int jid, const unsigned char* b, const float* a, bool connected) {
    if (jid < 0 || jid >= (int)gPads.size()) return;
    std::lock_guard<std::mutex> l(gPad);
    gPads[jid].connected = connected;
    std::memcpy(gPads[jid].b, b, 15);
    std::memcpy(gPads[jid].a, a, sizeof(float) * 6);
}
}  // namespace mcl::input

MCL_EXPORT int mcl_poll_event(mcl_event* out) {
    std::lock_guard<std::mutex> l(gQ);
    if (gEvents.empty()) return 0;
    *out = gEvents.front(); gEvents.pop_front();
    return 1;
}

MCL_EXPORT void mcl_get_window_size(int* w, int* h) { *w = mcl::state().width; *h = mcl::state().height; }
MCL_EXPORT void mcl_get_cursor_pos(double* x, double* y) { std::lock_guard<std::mutex> l(gQ); *x = gCursorX; *y = gCursorY; }
MCL_EXPORT void mcl_set_cursor_grabbed(int g) { mcl::state().cursorGrabbed = g != 0; }
MCL_EXPORT void mcl_set_cursor_pos(double x, double y) { std::lock_guard<std::mutex> l(gQ); gCursorX = x; gCursorY = y; }
MCL_EXPORT float mcl_get_refresh_rate(void) { return mcl::state().targetHz.load(); }

MCL_EXPORT int mcl_get_gamepad_state(int jid, unsigned char buttons[15], float axes[6]) {
    if (jid < 0 || jid >= (int)gPads.size()) return 0;
    std::lock_guard<std::mutex> l(gPad);
    std::memcpy(buttons, gPads[jid].b, 15);
    std::memcpy(axes, gPads[jid].a, sizeof(float) * 6);
    return gPads[jid].connected ? 1 : 0;
}
