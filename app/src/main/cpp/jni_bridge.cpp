// JNI de NativeBridge (launch) y LwjglBridge (input). Corre en el proceso :game (ART).
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <unistd.h>
#include <string>
#include <vector>
#include "mcl_native.h"
#include "vk_hdr_presenter.h"

#define LOG_TAG "mcl_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace mcl {
State& state() { static State s; return s; }

// Implementado en jvm_launcher.cpp
int launchJvm(const char* javaHome, const char* libjvm, const std::vector<std::string>& preload,
              const std::vector<std::string>& env, const std::vector<std::string>& jvmArgs,
              const char* mainClass, const std::vector<std::string>& gameArgs, const char* workDir);
}  // namespace mcl

namespace {
JavaVM* gArtVm = nullptr;        // la VM de ART (no la de HotSpot)
jobject gCallbacks = nullptr;
jmethodID gOnRenderStarted, gOnFrameWork, gOnExited, gSetClip, gGetClip;
std::string gClipboard;
std::mutex gClipMtx;

JNIEnv* artEnv() {
    JNIEnv* env = nullptr;
    if (gArtVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
        gArtVm->AttachCurrentThread(&env, nullptr);
    return env;
}

std::vector<std::string> toVec(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    jsize n = env->GetArrayLength(arr);
    out.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        const char* c = env->GetStringUTFChars(s, nullptr);
        out.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    return out;
}

struct JStr {
    JNIEnv* e; jstring s; const char* c;
    JStr(JNIEnv* e, jstring s) : e(e), s(s), c(e->GetStringUTFChars(s, nullptr)) {}
    ~JStr() { e->ReleaseStringUTFChars(s, c); }
};
}  // namespace

void mcl::notifyRenderThreadStarted(int tid) {
    if (!gCallbacks) return;
    JNIEnv* env = artEnv();
    jintArray a = env->NewIntArray(1);
    env->SetIntArrayRegion(a, 0, 1, &tid);
    env->CallVoidMethod(gCallbacks, gOnRenderStarted, a);
    env->DeleteLocalRef(a);
}
void mcl::notifyFrameWork(int64_t ns) { if (gCallbacks) artEnv()->CallVoidMethod(gCallbacks, gOnFrameWork, (jlong)ns); }
void mcl::notifyGameExited(int code) { if (gCallbacks) artEnv()->CallVoidMethod(gCallbacks, gOnExited, (jint)code); }

MCL_EXPORT void mcl_set_clipboard(const char* utf8) {
    if (!gCallbacks) return;
    JNIEnv* env = artEnv();
    jstring s = env->NewStringUTF(utf8 ? utf8 : "");
    env->CallVoidMethod(gCallbacks, gSetClip, s);
    env->DeleteLocalRef(s);
}

MCL_EXPORT const char* mcl_get_clipboard(void) {
    std::lock_guard<std::mutex> l(gClipMtx);
    gClipboard.clear();
    if (gCallbacks) {
        JNIEnv* env = artEnv();
        auto s = static_cast<jstring>(env->CallObjectMethod(gCallbacks, gGetClip));
        if (s) {
            const char* c = env->GetStringUTFChars(s, nullptr);
            gClipboard = c;
            env->ReleaseStringUTFChars(s, c);
            env->DeleteLocalRef(s);
        }
    }
    return gClipboard.c_str();
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) { gArtVm = vm; return JNI_VERSION_1_6; }

#define NB(name) Java_com_example_mclauncher_launch_NativeBridge_##name
#define LB(name) Java_com_example_mclauncher_input_LwjglBridge_##name

extern "C" {

JNIEXPORT void JNICALL NB(setCallbacks)(JNIEnv* env, jclass, jobject cb) {
    if (gCallbacks) env->DeleteGlobalRef(gCallbacks);
    gCallbacks = env->NewGlobalRef(cb);
    jclass c = env->GetObjectClass(cb);
    gOnRenderStarted = env->GetMethodID(c, "onRenderThreadStarted", "([I)V");
    gOnFrameWork = env->GetMethodID(c, "onFrameWork", "(J)V");
    gOnExited = env->GetMethodID(c, "onGameExited", "(I)V");
    gSetClip = env->GetMethodID(c, "setClipboard", "(Ljava/lang/String;)V");
    gGetClip = env->GetMethodID(c, "getClipboard", "()Ljava/lang/String;");
}

JNIEXPORT void JNICALL NB(setSurface)(JNIEnv* env, jclass, jobject surface, jint renderer, jint w, jint h) {
    auto& s = mcl::state();
    std::lock_guard<std::mutex> l(s.mtx);
    if (s.window) { ANativeWindow_release(s.window); s.window = nullptr; }
    if (surface) s.window = ANativeWindow_fromSurface(env, surface);
    s.renderer = static_cast<mcl::Renderer>(renderer);
    s.width = w; s.height = h;
    s.windowDirty = true;
    if (w > 0 && h > 0) mcl::input::framebufferSize(w, h);
    LOGI("setSurface %p renderer=%d %dx%d", s.window, renderer, w, h);
}

JNIEXPORT void JNICALL NB(setHdrParams)(JNIEnv*, jclass, jboolean en, jint mode, jfloat pw, jfloat peak, jfloat minN, jfloat maxFall) {
    auto& s = mcl::state();
    std::lock_guard<std::mutex> l(s.mtx);
    s.hdr.enabled = en; s.hdr.mode = static_cast<mcl::HdrMode>(mode);
    s.hdr.paperWhiteNits = pw; s.hdr.peakNits = peak; s.hdr.minNits = minN > 0 ? minN : 0.005f; s.hdr.maxFall = maxFall;
    mcl::HdrPresenter::instance().onHdrParamsChanged(s.hdr);
}

JNIEXPORT void JNICALL NB(updateHdrHeadroom)(JNIEnv*, jclass, jfloat ratio, jfloat effPeak) {
    auto& s = mcl::state();
    std::lock_guard<std::mutex> l(s.mtx);
    s.hdr.hdrSdrRatio = ratio;
    mcl::HdrPresenter::instance().onHeadroomChanged(ratio, effPeak);
}

JNIEXPORT void JNICALL NB(setTargetFrameRate)(JNIEnv*, jclass, jfloat hz) { mcl::state().targetHz = hz; }

JNIEXPORT void JNICALL NB(onVsync)(JNIEnv*, jclass, jlong t, jlong period) {
    mcl::state().lastVsyncNs = t; mcl::state().vsyncPeriodNs = period;
}

JNIEXPORT jint JNICALL NB(launchJvm)(JNIEnv* env, jclass, jstring javaHome, jstring libjvm, jobjectArray preload,
                                     jobjectArray envVars, jobjectArray jvmArgs, jstring mainClass, jobjectArray gameArgs, jstring workDir) {
    JStr home(env, javaHome), jvm(env, libjvm), main(env, mainClass), wd(env, workDir);
    auto pre = toVec(env, preload), ev = toVec(env, envVars), ja = toVec(env, jvmArgs), ga = toVec(env, gameArgs);
    mcl::notifyRenderThreadStarted(gettid());   // el hilo principal de MC es también el de render
    return mcl::launchJvm(home.c, jvm.c, pre, ev, ja, main.c, ga, wd.c);
}

// ---------------- LwjglBridge ----------------
JNIEXPORT void JNICALL LB(sendKey)(JNIEnv*, jclass, jint k, jint sc, jint a, jint m) { mcl::input::key(k, sc, a, m); }
JNIEXPORT void JNICALL LB(sendChar)(JNIEnv*, jclass, jint cp, jint m) { mcl::input::character(cp, m); }
JNIEXPORT void JNICALL LB(sendMouseButton)(JNIEnv*, jclass, jint b, jint a, jint m) { mcl::input::mouseButton(b, a, m); }
JNIEXPORT void JNICALL LB(sendCursorPos)(JNIEnv*, jclass, jdouble x, jdouble y) { mcl::input::cursorPos(x, y); }
JNIEXPORT void JNICALL LB(sendCursorDelta)(JNIEnv*, jclass, jdouble dx, jdouble dy) { mcl::input::cursorDelta(dx, dy); }
JNIEXPORT void JNICALL LB(sendScroll)(JNIEnv*, jclass, jdouble dx, jdouble dy) { mcl::input::scroll(dx, dy); }
JNIEXPORT jboolean JNICALL LB(isCursorGrabbed)(JNIEnv*, jclass) { return mcl::state().cursorGrabbed.load(); }

JNIEXPORT void JNICALL LB(sendGamepadState)(JNIEnv* env, jclass, jint jid, jbyteArray b, jfloatArray a, jboolean c) {
    unsigned char buttons[15]{}; float axes[6]{};
    env->GetByteArrayRegion(b, 0, 15, reinterpret_cast<jbyte*>(buttons));
    env->GetFloatArrayRegion(a, 0, 6, axes);
    mcl::input::gamepad(jid, buttons, axes, c);
}

JNIEXPORT jintArray JNICALL LB(windowSize)(JNIEnv* env, jclass) {
    jint v[2] = {mcl::state().width, mcl::state().height};
    jintArray r = env->NewIntArray(2);
    env->SetIntArrayRegion(r, 0, 2, v);
    return r;
}
}  // extern "C"
