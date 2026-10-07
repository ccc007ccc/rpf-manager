#include "shaders.h"
#include <algorithm>
#include <android/native_window_jni.h>
#include <array>
#include <cmath>
#include <cstring>
#include <jni.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <vulkan/vulkan.h>

namespace {
void check(VkResult r, const char *action) {
  if (r != VK_SUCCESS)
    throw std::runtime_error(std::string(action) + " (" + std::to_string(r) +
                             ")");
}
struct Buffer {
  VkBuffer buffer = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
};
struct Push {
  float matrix[16];
  float options[4];
};
class Viewport {
public:
  ANativeWindow *window = nullptr;
  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkSurfaceKHR surface = VK_NULL_HANDLE;
  VkQueue queue = VK_NULL_HANDLE;
  uint32_t family = 0;
  VkSwapchainKHR swapchain = VK_NULL_HANDLE;
  VkExtent2D extent{};
  VkSurfaceTransformFlagBitsKHR preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
  VkFormat format = VK_FORMAT_UNDEFINED, depthFormat = VK_FORMAT_D32_SFLOAT;
  VkRenderPass pass = VK_NULL_HANDLE;
  VkPipelineLayout layout = VK_NULL_HANDLE;
  VkPipeline solid = VK_NULL_HANDLE, lines = VK_NULL_HANDLE;
  VkCommandPool pool = VK_NULL_HANDLE;
  VkCommandBuffer command = VK_NULL_HANDLE;
  VkFence fence = VK_NULL_HANDLE;
  VkSemaphore acquired = VK_NULL_HANDLE;
  std::vector<VkSemaphore> finished;
  std::vector<VkImageView> views;
  std::vector<VkFramebuffer> frames;
  VkImage depth = VK_NULL_HANDLE;
  VkImageView depthView = VK_NULL_HANDLE;
  VkDeviceMemory depthMemory = VK_NULL_HANDLE;
  Buffer vertices, indices;
  VkDescriptorSetLayout textureLayout = VK_NULL_HANDLE;
  VkDescriptorPool texturePool = VK_NULL_HANDLE;
  VkDescriptorSet textureSet = VK_NULL_HANDLE;
  VkSampler sampler = VK_NULL_HANDLE;
  VkImage texture = VK_NULL_HANDLE;
  VkImageView textureView = VK_NULL_HANDLE;
  VkDeviceMemory textureMemory = VK_NULL_HANDLE;
  bool textured = false;
  uint32_t triangles = 0, edges = 0, gridStart = 0, gridCount = 0;

