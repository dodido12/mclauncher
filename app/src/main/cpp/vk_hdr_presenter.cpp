// Presentador Vulkan HDR10 / SDR. Ver include/vk_hdr_presenter.h
#include "vk_hdr_presenter.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>

#define LOG_TAG "mcl_vk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define VKCHECK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { LOGE("%s = %d (%s:%d)", #x, r_, __FILE__, __LINE__); return false; } } while (0)

namespace {
// SPIR-V generado en tiempo de compilación por glslc del NDK (ver CMakeLists.txt)
const uint32_t kVertSpv[] =
#include "fullscreen.vert.inc"
;
const uint32_t kFragSpv[] =
#include "present_hdr.frag.inc"
;

struct PushConstants {
    float rot[4];
    float hdr[4];     // paperWhite, peak, headroom, minNits
    int32_t modes[4]; // hdrMode, swapchainIsHdr, inputIsFloat, 0
};
static_assert(sizeof(PushConstants) == 48);

bool hasExt(const std::vector<VkExtensionProperties>& v, const char* n) {
    return std::any_of(v.begin(), v.end(), [&](auto& e) { return std::strcmp(e.extensionName, n) == 0; });
}
}  // namespace

namespace mcl {

HdrPresenter& HdrPresenter::instance() { static HdrPresenter p; return p; }

bool HdrPresenter::init(ANativeWindow* window, bool wantHdr, const HdrParams& p, bool lowLatency) {
    window_ = window; wantHdr_ = wantHdr; params_ = p; lowLatency_ = lowLatency;
    if (!inst_ && !createInstance()) return false;
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = window_;
    VKCHECK(vkCreateAndroidSurfaceKHR(inst_, &sci, nullptr, &surface_));
    if (!dev_ && !pickDevice()) return false;
    return createSwapchain();   // crea también render pass y pipeline si faltan
}

bool HdrPresenter::createInstance() {
    uint32_t n = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> exts(n);
    vkEnumerateInstanceExtensionProperties(nullptr, &n, exts.data());

    std::vector<const char*> enabled = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    hasColorspaceExt_ = hasExt(exts, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
    if (hasColorspaceExt_) enabled.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
    else LOGI("VK_EXT_swapchain_colorspace no disponible: solo SDR");

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "MCLauncher Presenter";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = (uint32_t)enabled.size();
    ci.ppEnabledExtensionNames = enabled.data();
    VKCHECK(vkCreateInstance(&ci, nullptr, &inst_));
    return true;
}

bool HdrPresenter::pickDevice() {
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst_, &n, nullptr);
    if (n == 0) { LOGE("Sin GPU Vulkan"); return false; }
    std::vector<VkPhysicalDevice> gpus(n);
    vkEnumeratePhysicalDevices(inst_, &n, gpus.data());
    gpu_ = gpus[0];   // en móviles hay una sola GPU

    VkPhysicalDeviceIDProperties idp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
    VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &idp};
    vkGetPhysicalDeviceProperties2(gpu_, &p2);
    std::memcpy(deviceUuid_, idp.deviceUUID, VK_UUID_SIZE);
    std::memcpy(driverUuid_, idp.driverUUID, VK_UUID_SIZE);
    LOGI("GPU: %s, driver %u", p2.properties.deviceName, p2.properties.driverVersion);

    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu_, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu_, &qn, qf.data());
    bool found = false;
    for (uint32_t i = 0; i < qn; ++i) {
        VkBool32 present = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(gpu_, i, surface_, &present);
        if ((qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) { queueFamily_ = i; found = true; break; }
    }
    if (!found) { LOGE("Sin cola gráfica con presentación"); return false; }

    uint32_t en = 0;
    vkEnumerateDeviceExtensionProperties(gpu_, nullptr, &en, nullptr);
    std::vector<VkExtensionProperties> exts(en);
    vkEnumerateDeviceExtensionProperties(gpu_, nullptr, &en, exts.data());
    std::vector<const char*> enabled = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
        VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
    };
    for (auto* e : enabled) if (!hasExt(exts, e)) { LOGE("Extensión requerida ausente: %s", e); return false; }
    hasHdrMetadata_ = hasExt(exts, VK_EXT_HDR_METADATA_EXTENSION_NAME);
    if (hasHdrMetadata_) enabled.push_back(VK_EXT_HDR_METADATA_EXTENSION_NAME);
    if (hasExt(exts, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME)) enabled.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = queueFamily_; qci.queueCount = 1; qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = (uint32_t)enabled.size(); dci.ppEnabledExtensionNames = enabled.data();
    VKCHECK(vkCreateDevice(gpu_, &dci, nullptr, &dev_));
    vkGetDeviceQueue(dev_, queueFamily_, 0, &queue_);

    pGetMemoryFd_ = (PFN_vkGetMemoryFdKHR)vkGetDeviceProcAddr(dev_, "vkGetMemoryFdKHR");
    pGetSemaphoreFd_ = (PFN_vkGetSemaphoreFdKHR)vkGetDeviceProcAddr(dev_, "vkGetSemaphoreFdKHR");
    if (hasHdrMetadata_) pSetHdrMetadata_ = (PFN_vkSetHdrMetadataEXT)vkGetDeviceProcAddr(dev_, "vkSetHdrMetadataEXT");

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = queueFamily_;
    VKCHECK(vkCreateCommandPool(dev_, &pci, nullptr, &pool_));
    return true;
}

