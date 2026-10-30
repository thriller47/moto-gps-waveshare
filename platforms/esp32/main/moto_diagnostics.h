#pragma once
#include <cstdint>

// Compact, anonymized persistent ring buffer in the diagnostic flash partition.
enum class MotoDiagEvent : std::uint16_t {
  Boot = 1, Link = 2, Snapshot = 3, MapScene = 4, Render = 5,
  RenderPhase = 6, RenderLockTimeout = 7
};
// Flash diagnostics are OFF by default for the road-test A/B isolation.
// Keep call sites, but compile them to true no-ops (no queues, PSRAM
// accesses, partition scans, flash erases or writes).
#if defined(MOTO_ESP_DIAG_FLASH_ENABLED) && MOTO_ESP_DIAG_FLASH_ENABLED
void moto_diag_init() noexcept;
void moto_diag_event(MotoDiagEvent event, std::int32_t a = 0,
                     std::int32_t b = 0, std::int32_t c = 0,
                     std::int32_t d = 0, std::int32_t e = 0) noexcept;
void moto_diag_flush() noexcept;
#else
inline void moto_diag_init() noexcept {}
inline void moto_diag_event(MotoDiagEvent, std::int32_t = 0,
                            std::int32_t = 0, std::int32_t = 0,
                            std::int32_t = 0, std::int32_t = 0) noexcept {}
inline void moto_diag_flush() noexcept {}
#endif
