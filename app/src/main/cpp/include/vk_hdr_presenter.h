// Presentador Vulkan: swapchain 10 bits HDR10 (A2B10G10R10 + ST2084) o SDR, que compone
// la imagen que Zink (Mesa GL sobre Vulkan) renderiza en imágenes compartidas por FD.
#pragma once
#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <array>
#include <vector>
#include "mcl_native.h"

namespace mcl {

/** Imagen compartida GL↔Vulkan (memoria + semáforos exportados como OPAQUE_FD). */
struct SharedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkDescriptorSet descriptor = VK_NULL_HANDLE;
    VkSemaphore glDone = VK_NULL_HANDLE;    // GL señala → Vulkan espera
    VkSemaphore vkDone = VK_NULL_HANDLE;    // Vulkan señala → GL espera antes de reescribir
    VkFence inFlight = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkDeviceSize allocationSize = 0;
    bool firstUse = true;
};

/** Lo que gl_loader necesita para importar en GL (GL_EXT_memory_object_fd / semaphore_fd). */
struct SharedImageFds { int memoryFd = -1; uint64_t size = 0; int glDoneFd = -1; int vkDoneFd = -1; };

class HdrPresenter {
public:
    static HdrPresenter& instance();

    bool init(ANativeWindow* window, bool wantHdr, const HdrParams& p, bool lowLatency);
    void shutdown();
    bool isHdrActive() const { return hdrActive_; }

    /** Crea N imágenes del tamaño de render. format: R16G16B16A16_SFLOAT (lineal) o R8G8B8A8_UNORM. */
    bool createSharedImages(uint32_t count, uint32_t w, uint32_t h, bool floatFormat, std::vector<SharedImageFds>& out);
    /** UUID de dispositivo/driver para verificar contra GL_DEVICE_UUID_EXT de Zink. */
    void deviceUuids(uint8_t device[VK_UUID_SIZE], uint8_t driver[VK_UUID_SIZE]) const;

    /** Compone y presenta la imagen `index` (GL ya señaló glDone). */
    bool present(uint32_t index);
    void waitIdleForImage(uint32_t index);

    void onWindowResized(ANativeWindow* w);
    void onHdrParamsChanged(const HdrParams& p);
    void onHeadroomChanged(float ratio, float effectivePeak);

private:
    HdrPresenter() = default;
    bool createInstance();
    bool pickDevice();
    bool createSwapchain();
    void destroySwapchain();
    bool createPipeline();
    void applyHdrMetadata();
    uint32_t findMemoryType(uint32_t bits, VkMemoryPropertyFlags flags) const;

    ANativeWindow* window_ = nullptr;
    VkInstance inst_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice gpu_ = VK_NULL_HANDLE;
    VkDevice dev_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkCommandPool pool_ = VK_NULL_HANDLE;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapFormat_ = VK_FORMAT_UNDEFINED;
    VkColorSpaceKHR swapColorSpace_ = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    VkExtent2D extent_{};
    VkSurfaceTransformFlagBitsKHR transform_ = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    std::vector<VkImage> swapImages_;
    std::vector<VkImageView> swapViews_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<VkSemaphore> acquireSems_, renderDoneSems_;
    uint32_t acquireIdx_ = 0;

    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkFormat renderPassFormat_ = VK_FORMAT_UNDEFINED;
    VkDescriptorSetLayout dsl_ = VK_NULL_HANDLE;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkSampler sampler_ = VK_NULL_HANDLE;

    std::vector<SharedImage> shared_;
    bool sharedIsFloat_ = false;

    bool wantHdr_ = false, hdrActive_ = false, lowLatency_ = true;
    bool hasColorspaceExt_ = false, hasHdrMetadata_ = false;
    HdrParams params_{};
    uint8_t deviceUuid_[VK_UUID_SIZE]{}, driverUuid_[VK_UUID_SIZE]{};

    PFN_vkGetMemoryFdKHR pGetMemoryFd_ = nullptr;
    PFN_vkGetSemaphoreFdKHR pGetSemaphoreFd_ = nullptr;
    PFN_vkSetHdrMetadataEXT pSetHdrMetadata_ = nullptr;
};

}  // namespace mcl
