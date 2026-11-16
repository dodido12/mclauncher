/* Stub: libcutils no es API pública del NDK. Mesa solo lo usa para atrace. */
#pragma once
#define ATRACE_TAG_GRAPHICS (1 << 1)
static inline void atrace_begin(unsigned long long tag, const char* name) { (void)tag; (void)name; }
static inline void atrace_end(unsigned long long tag) { (void)tag; }
static inline void atrace_init(void) {}
