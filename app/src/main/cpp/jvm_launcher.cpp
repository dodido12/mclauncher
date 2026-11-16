// Arranque de HotSpot (OpenJDK 8/17/21/25 aarch64 para Android) dentro del proceso :game.
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>
#include "mcl_native.h"

#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008   // el jni.h del NDK llega solo a 1_6
#endif

#define LOG_TAG "mcl_jvm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// stdout/stderr de la JVM → logcat (tag "Minecraft") y a latestlog del launcher
void redirectStdioToLogcat() {
    static bool done = false;
    if (done) return;
    done = true;
    int fds[2];
    if (pipe(fds) != 0) return;
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    std::thread([rd = fds[0]] {
        char buf[2048]; std::string line;
        ssize_t n;
        while ((n = read(rd, buf, sizeof(buf))) > 0) {
            for (ssize_t i = 0; i < n; ++i) {
                if (buf[i] == '\n') { __android_log_write(ANDROID_LOG_INFO, "Minecraft", line.c_str()); line.clear(); }
                else line.push_back(buf[i]);
            }
        }
    }).detach();
}

// Hooks de HotSpot: vfprintf → logcat, exit → avisar a Kotlin antes de morir
jint JNICALL jvmVfprintf(FILE*, const char* fmt, va_list ap) {
    __android_log_vprint(ANDROID_LOG_INFO, "HotSpot", fmt, ap);
    return 0;
}
void JNICALL jvmExit(jint code) {
    LOGI("System.exit(%d)", code);
    mcl::notifyGameExited(code);
    _exit(code);
}
void JNICALL jvmAbort() { LOGE("JVM abort()"); mcl::notifyGameExited(-1); _exit(134); }

std::string dotsToSlashes(std::string s) { for (auto& c : s) if (c == '.') c = '/'; return s; }

}  // namespace

namespace mcl {

int launchJvm(const char* javaHome, const char* libjvmPath, const std::vector<std::string>& preload,
              const std::vector<std::string>& env, const std::vector<std::string>& jvmArgs,
              const char* mainClass, const std::vector<std::string>& gameArgs, const char* workDir) {
    redirectStdioToLogcat();
    for (const auto& kv : env) {
        auto eq = kv.find('=');
        if (eq != std::string::npos) setenv(kv.substr(0, eq).c_str(), kv.substr(eq + 1).c_str(), 1);
    }
    if (chdir(workDir) != 0) LOGE("chdir(%s) falló", workDir);

    // 1) Precargas en orden por ruta absoluta (el linker de Android no busca en files/runtimes):
    //    dependencias de Termux (libandroid-spawn, libz.so.1…) → libjvm → libjava, libnet, …
    //    RTLD_GLOBAL para que los DT_NEEDED posteriores se resuelvan por soname.
    for (const auto& p : preload) {
        if (!dlopen(p.c_str(), RTLD_NOW | RTLD_GLOBAL)) LOGI("precarga %s: %s", p.c_str(), dlerror());
    }
    // 2) Handle explícito de libjvm para no tomar el JNI_CreateJavaVM de ART
    void* jvm = dlopen(libjvmPath, RTLD_NOW | RTLD_GLOBAL);
    if (!jvm) { LOGE("dlopen libjvm: %s", dlerror()); return -10; }

    using CreateVmFn = jint (*)(JavaVM**, void**, void*);
    auto createVm = reinterpret_cast<CreateVmFn>(dlsym(jvm, "JNI_CreateJavaVM"));
    if (!createVm) { LOGE("JNI_CreateJavaVM no encontrado"); return -11; }

    std::vector<JavaVMOption> opts;
    opts.reserve(jvmArgs.size() + 3);
    std::vector<std::string> owned;      // HotSpot no copia las cadenas de -cp separados
    owned.reserve(jvmArgs.size());
    for (size_t i = 0; i < jvmArgs.size(); ++i) {
        const std::string& a = jvmArgs[i];
        // "-cp X" / "-classpath X" son del launcher `java`, no de JNI: convertir a propiedad
        if ((a == "-cp" || a == "-classpath") && i + 1 < jvmArgs.size()) {
            owned.push_back("-Djava.class.path=" + jvmArgs[++i]);
            opts.push_back({const_cast<char*>(owned.back().c_str()), nullptr});
            continue;
        }
        opts.push_back({const_cast<char*>(a.c_str()), nullptr});
    }
    opts.push_back({const_cast<char*>("vfprintf"), reinterpret_cast<void*>(jvmVfprintf)});
    opts.push_back({const_cast<char*>("exit"), reinterpret_cast<void*>(jvmExit)});
    opts.push_back({const_cast<char*>("abort"), reinterpret_cast<void*>(jvmAbort)});

    JavaVMInitArgs init{};
    init.version = JNI_VERSION_1_8;   // aceptado por 8 → 25
    init.nOptions = static_cast<jint>(opts.size());
    init.options = opts.data();
    init.ignoreUnrecognized = JNI_TRUE;   // flags de Java 25 no rompen Java 8

    LOGI("Creando JVM (%s) con %zu opciones, main=%s", javaHome, opts.size(), mainClass);
    JavaVM* vm = nullptr; JNIEnv* jenv = nullptr;
    jint rc = createVm(&vm, reinterpret_cast<void**>(&jenv), &init);
    if (rc != JNI_OK) { LOGE("JNI_CreateJavaVM rc=%d", rc); return -12; }

    jclass mainCls = jenv->FindClass(dotsToSlashes(mainClass).c_str());
    if (!mainCls) { jenv->ExceptionDescribe(); vm->DestroyJavaVM(); return -13; }
    jmethodID mainMid = jenv->GetStaticMethodID(mainCls, "main", "([Ljava/lang/String;)V");
    if (!mainMid) { jenv->ExceptionDescribe(); vm->DestroyJavaVM(); return -14; }

    jclass strCls = jenv->FindClass("java/lang/String");
    jobjectArray args = jenv->NewObjectArray(static_cast<jsize>(gameArgs.size()), strCls, nullptr);
    for (size_t i = 0; i < gameArgs.size(); ++i) {
        jstring s = jenv->NewStringUTF(gameArgs[i].c_str());
        jenv->SetObjectArrayElement(args, static_cast<jsize>(i), s);
        jenv->DeleteLocalRef(s);
    }

    jenv->CallStaticVoidMethod(mainCls, mainMid, args);
    int code = 0;
    if (jenv->ExceptionCheck()) { jenv->ExceptionDescribe(); code = 1; }
    // DestroyJavaVM espera a los hilos no-daemon (servidor integrado, etc.)
    vm->DestroyJavaVM();
    LOGI("JVM terminada, código %d", code);
    return code;
}

}  // namespace mcl
