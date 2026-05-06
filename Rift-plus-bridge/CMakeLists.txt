cmake_minimum_required(VERSION 3.10)

# Set default generator to MinGW Makefiles if on Windows and no generator specified
if(WIN32 AND NOT CMAKE_GENERATOR)
    set(CMAKE_GENERATOR "MinGW Makefiles" CACHE INTERNAL "" FORCE)
endif()

project(RSPlusBridge)

# Force GCC compiler if available
if(NOT CMAKE_C_COMPILER)
    find_program(CMAKE_C_COMPILER gcc)
endif()
if(NOT CMAKE_CXX_COMPILER)
    find_program(CMAKE_CXX_COMPILER g++)
endif()

# 1. Standard C++ Setup
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 2. Include Directories
# Includes your local headers and the NVIDIA Video Codec SDK headers
include_directories(include)

# Use user-provided SDK location or fall back to the default install path.
set(NV_CODEC_SDK_ROOT "$ENV{NV_CODEC_SDK_ROOT}")
if(NOT NV_CODEC_SDK_ROOT)
    set(NV_CODEC_SDK_ROOT "C:/Program Files/NVIDIA Toolkit/Video_Codec_SDK")
endif()
if(NOT EXISTS "${NV_CODEC_SDK_ROOT}")
    set(NV_CODEC_SDK_ROOT "C:/Users/buddy/Downloads/Video_Codec_SDK_13.0.37/Video_Codec_SDK_13.0.37")
endif()
message(STATUS "Using NVIDIA Video Codec SDK root: ${NV_CODEC_SDK_ROOT}")
include_directories("${NV_CODEC_SDK_ROOT}/Interface")

# OpenVR SDK
set(OPENVR_SDK_ROOT "C:/openvr/openvr-master")
if(EXISTS "${OPENVR_SDK_ROOT}/headers")
    include_directories("${OPENVR_SDK_ROOT}/headers")
    message(STATUS "Using OpenVR SDK root: ${OPENVR_SDK_ROOT}")
else()
    message(WARNING "OpenVR SDK not found at ${OPENVR_SDK_ROOT} - building without OpenVR support")
endif()

# 3. Source File Definitions
# Organize your source files to match your project-tree.txt
set(SOURCE_FILES
    src/main.cpp
    src/core/BridgeEngine.cpp
    src/network/TelemetryReceiver.cpp
    src/network/VideoStreamer.cpp
    src/video/FrameCapturer.cpp
    src/video/VideoEncoder.cpp
    src/video/H264Encoder.cpp      # The new NVENC implementation
)

# 4. Define the Executable
add_executable(RSPlusBridge ${SOURCE_FILES})

# 5. Link Libraries (Platform Specific)
if(WIN32)
    # WinSock for UDP, D3D11/DXGI for Capture
    target_link_libraries(RSPlusBridge 
        Ws2_32 
        d3d11 
        dxgi
    )
    
    # Check if NVIDIA SDK is available and add NVENC libraries if found
    if(EXISTS "${NV_CODEC_SDK_ROOT}/Lib/win/x64/nvEncodeAPI.lib")
        message(STATUS "NVIDIA Video Codec SDK found - enabling NVENC support")
        target_link_libraries(RSPlusBridge 
            nvcuvid        # NVIDIA Video Decoder (if needed later)
            nvEncodeAPI    # NVIDIA Video Encoder (Required for H264Encoder.cpp)
        )
        target_link_directories(RSPlusBridge PRIVATE "${NV_CODEC_SDK_ROOT}/Lib/win/x64")
        target_compile_definitions(RSPlusBridge PRIVATE NVENC_AVAILABLE)
    else()
        message(WARNING "NVIDIA Video Codec SDK not found - building without NVENC support")
        target_compile_definitions(RSPlusBridge PRIVATE NVENC_UNAVAILABLE)
    endif()
    
    # Check if OpenVR SDK is available and add libraries if found
    if(EXISTS "${OPENVR_SDK_ROOT}/lib/win64/openvr_api.lib")
        message(STATUS "OpenVR SDK found - enabling OpenVR support")
        target_link_libraries(RSPlusBridge openvr_api)
        target_link_directories(RSPlusBridge PRIVATE "${OPENVR_SDK_ROOT}/lib/win64")
        target_compile_definitions(RSPlusBridge PRIVATE OPENVR_AVAILABLE)
    else()
        message(WARNING "OpenVR SDK libraries not found - building without OpenVR support")
        target_compile_definitions(RSPlusBridge PRIVATE OPENVR_UNAVAILABLE)
    endif()
else()
    # Non-Windows fallback (primarily for network testing)
    target_link_libraries(RSPlusBridge pthread)
endif()

# 6. Post-Build (Optional)
# This copies your config.json to the build folder so the app can find it
add_custom_command(TARGET RSPlusBridge POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${PROJECT_SOURCE_DIR}/config.json"
    "${CMAKE_BINARY_DIR}/config.json"
)
