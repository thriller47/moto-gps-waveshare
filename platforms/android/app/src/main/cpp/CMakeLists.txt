cmake_minimum_required(VERSION 3.22.1)

project("motogps_jni")

# Include the shared directories
set(SHARED_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../shared)

# Set up shared core build flags
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add the shared ble protocol target
add_subdirectory(${SHARED_DIR}/ble_protocol ${CMAKE_CURRENT_BINARY_DIR}/ble_protocol)

add_library(
        motogps_jni
        SHARED
        moto_ble_protocol_bridge.cpp)

target_link_libraries(
        motogps_jni
        moto_ble_protocol
        log)

# Required for 16 KiB memory-page Android devices. This protects our
# libmotogps_jni.so independent of whichever NDK a developer has locally;
# third-party prebuilt .so files must be built separately with this support.
target_link_options(motogps_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
