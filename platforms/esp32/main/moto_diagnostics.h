#pragma once
#include <cstdint>
#include "moto/ble_protocol/ble_protocol.hpp"

// Diagnostic events are deliberately numeric: no GPS coordinates or streets.
enum class MotoDiagEvent : std::uint16_t {
  Boot = 1, Link = 2, Snapshot = 3, MapScene = 4, Render = 5,
  RenderPhase = 6, RenderLockTimeout = 7
};

// Runtime OFF is the default: no flash, no queueing, no BLE traffic and
// no frame-by-frame work except the producer's atomic enabled check.
#ifdef ESP_PLATFORM
void moto_diag_init() noexcept;
void moto_diag_set_mode(std::uint8_t mode) noexcept;
std::uint8_t moto_diag_mode() noexcept;
void moto_diag_event(MotoDiagEvent event, std::int32_t a = 0,
                     std::int32_t b = 0, std::int32_t c = 0,
                     std::int32_t d = 0, std::int32_t e = 0) noexcept;
bool moto_diag_take_batch(moto::ble::DiagnosticBatch& batch) noexcept;
#else
inline void moto_diag_init() noexcept {}
inline void moto_diag_set_mode(std::uint8_t) noexcept {}
inline std::uint8_t moto_diag_mode() noexcept { return 0; }
inline void moto_diag_event(MotoDiagEvent, std::int32_t = 0,
                            std::int32_t = 0, std::int32_t = 0,
                            std::int32_t = 0, std::int32_t = 0) noexcept {}
inline bool moto_diag_take_batch(moto::ble::DiagnosticBatch&) noexcept {
  return false;
}
#endif
