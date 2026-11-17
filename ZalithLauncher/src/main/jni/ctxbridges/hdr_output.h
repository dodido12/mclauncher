// Salida HDR (scRGB FP16) para el bridge OSMesa. Parte de MCLauncher (GPLv3).
#ifndef MCL_HDR_OUTPUT_H
#define MCL_HDR_OUTPUT_H
#include <android/native_window.h>
#include <stdbool.h>
#include <stdint.h>

/** true si MCL_HDR=1 (lo decide el launcher según la instancia y el panel). */
bool hdr_enabled(void);
/** Pone la ventana en R16G16B16A16_FLOAT + ADATASPACE_SCRGB_LINEAR. */
void hdr_configure_window(struct ANativeWindow* w);
/** Buffer RGBA8 (stride = w) donde dibuja OSMesa; se recrea si cambia el tamaño. */
uint8_t* hdr_source_buffer(int w, int h);
int hdr_source_width(void);
int hdr_source_height(void);
/** Convierte el buffer de origen al buffer bloqueado de la ventana. */
void hdr_present(const ANativeWindow_Buffer* dst);

#endif
