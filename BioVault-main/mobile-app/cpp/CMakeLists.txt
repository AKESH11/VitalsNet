cmake_minimum_required(VERSION 3.20)
project(BioVaultCore VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ============================================================================
# OpenCV Configuration
# ============================================================================
# For Windows: Set OpenCV_DIR to your OpenCV installation
# Example: set OpenCV_DIR=C:/opencv/build
# For macOS: brew install opencv
# For Linux: sudo apt-get install libopencv-dev

find_package(OpenCV)
if(OpenCV_FOUND)
    message(STATUS "OpenCV Version: ${OpenCV_VERSION}")
    message(STATUS "OpenCV Libraries: ${OpenCV_LIBS}")
    set(HAVE_OPENCV ON)
else()
    message(WARNING "OpenCV not found! Building without OpenCV support.")
    message(STATUS "To enable OpenCV: Set OpenCV_DIR environment variable")
    set(HAVE_OPENCV OFF)
endif()

# ============================================================================
# Cryptography Libraries Configuration
# ============================================================================

# libsodium (PRIMARY — provides SHA-256 + Ed25519)
# On Android: cross-compiled via scripts/build_libsodium_android.ps1
# On desktop: install via package manager or set LIBSODIUM_ROOT
find_path(LIBSODIUM_INCLUDE_DIR sodium.h)
find_library(LIBSODIUM_LIBRARY NAMES sodium libsodium)
if(LIBSODIUM_INCLUDE_DIR AND LIBSODIUM_LIBRARY)
    message(STATUS "libsodium Found")
    message(STATUS "libsodium Include: ${LIBSODIUM_INCLUDE_DIR}")
    message(STATUS "libsodium Library: ${LIBSODIUM_LIBRARY}")
    set(HAVE_LIBSODIUM ON)
else()
    message(WARNING "libsodium not found! SHA-256 and Ed25519 will use MOCK (INSECURE)")
    message(STATUS "Install: vcpkg install libsodium / brew install libsodium / apt install libsodium-dev")
    set(HAVE_LIBSODIUM OFF)
endif()

# BLAKE3 (for fast cryptographic hashing)
find_path(BLAKE3_INCLUDE_DIR blake3.h)
find_library(BLAKE3_LIBRARY NAMES blake3 libblake3)
if(BLAKE3_INCLUDE_DIR AND BLAKE3_LIBRARY)
    message(STATUS "BLAKE3 Found")
    message(STATUS "BLAKE3 Include: ${BLAKE3_INCLUDE_DIR}")
    message(STATUS "BLAKE3 Library: ${BLAKE3_LIBRARY}")
    set(HAVE_BLAKE3 ON)
else()
    message(WARNING "BLAKE3 not found! Using SHA-256 fallback")
    message(STATUS "To enable: Build and install BLAKE3 from source")
    set(HAVE_BLAKE3 OFF)
endif()

# ============================================================================
# Source Files
# ============================================================================
set(SOURCES
    src/rppg_engine.cpp
    src/prnu_extractor.cpp
    src/crypto_utils.cpp
    src/consensus_handshake.cpp
    src/proof_of_reality.cpp
    src/bio_vault_native.cpp
    src/watermark.cpp
)

set(HEADERS
    include/rppg_engine.h
    include/prnu_extractor.h
    include/crypto_utils.h
    include/consensus_handshake.h
    include/proof_of_reality.h
    include/bio_vault_native.h
    include/watermark.h
)

if(HAVE_OPENCV)
    list(APPEND SOURCES src/BioVaultExtractor.cpp)
    list(APPEND HEADERS include/BioVaultExtractor.h)
endif()

# ============================================================================
# ============================================================================
# Library Target
# ============================================================================
add_library(${PROJECT_NAME} SHARED ${SOURCES} ${HEADERS})

# Add compile definitions for optional features
if(HAVE_OPENCV)
    target_compile_definitions(${PROJECT_NAME} PRIVATE HAVE_OPENCV)
endif()

if(HAVE_LIBSODIUM)
    target_compile_definitions(${PROJECT_NAME} PRIVATE HAVE_LIBSODIUM)
endif()

if(HAVE_BLAKE3)
    target_compile_definitions(${PROJECT_NAME} PRIVATE HAVE_BLAKE3)
endif()

target_include_directories(${PROJECT_NAME}
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        $<$<BOOL:${HAVE_LIBSODIUM}>:${LIBSODIUM_INCLUDE_DIR}>
        $<$<BOOL:${HAVE_BLAKE3}>:${BLAKE3_INCLUDE_DIR}>
)

target_link_libraries(${PROJECT_NAME}
    PUBLIC
        $<$<BOOL:${HAVE_OPENCV}>:${OpenCV_LIBS}>
    PRIVATE
        $<$<BOOL:${HAVE_LIBSODIUM}>:${LIBSODIUM_LIBRARY}>
        $<$<BOOL:${HAVE_BLAKE3}>:${BLAKE3_LIBRARY}>
)

# ============================================================================
# Platform-Specific Configurations
# ============================================================================
if(ANDROID)
    # Android JNI Configuration
    find_library(log-lib log)
    target_link_libraries(${PROJECT_NAME} PRIVATE ${log-lib})
    
    set_target_properties(${PROJECT_NAME} PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../android/app/src/main/jniLibs/${ANDROID_ABI}"
    )
    
    message(STATUS "Building for Android ABI: ${ANDROID_ABI}")
    
elseif(IOS)
    # iOS Configuration
    set_target_properties(${PROJECT_NAME} PROPERTIES
        FRAMEWORK TRUE
        MACOSX_FRAMEWORK_IDENTIFIER com.biovault.core
        VERSION ${PROJECT_VERSION}
        SOVERSION 1
    )
    
    message(STATUS "Building for iOS")
    
elseif(WIN32)
    # Windows Configuration
    target_compile_definitions(${PROJECT_NAME} PRIVATE BIOVAULT_EXPORTS)
    
elseif(APPLE)
    # macOS Configuration
    set_target_properties(${PROJECT_NAME} PROPERTIES
        INSTALL_RPATH "@loader_path"
        BUILD_WITH_INSTALL_RPATH TRUE
    )
endif()

# ============================================================================
# Compiler Warnings and Optimizations
# ============================================================================
if(MSVC)
    target_compile_options(${PROJECT_NAME} PRIVATE /W4)
else()
    target_compile_options(${PROJECT_NAME} PRIVATE -Wall -Wextra -Wpedantic)
endif()

# Release optimizations
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    if(MSVC)
        target_compile_options(${PROJECT_NAME} PRIVATE /O2)
    else()
        target_compile_options(${PROJECT_NAME} PRIVATE -O3)
    endif()
endif()

# ============================================================================
# Installation (Optional)
# ============================================================================
install(TARGETS ${PROJECT_NAME}
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
    RUNTIME DESTINATION bin
    FRAMEWORK DESTINATION Frameworks
)

install(FILES ${HEADERS} DESTINATION include/biovault)

# ============================================================================
# Examples (Optional)
# ============================================================================
option(BUILD_EXAMPLES "Build example programs" ON)

if(BUILD_EXAMPLES)
    # PRNU + BLAKE3 Demo
    add_executable(prnu_blake3_demo examples/prnu_blake3_demo.cpp)
    target_link_libraries(prnu_blake3_demo PRIVATE ${PROJECT_NAME})
    target_include_directories(prnu_blake3_demo PRIVATE include)
    
    message(STATUS "Building examples:")
    message(STATUS "  - prnu_blake3_demo")
    
    # rPPG Webcam Demo (if OpenCV available)
    if(HAVE_OPENCV)
        if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/examples/rppg_webcam_demo.cpp)
            add_executable(rppg_webcam_demo examples/rppg_webcam_demo.cpp)
            target_link_libraries(rppg_webcam_demo PRIVATE ${PROJECT_NAME})
            target_include_directories(rppg_webcam_demo PRIVATE include)
            message(STATUS "  - rppg_webcam_demo")
        endif()
    endif()