bool HdrPresenter::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    VKCHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu_, surface_, &caps));

    uint32_t fn = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu_, surface_, &fn, nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fn);
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu_, surface_, &fn, fmts.data());

    // Preferencia: HDR10 10 bits → SDR 8 bits UNORM (passthrough exacto)
    VkSurfaceFormatKHR chosen{VK_FORMAT_UNDEFINED, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
    hdrActive_ = false;
    if (wantHdr_ && hasColorspaceExt_) {
        for (auto& f : fmts) {
            if (f.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 && f.colorSpace == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
                chosen = f; hdrActive_ = true; break;
            }
        }
        if (!hdrActive_) LOGI("La superficie no ofrece A2B10G10R10 + HDR10_ST2084; usando SDR");
    }
    if (!hdrActive_) {
        for (auto& f : fmts) if (f.format == VK_FORMAT_R8G8B8A8_UNORM && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) { chosen = f; break; }
        if (chosen.format == VK_FORMAT_UNDEFINED) chosen = fmts[0];
    }
    swapFormat_ = chosen.format; swapColorSpace_ = chosen.colorSpace;

    transform_ = caps.currentTransform;
    extent_ = caps.currentExtent;
    if (extent_.width == 0xFFFFFFFF) {
        extent_.width = (uint32_t)ANativeWindow_getWidth(window_);
        extent_.height = (uint32_t)ANativeWindow_getHeight(window_);
    }

    uint32_t pmn = 0;
    vkGetPhysicalDeviceSurfacePresentModesKHR(gpu_, surface_, &pmn, nullptr);
    std::vector<VkPresentModeKHR> pms(pmn);
    vkGetPhysicalDeviceSurfacePresentModesKHR(gpu_, surface_, &pmn, pms.data());
    // MAILBOX = menor latencia sin tearing; FIFO = ritmo estable (mejor para MEMC del panel)
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    if (lowLatency_ && std::find(pms.begin(), pms.end(), VK_PRESENT_MODE_MAILBOX_KHR) != pms.end()) mode = VK_PRESENT_MODE_MAILBOX_KHR;

    uint32_t imgCount = std::max(caps.minImageCount, 3u);
    if (caps.maxImageCount) imgCount = std::min(imgCount, caps.maxImageCount);

    VkSwapchainKHR old = swapchain_;
    VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = surface_;
    sci.minImageCount = imgCount;
    sci.imageFormat = swapFormat_;
    sci.imageColorSpace = swapColorSpace_;   // HDR10_ST2084 → dataspace BT2020_PQ en Android
    sci.imageExtent = extent_;
    sci.imageArrayLayers = 1;
    sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    sci.preTransform = transform_;           // rotamos en el shader: el compositor no gasta GPU
    sci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                             ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    sci.presentMode = mode;
    sci.clipped = VK_TRUE;
    sci.oldSwapchain = old;
    VKCHECK(vkCreateSwapchainKHR(dev_, &sci, nullptr, &swapchain_));
    if (old) {
        vkDeviceWaitIdle(dev_);
        for (auto fb : framebuffers_) vkDestroyFramebuffer(dev_, fb, nullptr);
        for (auto v : swapViews_) vkDestroyImageView(dev_, v, nullptr);
        for (auto s : acquireSems_) vkDestroySemaphore(dev_, s, nullptr);
        for (auto s : renderDoneSems_) vkDestroySemaphore(dev_, s, nullptr);
        framebuffers_.clear(); swapViews_.clear(); acquireSems_.clear(); renderDoneSems_.clear();
        vkDestroySwapchainKHR(dev_, old, nullptr);
    }

    uint32_t n = 0;
    vkGetSwapchainImagesKHR(dev_, swapchain_, &n, nullptr);
    swapImages_.resize(n);
    vkGetSwapchainImagesKHR(dev_, swapchain_, &n, swapImages_.data());

    if (renderPass_ == VK_NULL_HANDLE || renderPassFormat_ != swapFormat_) {   // SDR↔HDR cambia el formato
        if (renderPass_) { vkDestroyRenderPass(dev_, renderPass_, nullptr); renderPass_ = VK_NULL_HANDLE; }
        VkAttachmentDescription att{};
        att.format = swapFormat_;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1; sub.pColorAttachments = &ref;
        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL; dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo rpci{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rpci.attachmentCount = 1; rpci.pAttachments = &att;
        rpci.subpassCount = 1; rpci.pSubpasses = &sub;
        rpci.dependencyCount = 1; rpci.pDependencies = &dep;
        VKCHECK(vkCreateRenderPass(dev_, &rpci, nullptr, &renderPass_));
        renderPassFormat_ = swapFormat_;
        if (pipeline_) { vkDestroyPipeline(dev_, pipeline_, nullptr); pipeline_ = VK_NULL_HANDLE; }
    }
    if (!pipeline_ && !createPipeline()) return false;

    VkSemaphoreCreateInfo semci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < n; ++i) {
        VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vci.image = swapImages_[i]; vci.viewType = VK_IMAGE_VIEW_TYPE_2D; vci.format = swapFormat_;
        vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageView v; VKCHECK(vkCreateImageView(dev_, &vci, nullptr, &v)); swapViews_.push_back(v);
        VkFramebufferCreateInfo fci{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fci.renderPass = renderPass_; fci.attachmentCount = 1; fci.pAttachments = &v;
        fci.width = extent_.width; fci.height = extent_.height; fci.layers = 1;
        VkFramebuffer fb; VKCHECK(vkCreateFramebuffer(dev_, &fci, nullptr, &fb)); framebuffers_.push_back(fb);
        VkSemaphore a, r;
        VKCHECK(vkCreateSemaphore(dev_, &semci, nullptr, &a)); VKCHECK(vkCreateSemaphore(dev_, &semci, nullptr, &r));
        acquireSems_.push_back(a); renderDoneSems_.push_back(r);
    }
    applyHdrMetadata();
    LOGI("Swapchain %ux%u formato=%d colorspace=%d HDR=%d modo=%d transform=%d",
         extent_.width, extent_.height, swapFormat_, swapColorSpace_, hdrActive_, mode, transform_);
    return true;
}

void HdrPresenter::applyHdrMetadata() {
    if (!hdrActive_ || !pSetHdrMetadata_) return;
    // Metadatos estáticos HDR10 (SMPTE ST 2086 + CTA-861.3). HDR10+ (dinámico, ST 2094-40)
    // no tiene API pública para superficies renderizadas por GPU en Android.
    VkHdrMetadataEXT md{VK_STRUCTURE_TYPE_HDR_METADATA_EXT};
    md.displayPrimaryRed = {0.708f, 0.292f};
    md.displayPrimaryGreen = {0.170f, 0.797f};
    md.displayPrimaryBlue = {0.131f, 0.046f};
    md.whitePoint = {0.3127f, 0.3290f};
    md.maxLuminance = params_.peakNits;
    md.minLuminance = params_.minNits;
    md.maxContentLightLevel = params_.peakNits;
    md.maxFrameAverageLightLevel = params_.maxFall;
    pSetHdrMetadata_(dev_, 1, &swapchain_, &md);
}

bool HdrPresenter::createPipeline() {
    VkSamplerCreateInfo sci{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sci.magFilter = VK_FILTER_LINEAR; sci.minFilter = VK_FILTER_LINEAR;
    sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (!sampler_) VKCHECK(vkCreateSampler(dev_, &sci, nullptr, &sampler_));

    VkDescriptorSetLayoutBinding b{0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, &sampler_};
    VkDescriptorSetLayoutCreateInfo dlci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dlci.bindingCount = 1; dlci.pBindings = &b;
    if (!dsl_) VKCHECK(vkCreateDescriptorSetLayout(dev_, &dlci, nullptr, &dsl_));

    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8};
    VkDescriptorPoolCreateInfo dpci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpci.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    dpci.maxSets = 8; dpci.poolSizeCount = 1; dpci.pPoolSizes = &ps;
    if (!descPool_) VKCHECK(vkCreateDescriptorPool(dev_, &dpci, nullptr, &descPool_));

    VkPushConstantRange pcr{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(PushConstants)};
    VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.setLayoutCount = 1; plci.pSetLayouts = &dsl_;
    plci.pushConstantRangeCount = 1; plci.pPushConstantRanges = &pcr;
    if (!layout_) VKCHECK(vkCreatePipelineLayout(dev_, &plci, nullptr, &layout_));

    auto makeModule = [&](const uint32_t* code, size_t bytes, VkShaderModule* out) {
        VkShaderModuleCreateInfo mci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        mci.codeSize = bytes; mci.pCode = code;
        return vkCreateShaderModule(dev_, &mci, nullptr, out);
    };
    VkShaderModule vs, fs;
    VKCHECK(makeModule(kVertSpv, sizeof(kVertSpv), &vs));
    VKCHECK(makeModule(kFragSpv, sizeof(kFragSpv), &fs));

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, vs, "main", nullptr};
    stages[1] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fs, "main", nullptr};
    VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1; vp.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL; rs.cullMode = VK_CULL_MODE_NONE; rs.lineWidth = 1.f;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState cba{};
    cba.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1; cb.pAttachments = &cba;
    VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    ds.dynamicStateCount = 2; ds.pDynamicStates = dyn;

    VkGraphicsPipelineCreateInfo gp{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gp.stageCount = 2; gp.pStages = stages;
    gp.pVertexInputState = &vi; gp.pInputAssemblyState = &ia; gp.pViewportState = &vp;
    gp.pRasterizationState = &rs; gp.pMultisampleState = &ms; gp.pColorBlendState = &cb;
    gp.pDynamicState = &ds; gp.layout = layout_; gp.renderPass = renderPass_;
    VkResult r = vkCreateGraphicsPipelines(dev_, VK_NULL_HANDLE, 1, &gp, nullptr, &pipeline_);
    vkDestroyShaderModule(dev_, vs, nullptr);
    vkDestroyShaderModule(dev_, fs, nullptr);
    VKCHECK(r);
    return true;
}

