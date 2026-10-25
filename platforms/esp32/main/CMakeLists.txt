set(MOTO_REPOSITORY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../..")
set(MOTO_SHARED_UI_SOURCE
    "${MOTO_REPOSITORY_ROOT}/shared/nav_ui/src/moto_nav_ui.cpp")
set(MOTO_SHARED_UI_FONT
    "${MOTO_REPOSITORY_ROOT}/shared/nav_ui/assets/moto_font_nav_16.c")
set(MOTO_SHARED_UI_INCLUDE
    "${MOTO_REPOSITORY_ROOT}/shared/nav_ui/include")
set(MOTO_SHARED_NAV_PRESENTER_SOURCE
    "${MOTO_REPOSITORY_ROOT}/shared/nav_presenter/src/moto_nav_presenter.cpp")
set(MOTO_SHARED_BLE_PROTOCOL_SOURCE
    "${MOTO_REPOSITORY_ROOT}/shared/ble_protocol/src/ble_protocol.cpp")

# The shared UI source is compiled directly into this ESP-IDF component. Do not
# copy it into platforms/esp32: Web and firmware must execute the same code.
idf_component_register(
    SRCS
        "app_main.cpp"
        "ble_nav_transport_nimble.cpp"
        "board_port_waveshare_1_75c.cpp"
        "motion_heading_sensor.cpp"
        "phone_nav_bridge.cpp"
        "signal_audio_output.cpp"
        "signal_es8311_codec.cpp"
        "signal_radio_audio.cpp"
        "signal_audio_selftest.cpp"
        "signal_wifi.cpp"
        "signal_wifi_console.cpp"
        "signal_radio_stream.cpp"
        "${MOTO_SHARED_BLE_PROTOCOL_SOURCE}"
        "${MOTO_SHARED_NAV_PRESENTER_SOURCE}"
        "${MOTO_SHARED_UI_FONT}"
        "${MOTO_SHARED_UI_SOURCE}"
    INCLUDE_DIRS
        "."
        "${MOTO_REPOSITORY_ROOT}/shared"
        "${MOTO_REPOSITORY_ROOT}/shared/ble_protocol/include"
        "${MOTO_REPOSITORY_ROOT}/shared/nav_presenter/include"
        "${MOTO_SHARED_UI_INCLUDE}"
    REQUIRES
        esp_timer
        esp_driver_gpio
        esp_driver_i2c
        esp_driver_i2s
        esp_codec_dev
        esp_audio_codec
        esp_http_client
        esp_wifi
        esp_event
        esp_netif
        esp_hw_support
        freertos
        log
        lvgl
        esp_lcd_co5300
        nvs_flash
        bt
        esp32_s3_touch_amoled_1_75c
)

target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
target_compile_options(${COMPONENT_LIB} PRIVATE
    -Wall
    -Wextra
    # Vendor LVGL adapter headers use partial aggregate initializers that
    # ESP-IDF 6.1's compiler warns about. Keep the warning visible but nonfatal.
    $<$<COMPILE_LANGUAGE:CXX>:-Wno-error=missing-field-initializers>
    # ESP-IDF 6.1 uses GCC #include_next in esp_libc/assert.h.
    # -Wpedantic flags that SDK header and becomes fatal with -Werror.
    # Retain -Wall/-Wextra without enforcing pedantic on vendor headers.
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>
)

# Experimental SIGNAL firmware builds in RADIO-ONLY LAB mode by default:
# no navigation/BLE/LVGL starts in this diagnostic image. This is temporary
# and must be disabled for any road/navigation use.
option(MOTO_SIGNAL_WIFI_LAB_BOOT "Boot into radio Wi-Fi lab console instead of GPS UI" ON)
if(MOTO_SIGNAL_WIFI_LAB_BOOT)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTO_SIGNAL_WIFI_LAB_BOOT=1)
endif()

# Verified speaker test (Waveshare BSP); only used in GPS-integrated builds.
option(MOTO_SIGNAL_AUDIO_BOOT_TEST "Run one-shot speaker test with GPS UI" OFF)
if(MOTO_SIGNAL_AUDIO_BOOT_TEST AND NOT MOTO_SIGNAL_WIFI_LAB_BOOT)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTO_SIGNAL_AUDIO_BOOT_TEST=1)
endif()
