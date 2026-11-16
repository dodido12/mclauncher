/* Stub: property_get de libcutils implementado con la API pública __system_property_get. */
#pragma once
#include <string.h>
#include <sys/system_properties.h>
#define PROPERTY_KEY_MAX 32
#define PROPERTY_VALUE_MAX 92
static inline int property_get(const char* key, char* value, const char* def) {
    int n = __system_property_get(key, value);
    if (n <= 0 && def) { strncpy(value, def, PROPERTY_VALUE_MAX - 1); value[PROPERTY_VALUE_MAX - 1] = 0; n = (int)strlen(value); }
    return n;
}