uint32_t HdrPresenter::findMemoryType(uint32_t bits, VkMemoryPropertyFlags flags) const {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(gpu_, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & flags) == flags) return i;
    return UINT32_MAX;
}

void HdrPresenter::deviceUuids(uint8_t device[VK_UUID_SIZE], uint8_t driver[VK_UUID_SIZE]) const {
    std::memcpy(device, deviceUuid_, VK_UUID_SIZE);
    std::memcpy(driver, driverUuid_, VK_UUID_SIZE);
}

bool HdrPresenter::createSharedImages(uint32_t count, uint32_t w, uint32_t h, bool floatFormat, std::vector<SharedImageFds>& out) {
    sharedIsFloat_ = floatFormat;
    const VkFormat fmt = floatFormat ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_R8G8B8A8_UNORM;
    out.clear();
    shared_.resize(count);
    for (uint32_t i = 0; i < count; ++i) {
        SharedImage& s = shared_[i];
        VkExternalMemoryImageCreateInfo emi{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        emi.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
        VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &emi};
        ici.imageType = VK_IMAGE_TYPE_2D; ici.format = fmt; ici.extent = {w, h, 1};
        ici.mipLevels = 1; ici.arrayLayers = 1; ici.samples = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling = VK_IMAGE_TILING_OPTIMAL;   // GL: glTexParameteri(GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT)
        ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        VKCHECK(vkCreateImage(dev_, &ici, nullptr, &s.image));

        VkMemoryRequirements req;
        vkGetImageMemoryRequirements(dev_, s.image, &req);
        VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        ded.image = s.image;
        VkExportMemoryAllocateInfo exp{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, &ded};
        exp.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &exp};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VKCHECK(vkAllocateMemory(dev_, &mai, nullptr, &s.memory));
        VKCHECK(vkBindImageMemory(dev_, s.image, s.memory, 0));
        s.allocationSize = req.size;

        VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vci.image = s.image; vci.viewType = VK_IMAGE_VIEW_TYPE_2D; vci.format = fmt;
        vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VKCHECK(vkCreateImageView(dev_, &vci, nullptr, &s.view));

        VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        dai.descriptorPool = descPool_; dai.descriptorSetCount = 1; dai.pSetLayouts = &dsl_;
        VKCHECK(vkAllocateDescriptorSets(dev_, &dai, &s.descriptor));
        VkDescriptorImageInfo dii{sampler_, s.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet wds{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        wds.dstSet = s.descriptor; wds.descriptorCount = 1;
        wds.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wds.pImageInfo = &dii;
        vkUpdateDescriptorSets(dev_, 1, &wds, 0, nullptr);

        VkExportSemaphoreCreateInfo esi{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
        esi.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
        VkSemaphoreCreateInfo semci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, &esi};
        VKCHECK(vkCreateSemaphore(dev_, &semci, nullptr, &s.glDone));
        VKCHECK(vkCreateSemaphore(dev_, &semci, nullptr, &s.vkDone));

        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        VKCHECK(vkCreateFence(dev_, &fci, nullptr, &s.inFlight));
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cai.commandPool = pool_; cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = 1;
        VKCHECK(vkAllocateCommandBuffers(dev_, &cai, &s.cmd));

        SharedImageFds fds;
        VkMemoryGetFdInfoKHR mgi{VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR};
        mgi.memory = s.memory; mgi.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
        VKCHECK(pGetMemoryFd_(dev_, &mgi, &fds.memoryFd));
        fds.size = req.size;
        VkSemaphoreGetFdInfoKHR sgi{VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR};
        sgi.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
        sgi.semaphore = s.glDone; VKCHECK(pGetSemaphoreFd_(dev_, &sgi, &fds.glDoneFd));
        sgi.semaphore = s.vkDone; VKCHECK(pGetSemaphoreFd_(dev_, &sgi, &fds.vkDoneFd));
        out.push_back(fds);
    }
    LOGI("%u imágenes compartidas %ux%u (%s)", count, w, h, floatFormat ? "RGBA16F lineal" : "RGBA8");
    return true;
}

void HdrPresenter::waitIdleForImage(uint32_t index) {
    if (index < shared_.size()) vkWaitForFences(dev_, 1, &shared_[index].inFlight, VK_TRUE, UINT64_MAX);
}

bool HdrPresenter::present(uint32_t index) {
    if (!swapchain_ || index >= shared_.size()) return false;
    SharedImage& s = shared_[index];
    vkWaitForFences(dev_, 1, &s.inFlight, VK_TRUE, UINT64_MAX);

    uint32_t imageIndex = 0;
    VkSemaphore acquireSem = acquireSems_[acquireIdx_];
    acquireIdx_ = (acquireIdx_ + 1) % (uint32_t)acquireSems_.size();
    VkResult ar = vkAcquireNextImageKHR(dev_, swapchain_, UINT64_MAX, acquireSem, VK_NULL_HANDLE, &imageIndex);
    if (ar == VK_ERROR_OUT_OF_DATE_KHR) { createSwapchain(); return false; }
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) { LOGE("acquire %d", ar); return false; }
    vkResetFences(dev_, 1, &s.inFlight);

    VkCommandBuffer cmd = s.cmd;
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &bi);

    // Adquirir la imagen desde la "cola externa" (Zink/GL). GL la dejó en SHADER_READ_ONLY.
    VkImageMemoryBarrier acq{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    acq.srcAccessMask = 0; acq.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    acq.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; acq.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    acq.srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL; acq.dstQueueFamilyIndex = queueFamily_;
    acq.image = s.image; acq.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &acq);

    VkRenderPassBeginInfo rbi{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rbi.renderPass = renderPass_; rbi.framebuffer = framebuffers_[imageIndex];
    rbi.renderArea = {{0, 0}, extent_};
    vkCmdBeginRenderPass(cmd, &rbi, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    VkViewport vpt{0, 0, (float)extent_.width, (float)extent_.height, 0, 1};
    VkRect2D sc{{0, 0}, extent_};
    vkCmdSetViewport(cmd, 0, 1, &vpt);
    vkCmdSetScissor(cmd, 0, 1, &sc);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout_, 0, 1, &s.descriptor, 0, nullptr);

    PushConstants pc{};
    // Matriz de pre-rotación (columnas). Verifica el sentido en tu dispositivo:
    // algunos drivers reportan ROTATE_90 donde el contenido espera 270.
    switch (transform_) {
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:  pc.rot[0] = 0;  pc.rot[1] = 1;  pc.rot[2] = -1; pc.rot[3] = 0;  break;
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: pc.rot[0] = -1; pc.rot[1] = 0;  pc.rot[2] = 0;  pc.rot[3] = -1; break;
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: pc.rot[0] = 0;  pc.rot[1] = -1; pc.rot[2] = 1;  pc.rot[3] = 0;  break;
        default:                                      pc.rot[0] = 1;  pc.rot[1] = 0;  pc.rot[2] = 0;  pc.rot[3] = 1;  break;
    }
    pc.hdr[0] = params_.paperWhiteNits; pc.hdr[1] = params_.peakNits;
    pc.hdr[2] = params_.hdrSdrRatio; pc.hdr[3] = params_.minNits;
    pc.modes[0] = (int)params_.mode; pc.modes[1] = hdrActive_ ? 1 : 0; pc.modes[2] = sharedIsFloat_ ? 1 : 0;
    vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
    vkCmdDraw(cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(cmd);

    // Devolver la imagen a GL
    VkImageMemoryBarrier rel = acq;
    rel.srcAccessMask = VK_ACCESS_SHADER_READ_BIT; rel.dstAccessMask = 0;
    rel.srcQueueFamilyIndex = queueFamily_; rel.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &rel);
    vkEndCommandBuffer(cmd);

    VkSemaphore waits[] = {s.glDone, acquireSem};
    VkPipelineStageFlags stages[] = {VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    VkSemaphore signals[] = {renderDoneSems_[imageIndex], s.vkDone};
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 2; si.pWaitSemaphores = waits; si.pWaitDstStageMask = stages;
    si.commandBufferCount = 1; si.pCommandBuffers = &cmd;
    si.signalSemaphoreCount = 2; si.pSignalSemaphores = signals;
    VKCHECK(vkQueueSubmit(queue_, 1, &si, s.inFlight));
    s.firstUse = false;

    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = &renderDoneSems_[imageIndex];
    pi.swapchainCount = 1; pi.pSwapchains = &swapchain_; pi.pImageIndices = &imageIndex;
    VkResult pr = vkQueuePresentKHR(queue_, &pi);
    if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) createSwapchain();
    return pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR;
}