  ~Viewport() {
    if (device) {
      vkDeviceWaitIdle(device);
      destroySwap();
      freeBuffer(vertices);
      freeBuffer(indices);
      freeTexture();
      if (sampler) vkDestroySampler(device, sampler, nullptr);
      if (texturePool) vkDestroyDescriptorPool(device, texturePool, nullptr);
      if (textureLayout) vkDestroyDescriptorSetLayout(device, textureLayout, nullptr);
      if (acquired)
        vkDestroySemaphore(device, acquired, nullptr);
      if (fence)
        vkDestroyFence(device, fence, nullptr);
      if (pool)
        vkDestroyCommandPool(device, pool, nullptr);
      vkDestroyDevice(device, nullptr);
    }
    if (surface)
      vkDestroySurfaceKHR(instance, surface, nullptr);
    if (instance)
      vkDestroyInstance(instance, nullptr);
    if (window)
      ANativeWindow_release(window);
  }
  void init(JNIEnv *env, jobject javaSurface) {
    window = ANativeWindow_fromSurface(env, javaSurface);
    if (!window)
      throw std::runtime_error("Preview surface is unavailable");
    const char *extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "RPF Manager";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = 2;
    ci.ppEnabledExtensionNames = extensions;
    check(vkCreateInstance(&ci, nullptr, &instance), "Create Vulkan instance");
    VkAndroidSurfaceCreateInfoKHR si{
        VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    si.window = window;
    check(vkCreateAndroidSurfaceKHR(instance, &si, nullptr, &surface),
          "Create preview surface");
    uint32_t count = 0;
    check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "Find GPU");
    std::vector<VkPhysicalDevice> devices(count);
    check(vkEnumeratePhysicalDevices(instance, &count, devices.data()),
          "Read GPUs");
    bool found = false;
    for (auto gpu : devices) {
      uint32_t n = 0;
      vkGetPhysicalDeviceQueueFamilyProperties(gpu, &n, nullptr);
      std::vector<VkQueueFamilyProperties> families(n);
      vkGetPhysicalDeviceQueueFamilyProperties(gpu, &n, families.data());
      for (uint32_t i = 0; i < n; ++i) {
        VkBool32 present = false;
        vkGetPhysicalDeviceSurfaceSupportKHR(gpu, i, surface, &present);
        if (present && (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
          physical = gpu;
          family = i;
          found = true;
          break;
        }
      }
      if (found)
        break;
    }
    if (!found)
      throw std::runtime_error(
          "No Vulkan graphics queue supports this surface");
    const float priority = 1.f;
    VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qi.queueFamilyIndex = family;
    qi.queueCount = 1;
    qi.pQueuePriorities = &priority;
    const char *swapExtension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    di.queueCreateInfoCount = 1;
    di.pQueueCreateInfos = &qi;
    di.enabledExtensionCount = 1;
    di.ppEnabledExtensionNames = &swapExtension;
    check(vkCreateDevice(physical, &di, nullptr, &device),
          "Create Vulkan device");
    vkGetDeviceQueue(device, family, 0, &queue);
    VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pi.queueFamilyIndex = family;
    pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    check(vkCreateCommandPool(device, &pi, nullptr, &pool),
          "Create command pool");
    VkCommandBufferAllocateInfo ai{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    check(vkAllocateCommandBuffers(device, &ai, &command),
          "Allocate command buffer");
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    check(vkCreateFence(device, &fi, nullptr, &fence), "Create frame fence");
    VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    check(vkCreateSemaphore(device, &sem, nullptr, &acquired),
          "Create acquire semaphore");
    createTextureBindings();
    setTexture(1, 1, {0xffffffffu}, false);
    createSwap();
  }
  uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties p{};
    vkGetPhysicalDeviceMemoryProperties(physical, &p);
    for (uint32_t i = 0; i < p.memoryTypeCount; ++i)
      if ((bits & (1u << i)) &&
          (p.memoryTypes[i].propertyFlags & flags) == flags)
        return i;
    throw std::runtime_error("No compatible GPU memory type");
  }
  void freeBuffer(Buffer &b) {
    if (b.buffer)
      vkDestroyBuffer(device, b.buffer, nullptr);
    if (b.memory)
      vkFreeMemory(device, b.memory, nullptr);
    b = {};
  }
  void upload(Buffer &b, const void *data, size_t size,
              VkBufferUsageFlags usage) {
    freeBuffer(b);
    if (!size)
      return;
    VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    ci.size = size;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(device, &ci, nullptr, &b.buffer),
          "Create mesh buffer");
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device, b.buffer, &req);
    VkMemoryAllocateInfo mi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mi.allocationSize = req.size;
    mi.memoryTypeIndex = memoryType(req.memoryTypeBits,
                                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    check(vkAllocateMemory(device, &mi, nullptr, &b.memory),
          "Allocate mesh memory");
    check(vkBindBufferMemory(device, b.buffer, b.memory, 0),
          "Bind mesh memory");
    void *mapped = nullptr;
    check(vkMapMemory(device, b.memory, 0, size, 0, &mapped),
          "Map mesh memory");
    std::memcpy(mapped, data, size);
    vkUnmapMemory(device, b.memory);
  }
  void freeTexture() {
    if (textureView) vkDestroyImageView(device, textureView, nullptr);
    if (texture) vkDestroyImage(device, texture, nullptr);
    if (textureMemory) vkFreeMemory(device, textureMemory, nullptr);
    textureView = VK_NULL_HANDLE; texture = VK_NULL_HANDLE; textureMemory = VK_NULL_HANDLE;
  }
  void createTextureBindings() {
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    li.bindingCount = 1; li.pBindings = &binding;
    check(vkCreateDescriptorSetLayout(device, &li, nullptr, &textureLayout), "Create texture layout");
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
    VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pi.maxSets = 1; pi.poolSizeCount = 1; pi.pPoolSizes = &size;
    check(vkCreateDescriptorPool(device, &pi, nullptr, &texturePool), "Create texture descriptor pool");
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool = texturePool; ai.descriptorSetCount = 1; ai.pSetLayouts = &textureLayout;
    check(vkAllocateDescriptorSets(device, &ai, &textureSet), "Allocate texture descriptor");
    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = si.minFilter = VK_FILTER_LINEAR;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    si.maxLod = 0;
    check(vkCreateSampler(device, &si, nullptr, &sampler), "Create texture sampler");
  }
  void setTexture(uint32_t width, uint32_t height, const std::vector<uint32_t> &argb, bool enabled) {
    if (!enabled && textureView) { textured = false; return; }
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(physical, &props);
    if (!width || !height || width > props.limits.maxImageDimension2D || height > props.limits.maxImageDimension2D ||
        static_cast<uint64_t>(width) * height > 16777216 || argb.size() != static_cast<size_t>(width) * height)
      throw std::runtime_error("Invalid preview texture dimensions");
    check(vkDeviceWaitIdle(device), "Wait for texture upload");
    freeTexture();
    std::vector<uint8_t> rgba(argb.size() * 4);
    for (size_t i = 0; i < argb.size(); ++i) {
      rgba[i * 4] = (argb[i] >> 16) & 255;
      rgba[i * 4 + 1] = (argb[i] >> 8) & 255;
      rgba[i * 4 + 2] = argb[i] & 255;
      rgba[i * 4 + 3] = (argb[i] >> 24) & 255;
    }
    Buffer staging;
    try {
      upload(staging, rgba.data(), rgba.size(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
      VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
      ci.imageType = VK_IMAGE_TYPE_2D; ci.format = VK_FORMAT_R8G8B8A8_UNORM;
      ci.extent = {width, height, 1}; ci.mipLevels = 1; ci.arrayLayers = 1;
      ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
      ci.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
      ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      check(vkCreateImage(device, &ci, nullptr, &texture), "Create texture image");
      VkMemoryRequirements req{}; vkGetImageMemoryRequirements(device, texture, &req);
      VkMemoryAllocateInfo mi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
      mi.allocationSize = req.size; mi.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
      check(vkAllocateMemory(device, &mi, nullptr, &textureMemory), "Allocate texture memory");
      check(vkBindImageMemory(device, texture, textureMemory, 0), "Bind texture memory");
      check(vkResetCommandBuffer(command, 0), "Reset texture upload commands");
      VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
      bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
      check(vkBeginCommandBuffer(command, &bi), "Begin texture upload");
      VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
      barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
      barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      barrier.image = texture; barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
      VkBufferImageCopy copy{}; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; copy.imageExtent = {width, height, 1};
      vkCmdCopyBufferToImage(command, staging.buffer, texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
      barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
      barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
      vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
      check(vkEndCommandBuffer(command), "End texture upload");
      VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1; submit.pCommandBuffers = &command;
      check(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE), "Submit texture upload");
      check(vkQueueWaitIdle(queue), "Wait for uploaded texture");
      VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
      vi.image = texture; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = VK_FORMAT_R8G8B8A8_UNORM;
      vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      check(vkCreateImageView(device, &vi, nullptr, &textureView), "Create texture view");
      VkDescriptorImageInfo image{sampler, textureView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
      VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
      write.dstSet = textureSet; write.dstBinding = 0; write.descriptorCount = 1;
      write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; write.pImageInfo = &image;
      vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
      textured = enabled;
    } catch (...) { freeBuffer(staging); throw; }
    freeBuffer(staging);
  }
  void setMesh(std::vector<float> v, std::vector<uint32_t> ix) {
    if (v.empty() || v.size() % 12 || ix.empty() || ix.size() % 3 ||
        v.size() > 12000000 || ix.size() > 6000000)
      throw std::runtime_error("Invalid mesh buffers");
    for (auto i : ix)
      if (i >= v.size() / 12)
        throw std::runtime_error("Mesh index outside vertex buffer");
    for (auto f : v)
      if (!std::isfinite(f))
        throw std::runtime_error("Invalid mesh coordinate");
    check(vkDeviceWaitIdle(device), "Wait for mesh upload");
    triangles = static_cast<uint32_t>(ix.size());
    edges = triangles * 2;
    ix.reserve(triangles + edges);
    for (uint32_t i = 0; i < triangles; i += 3) {
      auto a = ix[i], b = ix[i + 1], c = ix[i + 2];
      ix.insert(ix.end(), {a, b, b, c, c, a});
    }
    std::array<float, 3> lo{v[0], v[1], v[2]}, hi = lo;
    for (size_t i = 0; i < v.size(); i += 12)
      for (int k = 0; k < 3; ++k) {
        lo[k] = std::min(lo[k], v[i + k]);
        hi[k] = std::max(hi[k], v[i + k]);
      }
    float span = std::max({hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2], 0.01f});
    float x = (lo[0] + hi[0]) * 0.5f, y = (lo[1] + hi[1]) * 0.5f,
          z = lo[2] - span * 0.006f;
    gridStart = static_cast<uint32_t>(v.size() / 12);
    auto add = [&](float a, float b, float c, float r, float g, float blue) {
      v.insert(v.end(), {a, b, c, 0, 0, 1, r, g, blue, 0, 0, 0});
    };
    for (int i = -10; i <= 10; ++i) {
      float step = i * span / 10.f;
      float shade = i == 0 ? 0.32f : 0.19f;
      add(x - span, y + step, z, shade, shade + 0.02f, shade + 0.03f);
      add(x + span, y + step, z, shade, shade + 0.02f, shade + 0.03f);
      add(x + step, y - span, z, shade, shade + 0.02f, shade + 0.03f);
      add(x + step, y + span, z, shade, shade + 0.02f, shade + 0.03f);
    }
    gridCount = static_cast<uint32_t>(v.size() / 12) - gridStart;
    upload(vertices, v.data(), v.size() * sizeof(float),
           VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
    upload(indices, ix.data(), ix.size() * sizeof(uint32_t),
           VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
  }
  VkPipeline pipeline(VkPrimitiveTopology topology, VkShaderModule vs,
                      VkShaderModule fs) {
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vs;
    stages[0].pName = "main";
    stages[1] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fs;
    stages[1].pName = "main";
    VkVertexInputBindingDescription binding{0, 12 * sizeof(float),
                                            VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription attrs[] = {
        {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0},
        {1, 0, VK_FORMAT_R32G32B32_SFLOAT, 3 * sizeof(float)},
        {2, 0, VK_FORMAT_R32G32B32_SFLOAT, 6 * sizeof(float)},
        {3, 0, VK_FORMAT_R32G32B32_SFLOAT, 9 * sizeof(float)}};
    VkPipelineVertexInputStateCreateInfo input{
        VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    input.vertexBindingDescriptionCount = 1;
    input.pVertexBindingDescriptions = &binding;
    input.vertexAttributeDescriptionCount = 4;
    input.pVertexAttributeDescriptions = attrs;
    VkPipelineInputAssemblyStateCreateInfo assembly{
        VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    assembly.topology = topology;
    VkPipelineViewportStateCreateInfo viewport{
        VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo raster{
        VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_CLOCKWISE;
    raster.lineWidth = 1.f;
    VkPipelineMultisampleStateCreateInfo samples{
        VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    samples.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineDepthStencilStateCreateInfo ds{
        VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
    ds.depthTestEnable = VK_TRUE;
    ds.depthWriteEnable = VK_TRUE;
    ds.depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
    VkPipelineColorBlendAttachmentState attachment{};
    attachment.colorWriteMask = 0xf;
    VkPipelineColorBlendStateCreateInfo blend{
        VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blend.attachmentCount = 1;
    blend.pAttachments = &attachment;
    VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT,
                            VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{
        VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dyn;
    VkGraphicsPipelineCreateInfo ci{
        VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    ci.stageCount = 2;
    ci.pStages = stages;
    ci.pVertexInputState = &input;
    ci.pInputAssemblyState = &assembly;
    ci.pViewportState = &viewport;
    ci.pRasterizationState = &raster;
    ci.pMultisampleState = &samples;
    ci.pDepthStencilState = &ds;
    ci.pColorBlendState = &blend;
    ci.pDynamicState = &dynamic;
    ci.layout = layout;
    ci.renderPass = pass;
    VkPipeline out = VK_NULL_HANDLE;
    check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &ci, nullptr,
                                    &out),
          "Create preview pipeline");
    return out;
  }
  void createSwap() {
    VkSurfaceCapabilitiesKHR caps{};
    check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &caps),
          "Read surface capabilities");
    extent = caps.currentExtent;
    if (extent.width == UINT32_MAX)
      extent = {static_cast<uint32_t>(ANativeWindow_getWidth(window)),
                static_cast<uint32_t>(ANativeWindow_getHeight(window))};
    if (!extent.width || !extent.height)
      return;
    uint32_t count = 0;
    check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count,
                                               nullptr),
          "Read surface formats");
    if (!count)
      throw std::runtime_error("No Vulkan surface format");
    std::vector<VkSurfaceFormatKHR> formats(count);
    check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count,
                                               formats.data()),
          "Read surface formats");
    auto selected = formats.front();
    for (auto f : formats)
      if (f.format == VK_FORMAT_B8G8R8A8_UNORM ||
          f.format == VK_FORMAT_R8G8B8A8_UNORM) {
        selected = f;
        break;
      }
    format = selected.format == VK_FORMAT_UNDEFINED ? VK_FORMAT_B8G8R8A8_UNORM
                                                    : selected.format;
    VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface = surface;
    ci.minImageCount =
        std::min(caps.minImageCount + 1,
                 caps.maxImageCount ? caps.maxImageCount : UINT32_MAX);
    ci.imageFormat = format;
    ci.imageColorSpace = selected.colorSpace;
    ci.imageExtent = extent;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // Camera matrices use the View's logical orientation. As in the reference
    // GTA swapchain, let presentation handle Android display rotation.
    preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                       ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    ci.preTransform = preTransform;
    ci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    for (auto alpha :
         {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
          VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
          VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR})
      if (caps.supportedCompositeAlpha & alpha) {
        ci.compositeAlpha = alpha;
        break;
      }
    ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    ci.clipped = VK_TRUE;
    check(vkCreateSwapchainKHR(device, &ci, nullptr, &swapchain),
          "Create swapchain");
    VkFormatProperties dp{};
    vkGetPhysicalDeviceFormatProperties(physical, depthFormat, &dp);
    if (!(dp.optimalTilingFeatures &
          VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT))
      depthFormat = VK_FORMAT_D16_UNORM;
    VkAttachmentDescription attachments[2]{};
    attachments[0].format = format;
    attachments[0].samples = VK_SAMPLE_COUNT_1_BIT;
    attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachments[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachments[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[0].finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    attachments[1] = attachments[0];
    attachments[1].format = depthFormat;
    attachments[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[1].finalLayout =
        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    VkAttachmentReference color{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL},
        dep{1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &color;
    sub.pDepthStencilAttachment = &dep;
    VkSubpassDependency dependency{};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                              VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
    dependency.dstStageMask = dependency.srcStageMask;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                               VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rp.attachmentCount = 2;
    rp.pAttachments = attachments;
    rp.subpassCount = 1;
    rp.pSubpasses = &sub;
    rp.dependencyCount = 1;
    rp.pDependencies = &dependency;
    check(vkCreateRenderPass(device, &rp, nullptr, &pass),
          "Create render pass");
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = depthFormat;
    ii.extent = {extent.width, extent.height, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateImage(device, &ii, nullptr, &depth), "Create depth image");
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(device, depth, &req);
    VkMemoryAllocateInfo mi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mi.allocationSize = req.size;
    mi.memoryTypeIndex =
        memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(device, &mi, nullptr, &depthMemory),
          "Allocate depth image");
    check(vkBindImageMemory(device, depth, depthMemory, 0), "Bind depth image");
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = depth;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = depthFormat;
    vi.subresourceRange = {VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, 1};
    check(vkCreateImageView(device, &vi, nullptr, &depthView),
          "Create depth view");
    check(vkGetSwapchainImagesKHR(device, swapchain, &count, nullptr),
          "Read swapchain images");
    std::vector<VkImage> images(count);
    check(vkGetSwapchainImagesKHR(device, swapchain, &count, images.data()),
          "Read swapchain images");
    views.resize(count);
    frames.resize(count);
    finished.resize(count);
    for (uint32_t i = 0; i < count; ++i) {
      vi.image = images[i];
      vi.format = format;
      vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
      check(vkCreateImageView(device, &vi, nullptr, &views[i]),
            "Create color view");
      VkImageView av[] = {views[i], depthView};
      VkFramebufferCreateInfo fb{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
      fb.renderPass = pass;
      fb.attachmentCount = 2;
      fb.pAttachments = av;
      fb.width = extent.width;
      fb.height = extent.height;
      fb.layers = 1;
      check(vkCreateFramebuffer(device, &fb, nullptr, &frames[i]),
            "Create framebuffer");
      VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
      check(vkCreateSemaphore(device, &sem, nullptr, &finished[i]),
            "Create present semaphore");
    }
    VkPushConstantRange range{VK_SHADER_STAGE_VERTEX_BIT |
                                  VK_SHADER_STAGE_FRAGMENT_BIT,
                              0, sizeof(Push)};
    VkPipelineLayoutCreateInfo li{
        VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    li.pushConstantRangeCount = 1;
    li.pPushConstantRanges = &range;
    li.setLayoutCount = 1;
    li.pSetLayouts = &textureLayout;
    check(vkCreatePipelineLayout(device, &li, nullptr, &layout),
          "Create pipeline layout");
    VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE;
    try {
      VkShaderModuleCreateInfo sh{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
      sh.codeSize = sizeof(scene_vert);
      sh.pCode = scene_vert;
      check(vkCreateShaderModule(device, &sh, nullptr, &vs),
            "Create vertex shader");
      sh.codeSize = sizeof(scene_frag);
      sh.pCode = scene_frag;
      check(vkCreateShaderModule(device, &sh, nullptr, &fs),
            "Create fragment shader");
      solid = pipeline(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, vs, fs);
      lines = pipeline(VK_PRIMITIVE_TOPOLOGY_LINE_LIST, vs, fs);
    } catch (...) {
      if (vs)
        vkDestroyShaderModule(device, vs, nullptr);
      if (fs)
        vkDestroyShaderModule(device, fs, nullptr);
      throw;
    }
    vkDestroyShaderModule(device, vs, nullptr);
    vkDestroyShaderModule(device, fs, nullptr);
  }
  void destroySwap() {
    for (auto fb : frames)
      if (fb)
        vkDestroyFramebuffer(device, fb, nullptr);
    frames.clear();
    for (auto view : views)
      if (view)
        vkDestroyImageView(device, view, nullptr);
    views.clear();
    for (auto sem : finished)
      if (sem)
        vkDestroySemaphore(device, sem, nullptr);
    finished.clear();
    if (solid)
      vkDestroyPipeline(device, solid, nullptr);
    solid = VK_NULL_HANDLE;
    if (lines)
      vkDestroyPipeline(device, lines, nullptr);
    lines = VK_NULL_HANDLE;
    if (layout)
      vkDestroyPipelineLayout(device, layout, nullptr);
    layout = VK_NULL_HANDLE;
    if (pass)
      vkDestroyRenderPass(device, pass, nullptr);
    pass = VK_NULL_HANDLE;
    if (depthView)
      vkDestroyImageView(device, depthView, nullptr);
    depthView = VK_NULL_HANDLE;
    if (depth)
      vkDestroyImage(device, depth, nullptr);
    depth = VK_NULL_HANDLE;
    if (depthMemory)
      vkFreeMemory(device, depthMemory, nullptr);
    depthMemory = VK_NULL_HANDLE;
    if (swapchain)
      vkDestroySwapchainKHR(device, swapchain, nullptr);
    swapchain = VK_NULL_HANDLE;
  }
  void resize() {
    check(vkDeviceWaitIdle(device), "Wait for surface resize");
    destroySwap();
    createSwap();
  }
  bool needsResize() {
    VkSurfaceCapabilitiesKHR caps{};
    check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &caps),
          "Check preview surface");
    const auto transform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                               ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    return (caps.currentExtent.width != UINT32_MAX &&
            (caps.currentExtent.width != extent.width || caps.currentExtent.height != extent.height)) ||
           transform != preTransform;
  }
  void draw(const float *matrix, bool wire, bool grid) {
    if (!vertices.buffer)
      return;
    if (!swapchain) {
      createSwap();
      if (!swapchain)
        return;
    }
    check(vkWaitForFences(device, 1, &fence, VK_TRUE, 2000000000ULL),
          "Wait for preview frame");
    uint32_t i = 0;
    auto status = vkAcquireNextImageKHR(device, swapchain, 2000000000ULL,
                                        acquired, VK_NULL_HANDLE, &i);
    if (status == VK_ERROR_OUT_OF_DATE_KHR) {
      resize();
      return;
    }
    if (status != VK_SUBOPTIMAL_KHR)
      check(status, "Acquire preview frame");
    check(vkResetCommandBuffer(command, 0), "Reset preview commands");
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    check(vkBeginCommandBuffer(command, &bi), "Begin preview commands");
    VkClearValue clear[2]{};
    clear[0].color = {{0.071f, 0.082f, 0.094f, 1.f}};
    clear[1].depthStencil = {1.f, 0};
    VkRenderPassBeginInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    ri.renderPass = pass;
    ri.framebuffer = frames[i];
    ri.renderArea = {{0, 0}, extent};
    ri.clearValueCount = 2;
    ri.pClearValues = clear;
    vkCmdBeginRenderPass(command, &ri, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0,
                  0,
                  static_cast<float>(extent.width),
                  static_cast<float>(extent.height),
                  0,
                  1};
    VkRect2D sc{{0, 0}, extent};
    vkCmdSetViewport(command, 0, 1, &vp);
    vkCmdSetScissor(command, 0, 1, &sc);
    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(command, 0, 1, &vertices.buffer, &offset);
    vkCmdBindIndexBuffer(command, indices.buffer, 0, VK_INDEX_TYPE_UINT32);
    Push push{};
    std::memcpy(push.matrix, matrix, sizeof(push.matrix));
    push.options[0] = wire ? 1.f : 0.f;
    push.options[1] = textured ? 1.f : 0.f;
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, 1, &textureSet, 0, nullptr);
    vkCmdPushConstants(command, layout,
                       VK_SHADER_STAGE_VERTEX_BIT |
                           VK_SHADER_STAGE_FRAGMENT_BIT,
                       0, sizeof(push), &push);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS,
                      wire ? lines : solid);
    vkCmdDrawIndexed(command, wire ? edges : triangles, 1, wire ? triangles : 0,
                     0, 0);
    if (grid) {
      push.options[0] = 1.f;
      vkCmdPushConstants(command, layout,
                         VK_SHADER_STAGE_VERTEX_BIT |
                             VK_SHADER_STAGE_FRAGMENT_BIT,
                         0, sizeof(push), &push);
      vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, lines);
      vkCmdDraw(command, gridCount, 1, gridStart, 0);
    }
    vkCmdEndRenderPass(command);
    check(vkEndCommandBuffer(command), "End preview commands");
    VkPipelineStageFlags waitStage =
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &acquired;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &finished[i];
    check(vkResetFences(device, 1, &fence), "Reset frame fence");
    check(vkQueueSubmit(queue, 1, &submit, fence), "Submit preview");
    VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &finished[i];
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain;
    present.pImageIndices = &i;
    status = vkQueuePresentKHR(queue, &present);
    // IDENTITY can be reported as SUBOPTIMAL on every landscape frame. Only
    // rebuild for an actual capability/extent change, not that rotation hint.
    if (status == VK_ERROR_OUT_OF_DATE_KHR ||
        (status == VK_SUBOPTIMAL_KHR && needsResize()))
      resize();
    else if (status != VK_SUBOPTIMAL_KHR)
      check(status, "Present preview");
  }
};
void error(JNIEnv *env, const std::exception &e) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
} // namespace
extern "C" JNIEXPORT jlong JNICALL
Java_com_gtav_rpfmanager_preview_NativeViewport_create(JNIEnv *env, jobject,
                                                       jobject surface) {
  try {
    auto v = std::make_unique<Viewport>();
    v->init(env, surface);
    return reinterpret_cast<jlong>(v.release());
  } catch (const std::exception &e) {
    error(env, e);
    return 0;
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_gtav_rpfmanager_preview_NativeViewport_destroy(JNIEnv *, jobject,
                                                        jlong handle) {
  delete reinterpret_cast<Viewport *>(handle);
}
extern "C" JNIEXPORT void JNICALL
Java_com_gtav_rpfmanager_preview_NativeViewport_resize(JNIEnv *env, jobject,
                                                       jlong handle) {
  try {
    reinterpret_cast<Viewport *>(handle)->resize();
  } catch (const std::exception &e) {
    error(env, e);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_gtav_rpfmanager_preview_NativeViewport_setMesh(JNIEnv *env, jobject,
                                                        jlong handle,
                                                        jfloatArray vertices,
                                                        jintArray indices) {
  try {
    std::vector<float> v(env->GetArrayLength(vertices));
    std::vector<uint32_t> i(env->GetArrayLength(indices));
    env->GetFloatArrayRegion(vertices, 0, v.size(), v.data());
    env->GetIntArrayRegion(indices, 0, i.size(),
                           reinterpret_cast<jint *>(i.data()));
    if (env->ExceptionCheck())
      return;
    reinterpret_cast<Viewport *>(handle)->setMesh(std::move(v), std::move(i));
  } catch (const std::exception &e) {
    error(env, e);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_gtav_rpfmanager_preview_NativeViewport_setTexture(JNIEnv *env, jobject,
                                                         jlong handle, jint width, jint height, jintArray pixels) {
  try {
    if (!pixels) {
      reinterpret_cast<Viewport *>(handle)->setTexture(1, 1, {0xffffffffu}, false);
      return;
    }
    const auto count = env->GetArrayLength(pixels);
    if (count <= 0 || count > 16777216) throw std::runtime_error("Invalid texture pixel count");
    std::vector<uint32_t> argb(count);
    env->GetIntArrayRegion(pixels, 0, count, reinterpret_cast<jint *>(argb.data()));
    if (env->ExceptionCheck()) return;
    reinterpret_cast<Viewport *>(handle)->setTexture(width, height, argb, true);
  } catch (const std::exception &e) { error(env, e); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_gtav_rpfmanager_preview_NativeViewport_draw(JNIEnv *env, jobject,
                                                     jlong handle,
                                                     jfloatArray matrix,
                                                     jboolean wire,
                                                     jboolean grid) {
  try {
    if (env->GetArrayLength(matrix) != 16)
      throw std::runtime_error("Invalid camera matrix");
    float m[16];
    env->GetFloatArrayRegion(matrix, 0, 16, m);
    if (env->ExceptionCheck())
      return;
    reinterpret_cast<Viewport *>(handle)->draw(m, wire, grid);
  } catch (const std::exception &e) {
    error(env, e);
  }
}
