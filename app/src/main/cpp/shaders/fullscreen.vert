#version 450
// Triángulo a pantalla completa sin vertex buffer + rotación de preTransform.
layout(push_constant) uniform PC {
    vec4 rot;        // matriz 2x2 (preTransform del swapchain)
    vec4 hdr;        // x=paperWhite nits, y=peak nits, z=headroom ratio, w=minNits
    ivec4 modes;     // x=hdrMode (0 inverse,1 linear), y=swapchainIsHdr, z=inputIsFloat
} pc;
layout(location = 0) out vec2 vUv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vUv = vec2(p.x, 1.0 - p.y);          // GL guarda el origen abajo-izquierda
    vec2 ndc = p * 2.0 - 1.0;
    ndc = mat2(pc.rot.xy, pc.rot.zw) * ndc;
    gl_Position = vec4(ndc, 0.0, 1.0);
}
