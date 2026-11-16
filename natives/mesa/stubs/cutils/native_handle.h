/* Stub de <cutils/native_handle.h> (estructura idéntica a AOSP, solo para tipos). */
#pragma once
typedef struct native_handle {
    int version;   /* sizeof(native_handle_t) */
    int numFds;
    int numInts;
    int data[0];
} native_handle_t;
