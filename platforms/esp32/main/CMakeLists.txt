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

# Stability A/B: avoid all SPI flash writes/scans while routing and LVGL
# continuously render from PSRAM. The 256 KiB partition is preserved.
# Enable only in a controlled bench build after diagnosing the freezes.
option(MOTO_ESP_DIAG_FLASH_ENABLED "Enable persistent ESP32 flash diagnostic logging (experimental)" OFF)
set(MOTO_ESP_DIAG_SOURCES "")
if(MOTO_ESP_DIAG_FLASH_ENABLED)
    list(APPEND MOTO_ESP_DIAG_SOURCES "moto_diagnostics.cpp")
endif()

# The shared UI source is compiled directly into this ESP-IDF component. Do not
# copy it into platforms/esp32: Web and firmware must execute the same code.
idf_component_register(
    SRCS
        "app_main.cpp"
        "ble_nav_transport_nimble.cpp"
        "board_port_waveshare_1_75c.cpp"
        "motion_heading_sensor.cpp"
        ${MOTO_ESP_DIAG_SOURCES}
        "phone_nav_bridge.cpp"
        "signal_audio_output.cpp"
        "signal_es8311_codec.cpp"
        "signal_radio_audio.cpp"
        "signal_audio_selftest.cpp"
        "signal_wifi.cpp"
        "signal_wifi_console.cpp"
        "signal_radio_stream.cpp"
        "signal_radio_ui.cpp"
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
        esp-tls
        esp_wifi
        esp_event
        esp_netif
        esp_hw_support
        freertos
        log
        lvgl
        esp_lcd_co5300
        nvs_flash
        spi_flash
        bt
        esp32_s3_touch_amoled_1_75c
)

if(MOTO_ESP_DIAG_FLASH_ENABLED)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTO_ESP_DIAG_FLASH_ENABLED=1)
else()
    target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTO_ESP_DIAG_FLASH_ENABLED=0)
endif()

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

# Integrated GPS / radio boot: GPS starts on cold boot; the RADIO/GPS touch
# buttons select the alternate isolated mode through software restart.
# The radio-only lab boot is preserved as an explicit opt-in fallback.
option(MOTO_SIGNAL_MODE_SWITCH "Enable isolated GPS/radio mode switching with controlled restart" ON)
option(MOTO_SIGNAL_WIFI_LAB_BOOT "Boot into radio Wi-Fi lab console instead of GPS UI" OFF)
if(MOTO_SIGNAL_MODE_SWITCH)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTO_SIGNAL_MODE_SWITCH=1 MOTO_SIGNAL_WIFI_LAB_BOOT=1 MOTO_SIGNAL_RADIO_TOUCH_UI=1)
endif()
# Touch radio UI is enabled automatically when switching modes.
# This option still applies to isolated radio-lab builds.
option(MOTO_SIGNAL_RADIO_TOUCH_UI "Start radio touch UI in lab mode" OFF)
if(MOTO_SIGNAL_RADIO_TOUCH_UI AND MOTO_SIGNAL_WIFI_LAB_BOOT AND NOT MOTO_SIGNAL_MODE_SWITCH)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTO_SIGNAL_RADIO_TOUCH_UI=1)
endif()
if(MOTO_SIGNAL_WIFI_LAB_BOOT AND NOT MOTO_SIGNAL_MODE_SWITCH)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTO_SIGNAL_WIFI_LAB_BOOT=1)
endif()

# Verified speaker test (Waveshare BSP); only used in GPS-integrated builds.
option(MOTO_SIGNAL_AUDIO_BOOT_TEST "Run one-shot speaker test with GPS UI" OFF)
if(MOTO_SIGNAL_AUDIO_BOOT_TEST AND NOT MOTO_SIGNAL_WIFI_LAB_BOOT)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTO_SIGNAL_AUDIO_BOOT_TEST=1)
endif()