void HdrPresenter::onWindowResized(ANativeWindow* w) {
    if (!dev_) return;
    vkDeviceWaitIdle(dev_);
    if (w != window_) {
        destroySwapchain();
        if (surface_) { vkDestroySurfaceKHR(inst_, surface_, nullptr); surface_ = VK_NULL_HANDLE; }
        window_ = w;
        if (!w) return;
        VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        sci.window = w;
        if (vkCreateAndroidSurfaceKHR(inst_, &sci, nullptr, &surface_) != VK_SUCCESS) return;
    }
    createSwapchain();
}

void HdrPresenter::onHdrParamsChanged(const HdrParams& p) {
    bool reconfigure = p.enabled != wantHdr_;
    params_ = p; wantHdr_ = p.enabled;
    if (!dev_) return;
    if (reconfigure) { vkDeviceWaitIdle(dev_); createSwapchain(); }
    else applyHdrMetadata();
}

void HdrPresenter::onHeadroomChanged(float ratio, float) { params_.hdrSdrRatio = ratio; }

void HdrPresenter::destroySwapchain() {
    if (!dev_) return;
    for (auto fb : framebuffers_) vkDestroyFramebuffer(dev_, fb, nullptr);
    for (auto v : swapViews_) vkDestroyImageView(dev_, v, nullptr);
    for (auto s : acquireSems_) vkDestroySemaphore(dev_, s, nullptr);
    for (auto s : renderDoneSems_) vkDestroySemaphore(dev_, s, nullptr);
    framebuffers_.clear(); swapViews_.clear(); acquireSems_.clear(); renderDoneSems_.clear(); swapImages_.clear();
    if (swapchain_) vkDestroySwapchainKHR(dev_, swapchain_, nullptr);
    swapchain_ = VK_NULL_HANDLE;
}

