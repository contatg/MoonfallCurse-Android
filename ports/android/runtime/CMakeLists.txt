cmake_minimum_required(VERSION 3.30)
project(TriAevumAndroidRuntime LANGUAGES C CXX)
if(NOT ANDROID OR NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Use the Android NDK arm64-v8a toolchain")
endif()
set(CMAKE_CXX_SCAN_FOR_MODULES OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(OOT3D_WHOLE_AOT_PRODUCT_MODE ON CACHE BOOL "" FORCE)
set(OOT3D_DIRECT_AOT_PLUGIN ON CACHE BOOL "" FORCE)
set(OOT3D_ENABLE_VULKAN_RENDERER ON CACHE BOOL "" FORCE)
set(THREE_DS_RECOMP_ENABLE_CACAO OFF CACHE BOOL "" FORCE)
set(THREE_DS_RECOMP_ENABLE_SSSR OFF CACHE BOOL "" FORCE)
set(USE_OPENGLES ON CACHE BOOL "" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "" FORCE)
set(TRIAEVUM_ANDROID_DEPENDENCY_SOURCE_CACHE "" CACHE PATH "Existing FetchContent source trees (never share binary directories)")
if(TRIAEVUM_ANDROID_DEPENDENCY_SOURCE_CACHE)
    file(GLOB cached_sources LIST_DIRECTORIES TRUE "${TRIAEVUM_ANDROID_DEPENDENCY_SOURCE_CACHE}/*-src")
    foreach(cached_source IN LISTS cached_sources)
        get_filename_component(dependency "${cached_source}" NAME)
        string(REGEX REPLACE "-src$" "" dependency "${dependency}")
        string(TOUPPER "${dependency}" dependency)
        set("FETCHCONTENT_SOURCE_DIR_${dependency}" "${cached_source}" CACHE PATH "Cached dependency source" FORCE)
    endforeach()
endif()
get_filename_component(root "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
add_subdirectory("${root}" product)
target_sources(oot3d_native_game PRIVATE android_host.cpp android_input_bridge.cpp)
target_include_directories(oot3d_native_game PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
# The Forge's native services run in process (triaevum_forge_tool.cpp): the
# runtime's tool modes, the pass compiler and the device pipeline helper, on
# this library's own renderer and shaderc build.
set(forge_tools
    "${root}/tools/oot3d/native_pica_frontend/oot3d_native_pica_aot_compiler.cpp"
    "${root}/tools/renderer/pipeline_prepare/main.cpp")
target_sources(oot3d_native_game PRIVATE
    "${root}/tools/oot3d/native_game_runtime/triaevum_forge_tool.cpp" ${forge_tools})
set_source_files_properties(${forge_tools} TARGET_DIRECTORY oot3d_native_game
    PROPERTIES COMPILE_DEFINITIONS TRIAEVUM_TOOL_LIBRARY)
# The pipeline helper uses the renderer's NRI/Vulkan interfaces, as the
# runtime's own sources do.
set_property(SOURCE "${root}/tools/renderer/pipeline_prepare/main.cpp" TARGET_DIRECTORY oot3d_native_game
    APPEND PROPERTY COMPILE_DEFINITIONS ENABLE_RENDERER3DS_VULKAN ENABLE_RENDERER3DS_NRI)
target_include_directories(oot3d_native_game PRIVATE
    "${root}/tools/renderer/pipeline_prepare" "${root}/tools/oot3d/native_pica_frontend")
target_link_libraries(oot3d_native_game PRIVATE three_ds_recomp_shaderc NRI)

option(TRIAEVUM_ANDROID_TURNIP "Support imported Mesa Turnip drivers on Android ARM64" ON)
if(TRIAEVUM_ANDROID_TURNIP)
    include(FetchContent)
    block()
        set(BUILD_SHARED_LIBS OFF)
        FetchContent_Declare(adrenotools
            GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
            GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80
            EXCLUDE_FROM_ALL)
        FetchContent_MakeAvailable(adrenotools)
    endblock()

    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    find_library(vulkan_stub NAMES vulkan REQUIRED)
    set(vulkan_headers "${CMAKE_SYSROOT}/usr/include/vulkan")
    set(generator "${CMAKE_CURRENT_SOURCE_DIR}/generate_vulkan_dispatch.py")
    set(dispatch "${CMAKE_CURRENT_BINARY_DIR}/android_vulkan_dispatch.inc")
    add_custom_command(OUTPUT "${dispatch}"
        COMMAND "${Python3_EXECUTABLE}" "${generator}" "${CMAKE_NM}" "${vulkan_stub}" "${vulkan_headers}" "${dispatch}"
        DEPENDS "${generator}" "${vulkan_stub}" "${vulkan_headers}/vulkan_core.h" "${vulkan_headers}/vulkan_android.h"
        VERBATIM)

    add_library(triaevum_vulkan SHARED android_vulkan_loader.cpp "${dispatch}")
    target_compile_features(triaevum_vulkan PRIVATE cxx_std_17)
    target_include_directories(triaevum_vulkan PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
    target_link_libraries(triaevum_vulkan PRIVATE adrenotools dl log)
    add_dependencies(triaevum_vulkan main_hook)
    set_target_properties(triaevum_vulkan main_hook hook_impl PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/turnip-libs")

    # The final runtime links the Vulkan users from the shared renderer and Forge.
    # Wrap their direct calls. SDL and NRI load the same proxy by its absolute path.
    target_link_libraries(oot3d_native_game PRIVATE triaevum_vulkan)
    execute_process(COMMAND "${CMAKE_NM}" -D --defined-only --format=posix "${vulkan_stub}"
        OUTPUT_VARIABLE vulkan_exports COMMAND_ERROR_IS_FATAL ANY)
    string(REGEX MATCHALL "vk[A-Za-z0-9_]+ " vulkan_symbols "${vulkan_exports}")
    if(NOT vulkan_symbols)
        message(FATAL_ERROR "No Vulkan exports found in ${vulkan_stub}")
    endif()
    foreach(symbol IN LISTS vulkan_symbols)
        string(STRIP "${symbol}" symbol)
        target_link_options(oot3d_native_game PRIVATE "LINKER:--wrap=${symbol}")
    endforeach()
endif()
