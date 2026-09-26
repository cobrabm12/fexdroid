// Enumerates Vulkan devices through the system loader (the vendor Adreno driver).
// This is the baseline we compare Turnip against in phase 3.
#define VK_NO_PROTOTYPES
#include "probes.h"

#include <dlfcn.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define LOAD(inst, name) PFN_##name name = (PFN_##name)gipa(inst, #name)

static const char *kInterestingExts[] = {
    "VK_KHR_external_memory_fd",
    "VK_EXT_external_memory_dma_buf",
    "VK_ANDROID_external_memory_android_hardware_buffer",
    "VK_KHR_swapchain",
    "VK_EXT_descriptor_indexing",
    "VK_KHR_timeline_semaphore",
    "VK_EXT_transform_feedback",
    "VK_KHR_dynamic_rendering",
};

void probe_vulkan(strbuf *out) {
    void *lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { sb_printf(out, "dlopen libvulkan.so failed: %s\n", dlerror()); return; }
    PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!gipa) { sb_printf(out, "no vkGetInstanceProcAddr\n"); return; }

    uint32_t loader_ver = VK_API_VERSION_1_0;
    LOAD(NULL, vkEnumerateInstanceVersion);
    if (vkEnumerateInstanceVersion) vkEnumerateInstanceVersion(&loader_ver);
    sb_printf(out, "loader API %u.%u.%u\n", VK_API_VERSION_MAJOR(loader_ver),
              VK_API_VERSION_MINOR(loader_ver), VK_API_VERSION_PATCH(loader_ver));

    LOAD(NULL, vkCreateInstance);
    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                              .pApplicationName = "fexdroid-recon", .apiVersion = loader_ver };
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
    VkInstance inst;
    VkResult r = vkCreateInstance(&ici, NULL, &inst);
    if (r != VK_SUCCESS) { sb_printf(out, "vkCreateInstance failed: %d\n", r); return; }

    LOAD(inst, vkEnumeratePhysicalDevices);
    LOAD(inst, vkGetPhysicalDeviceProperties2);
    LOAD(inst, vkGetPhysicalDeviceMemoryProperties);
    LOAD(inst, vkEnumerateDeviceExtensionProperties);
    LOAD(inst, vkDestroyInstance);

    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, NULL);
    VkPhysicalDevice devs[8];
    if (n > 8) n = 8;
    vkEnumeratePhysicalDevices(inst, &n, devs);
    for (uint32_t i = 0; i < n; i++) {
        VkPhysicalDeviceDriverProperties drv = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES };
        VkPhysicalDeviceProperties2 p2 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &drv };
        vkGetPhysicalDeviceProperties2(devs[i], &p2);
        VkPhysicalDeviceProperties *p = &p2.properties;
        sb_printf(out, "device %u: %s\n", i, p->deviceName);
        sb_printf(out, "  vendor=0x%04x device=0x%08x api=%u.%u.%u driverVersion=0x%08x\n",
                  p->vendorID, p->deviceID, VK_API_VERSION_MAJOR(p->apiVersion),
                  VK_API_VERSION_MINOR(p->apiVersion), VK_API_VERSION_PATCH(p->apiVersion),
                  p->driverVersion);
        sb_printf(out, "  driver: %s / %s\n", drv.driverName, drv.driverInfo);

        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(devs[i], &mp);
        for (uint32_t h = 0; h < mp.memoryHeapCount; h++)
            sb_printf(out, "  heap %u: %llu MiB flags=0x%x\n", h,
                      (unsigned long long)(mp.memoryHeaps[h].size >> 20), mp.memoryHeaps[h].flags);

        uint32_t ne = 0;
        vkEnumerateDeviceExtensionProperties(devs[i], NULL, &ne, NULL);
        VkExtensionProperties ext[512];
        if (ne > 512) ne = 512;
        vkEnumerateDeviceExtensionProperties(devs[i], NULL, &ne, ext);
        sb_printf(out, "  %u device extensions\n", ne);
        for (size_t k = 0; k < sizeof kInterestingExts / sizeof *kInterestingExts; k++) {
            int found = 0;
            for (uint32_t e = 0; e < ne; e++)
                if (strcmp(ext[e].extensionName, kInterestingExts[k]) == 0) { found = 1; break; }
            sb_printf(out, "  %-52s %s\n", kInterestingExts[k], found ? "yes" : "no");
        }
    }
    vkDestroyInstance(inst, NULL);
}
