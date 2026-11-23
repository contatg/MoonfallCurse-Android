#include <adrenotools/driver.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex loaderMutex;
void* loaderHandle = nullptr;
bool turnipSelected = false;

template <typename Function>
Function VulkanEntry(const char* name) {
    std::lock_guard lock(loaderMutex);
    if (!loaderHandle)
        loaderHandle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    auto entry = loaderHandle ? reinterpret_cast<Function>(dlsym(loaderHandle, name)) : nullptr;
    if (!entry) {
        __android_log_print(ANDROID_LOG_FATAL, "TriAevum", "Missing Vulkan loader entry: %s", name);
        std::abort();
    }
    return entry;
}

bool SupportsTurnip(PFN_vkGetInstanceProcAddr getProc) {
    if (!getProc)
        return false;
    const auto create = reinterpret_cast<PFN_vkCreateInstance>(getProc(nullptr, "vkCreateInstance"));
    if (!create)
        return false;

    const char* extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = 2;
    info.ppEnabledExtensionNames = extensions;
    VkInstance instance{};
    if (create(&info, nullptr, &instance) != VK_SUCCESS)
        return false;

    const auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        getProc(instance, "vkEnumeratePhysicalDevices"));
    const auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
        getProc(instance, "vkGetPhysicalDeviceProperties2"));
    const auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(
        getProc(instance, "vkDestroyInstance"));
    const auto enumerateExtensions = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
        getProc(instance, "vkEnumerateDeviceExtensionProperties"));
    const auto surface = getProc(instance, "vkCreateAndroidSurfaceKHR");
    bool supported = false;
    uint32_t count = 0;
    if (destroy && enumerate && properties && enumerateExtensions && surface &&
        enumerate(instance, &count, nullptr) == VK_SUCCESS && count > 0) {
        std::vector<VkPhysicalDevice> devices(count);
        if (enumerate(instance, &count, devices.data()) == VK_SUCCESS) {
            for (uint32_t i = 0; i < count; ++i) {
                VkPhysicalDeviceDriverProperties driver{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
                VkPhysicalDeviceProperties2 device{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &driver};
                properties(devices[i], &device);
                if (driver.driverID != VK_DRIVER_ID_MESA_TURNIP ||
                    device.properties.apiVersion < VK_API_VERSION_1_2)
                    continue;

                uint32_t extensionCount = 0;
                if (enumerateExtensions(devices[i], nullptr, &extensionCount, nullptr) != VK_SUCCESS ||
                    extensionCount == 0)
                    continue;
                std::vector<VkExtensionProperties> deviceExtensions(extensionCount);
                if (enumerateExtensions(devices[i], nullptr, &extensionCount, deviceExtensions.data()) != VK_SUCCESS)
                    continue;
                for (uint32_t extension = 0; extension < extensionCount; ++extension) {
                    if (std::strcmp(deviceExtensions[extension].extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME) == 0) {
                        supported = true;
                        break;
                    }
                }
                if (supported) {
                    __android_log_print(ANDROID_LOG_INFO, "TriAevum", "Turnip gpu=%s driver=%s",
                        device.properties.deviceName, driver.driverName);
                    break;
                }
            }
        }
    }
    if (destroy)
        destroy(instance, nullptr);
    return supported;
}

std::string Directory(JNIEnv* env, jstring path) {
    const char* value = env->GetStringUTFChars(path, nullptr);
    if (!value)
        return {};
    std::string directory = std::string(value) + "/";
    env->ReleaseStringUTFChars(path, value);
    return directory;
}
}

#include "android_vulkan_dispatch.inc"

// SDL and NRI obtain this symbol through dlsym on the proxy library.
extern "C" VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(
    VkInstance instance, const char* name) {
    return __wrap_vkGetInstanceProcAddr(instance, name);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_triaevum_android_TurnipDriver_selectNative(
    JNIEnv* env, jclass, jstring nativeDir, jstring driverDir) {
    const std::string hooks = Directory(env, nativeDir);
    if (hooks.empty())
        return JNI_FALSE;
    const std::string driver = Directory(env, driverDir);
    if (driver.empty())
        return JNI_FALSE;

    std::lock_guard lock(loaderMutex);
    if (loaderHandle)
        return turnipSelected ? JNI_TRUE : JNI_FALSE;
    void* handle = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
        nullptr, hooks.c_str(), driver.c_str(), "libturnip.so", nullptr, nullptr);
    if (!handle)
        return JNI_FALSE;
    auto getProc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(handle, "vkGetInstanceProcAddr"));
    if (!SupportsTurnip(getProc)) {
        dlclose(handle);
        return JNI_FALSE;
    }

    const std::string proxy = hooks + "libtriaevum_vulkan.so";
    if (setenv("TRIAEVUM_ANDROID_TURNIP_LOADER", proxy.c_str(), 1) != 0 ||
        setenv("SDL_VULKAN_LIBRARY", proxy.c_str(), 1) != 0) {
        unsetenv("TRIAEVUM_ANDROID_TURNIP_LOADER");
        dlclose(handle);
        return JNI_FALSE;
    }
    loaderHandle = handle;
    turnipSelected = true;
    return JNI_TRUE;
}