endif()

# ============================================================================
# Build Summary
# ============================================================================
message(STATUS "==============================================")
message(STATUS "Bio-Vault Core C++ Configuration Summary")
message(STATUS "==============================================")
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "OpenCV: ${HAVE_OPENCV}")
if(HAVE_OPENCV)
    message(STATUS "  - Version: ${OpenCV_VERSION}")
endif()
message(STATUS "libsodium: ${HAVE_LIBSODIUM}")
message(STATUS "BLAKE3: ${HAVE_BLAKE3}")
message(STATUS "")
if(NOT HAVE_LIBSODIUM)
    message(WARNING "⚠️  SECURITY WARNING: Building with mock crypto!")
    message(WARNING "⚠️  Install libsodium for production use")
    message(WARNING "⚠️  vcpkg install libsodium / brew install libsodium / apt install libsodium-dev")
endif()
message(STATUS "==============================================")

# ============================================================================
# Unit Tests (Optional)
# ============================================================================
option(BUILD_TESTS "Build unit tests" OFF)

if(BUILD_TESTS)
    enable_testing()
    
    add_executable(biovault_tests test/biovault_tests.cpp ${SOURCES})
    
    # Same compile definitions as the library
    if(HAVE_OPENCV)
        target_compile_definitions(biovault_tests PRIVATE HAVE_OPENCV)
    endif()
    if(HAVE_LIBSODIUM)
        target_compile_definitions(biovault_tests PRIVATE HAVE_LIBSODIUM)
    endif()
    if(HAVE_BLAKE3)
        target_compile_definitions(biovault_tests PRIVATE HAVE_BLAKE3)
    endif()
    
    target_include_directories(biovault_tests PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        $<$<BOOL:${HAVE_LIBSODIUM}>:${LIBSODIUM_INCLUDE_DIR}>
        $<$<BOOL:${HAVE_BLAKE3}>:${BLAKE3_INCLUDE_DIR}>
    )
    
    target_link_libraries(biovault_tests PRIVATE
        $<$<BOOL:${HAVE_OPENCV}>:${OpenCV_LIBS}>
        $<$<BOOL:${HAVE_LIBSODIUM}>:${LIBSODIUM_LIBRARY}>
        $<$<BOOL:${HAVE_BLAKE3}>:${BLAKE3_LIBRARY}>
    )
    
    add_test(NAME BioVaultCppTests COMMAND biovault_tests)
    
    message(STATUS "Unit tests: ENABLED (build target: biovault_tests)")
endif()