void HdrPresenter::shutdown() {
    if (!dev_) return;
    vkDeviceWaitIdle(dev_);
    for (auto& s : shared_) {
        vkDestroyFence(dev_, s.inFlight, nullptr);
        vkDestroySemaphore(dev_, s.glDone, nullptr); vkDestroySemaphore(dev_, s.vkDone, nullptr);
        vkDestroyImageView(dev_, s.view, nullptr); vkDestroyImage(dev_, s.image, nullptr); vkFreeMemory(dev_, s.memory, nullptr);
    }
    shared_.clear();
    destroySwapchain();
    vkDestroyPipeline(dev_, pipeline_, nullptr); vkDestroyPipelineLayout(dev_, layout_, nullptr);
    vkDestroyDescriptorPool(dev_, descPool_, nullptr); vkDestroyDescriptorSetLayout(dev_, dsl_, nullptr);
    vkDestroySampler(dev_, sampler_, nullptr); vkDestroyRenderPass(dev_, renderPass_, nullptr);
    vkDestroyCommandPool(dev_, pool_, nullptr); vkDestroyDevice(dev_, nullptr);
    if (surface_) vkDestroySurfaceKHR(inst_, surface_, nullptr);
    vkDestroyInstance(inst_, nullptr);
    *this = HdrPresenter();
}

}  // namespace mcl
