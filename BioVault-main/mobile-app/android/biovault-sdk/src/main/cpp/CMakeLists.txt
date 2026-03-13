# SDK CMakeLists.txt — builds core C++ library for BioVault SDK (.aar)
cmake_minimum_required(VERSION 3.18.1)
project(BioVaultCore)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to shared C++ core (relative to this CMakeLists.txt inside biovault-sdk/src/main/cpp)
set(CPP_CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../cpp")

# Core sources (same as app — these are the bulk of the SDK)
set(CORE_SOURCES
    ${CPP_CORE_DIR}/src/rppg_engine.cpp
    ${CPP_CORE_DIR}/src/prnu_extractor.cpp
    ${CPP_CORE_DIR}/src/crypto_utils.cpp
    ${CPP_CORE_DIR}/src/bio_vault_native.cpp
    ${CPP_CORE_DIR}/src/BioVaultExtractor.cpp
    ${CPP_CORE_DIR}/src/consensus_handshake.cpp
    ${CPP_CORE_DIR}/src/proof_of_reality.cpp
    ${CPP_CORE_DIR}/src/watermark.cpp
)

# JNI bridge sources (still needed for StrongBox JNI callbacks)
# These live in the app's cpp dir — reference them from there
set(APP_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/cpp")
set(JNI_SOURCES
    ${APP_CPP_DIR}/native-lib.cpp
    ${APP_CPP_DIR}/camera_bridge.cpp
)

# ============================================================================
# OpenCV Android SDK
# ============================================================================
if(DEFINED ENV{OPENCV_ANDROID_SDK})
    set(OpenCV_ANDROID_SDK $ENV{OPENCV_ANDROID_SDK})
elseif(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/OpenCV-android-sdk")
    set(OpenCV_ANDROID_SDK "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/OpenCV-android-sdk")
    message(STATUS "Auto-detected OpenCV SDK at: ${OpenCV_ANDROID_SDK}")
else()
    message(FATAL_ERROR "OpenCV SDK not found. Set OPENCV_ANDROID_SDK env var or place SDK in android/third_party/OpenCV-android-sdk")
endif()
set(OpenCV_ANDROID_INCLUDE "${OpenCV_ANDROID_SDK}/sdk/native/jni/include")
set(OpenCV_ANDROID_LIB "${OpenCV_ANDROID_SDK}/sdk/native/libs/${ANDROID_ABI}/libopencv_java4.so")

if(NOT EXISTS "${OpenCV_ANDROID_LIB}")
    message(FATAL_ERROR "OpenCV lib not found for ABI ${ANDROID_ABI} at ${OpenCV_ANDROID_LIB}")
endif()

add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES IMPORTED_LOCATION ${OpenCV_ANDROID_LIB})

set(HAVE_OPENCV ON)

# ============================================================================
# BLAKE3 via FetchContent
# ============================================================================
include(FetchContent)

FetchContent_Declare(blake3
    GIT_REPOSITORY https://github.com/BLAKE3-team/BLAKE3.git
    GIT_TAG 1.5.0
    SOURCE_SUBDIR c
)
FetchContent_MakeAvailable(blake3)
set(HAVE_BLAKE3 ON)

# ============================================================================
# libsodium (prebuilt static library)
# ============================================================================
set(LIBSODIUM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third-party/libsodium")

if(EXISTS "${LIBSODIUM_DIR}/lib/${ANDROID_ABI}/libsodium.a")
    add_library(sodium STATIC IMPORTED)
    set_target_properties(sodium PROPERTIES
        IMPORTED_LOCATION "${LIBSODIUM_DIR}/lib/${ANDROID_ABI}/libsodium.a"
        INTERFACE_INCLUDE_DIRECTORIES "${LIBSODIUM_DIR}/include"
    )
    set(HAVE_LIBSODIUM ON)
    message(STATUS "libsodium found for ${ANDROID_ABI}")
else()
    if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
        message(FATAL_ERROR "libsodium not found for ${ANDROID_ABI}.")
    else()
        message(WARNING "libsodium not found for ${ANDROID_ABI}. Using MOCK implementations!")
    endif()
    set(HAVE_LIBSODIUM OFF)
endif()

# Create shared library
add_library(${PROJECT_NAME} SHARED
    ${CORE_SOURCES}
    ${JNI_SOURCES}
)

# Include directories
target_include_directories(${PROJECT_NAME} PRIVATE
    ${CPP_CORE_DIR}/include
    ${OpenCV_ANDROID_INCLUDE}
    ${blake3_SOURCE_DIR}/c
    $<$<BOOL:${HAVE_LIBSODIUM}>:${LIBSODIUM_DIR}/include>
)

# Link libraries
target_link_libraries(${PROJECT_NAME}
    android
    log
    opencv_java4
    blake3
    $<$<BOOL:${HAVE_LIBSODIUM}>:sodium>
)

# Compile definitions
target_compile_definitions(${PROJECT_NAME} PRIVATE
    HAVE_OPENCV
    HAVE_BLAKE3
    $<$<BOOL:${HAVE_LIBSODIUM}>:HAVE_LIBSODIUM>
)

# Compiler flags
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(${PROJECT_NAME} PRIVATE
        -O3
        -fvisibility=hidden
    )
else()
    target_compile_options(${PROJECT_NAME} PRIVATE
        -g
        -Wall
        -Wextra
    )
endif()

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-debug --strip-unneeded 
        $<TARGET_FILE:${PROJECT_NAME}>
    )
endif()
