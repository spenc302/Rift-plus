cmake_minimum_required(VERSION 3.16)
project(RSPlusDriver)

set(CMAKE_CXX_STANDARD 17)

set(OPENVR_SDK_PATH "C:/openvr/openvr-master" CACHE PATH "Path to OpenVR SDK root")

# ── Sources ───────────────────────────────────────────────────────────────────
set(DRIVER_SOURCES
    driver_rsplus.cpp
    CDeviceDriver.cpp
    CWatchdogDriver.cpp
)

# ── Shared memory header (same file as bridge) ────────────────────────────────
set(COMMON_HEADERS
    include/common/SharedPose.hpp
)

# ── Build as DLL ─────────────────────────────────────────────────────────────
add_library(driver_rsplus SHARED
    ${DRIVER_SOURCES}
)

target_include_directories(driver_rsplus PRIVATE
    include
    include/common
    "${OPENVR_SDK_PATH}/headers"
)

target_link_libraries(driver_rsplus PRIVATE
    "${OPENVR_SDK_PATH}/lib/win64/openvr_api.lib"
)

# ── Output to SteamVR driver layout ──────────────────────────────────────────
# Final layout SteamVR expects:
#   drivers/rsplus/
#     ├── driver.vrdrivermanifest
#     ├── bin/win64/driver_rsplus.dll
#     └── resources/driver.vrresources
set_target_properties(driver_rsplus PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/drivers/rsplus/bin/win64"
)

# Copy manifest, guide and resources post-build
add_custom_command(TARGET driver_rsplus POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy
        "${CMAKE_SOURCE_DIR}/driver_vrdrivermanifest.json"
        "${CMAKE_BINARY_DIR}/drivers/rsplus/driver.vrdrivermanifest"
    COMMAND ${CMAKE_COMMAND} -E copy
        "${CMAKE_SOURCE_DIR}/driver.vrguide"
        "${CMAKE_BINARY_DIR}/drivers/rsplus/driver.vrguide"
    COMMAND ${CMAKE_COMMAND} -E copy
        "${CMAKE_SOURCE_DIR}/resources/driver.vrresources"
        "${CMAKE_BINARY_DIR}/drivers/rsplus/resources/driver.vrresources"
    COMMENT "Copying driver manifest, guide and resources"
)

# ── Install to SteamVR drivers folder ────────────────────────────────────────
set(STEAMVR_DRIVERS_PATH "C:/Program Files (x86)/Steam/steamapps/common/SteamVR/drivers"
    CACHE PATH "SteamVR drivers folder")

install(DIRECTORY "${CMAKE_BINARY_DIR}/drivers/rsplus"
        DESTINATION "${STEAMVR_DRIVERS_PATH}")