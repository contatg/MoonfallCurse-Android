#pragma once

#include <vulkan/vulkan.h>

#include <string_view>

namespace Fast::Extension {

inline bool AvoidBarrierHeavyMobileEffects(const VkPhysicalDeviceProperties& properties) {
#if defined(__ANDROID__)
    constexpr uint32_t qualcommVendorId = 0x5143;
    const std::string_view name(properties.deviceName);
    return properties.vendorID == qualcommVendorId &&
           (name.find("Adreno (TM) 610") != std::string_view::npos ||
            name.find("Adreno 610") != std::string_view::npos);
#else
    (void)properties;
    return false;
#endif
}

inline bool AvoidBarrierHeavyMobileEffects(VkPhysicalDevice physicalDevice) {
#if defined(__ANDROID__)
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(physicalDevice, &properties);
    return AvoidBarrierHeavyMobileEffects(properties);
#else
    (void)physicalDevice;
    return false;
#endif
}

} // namespace Fast::Extension
