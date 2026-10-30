#pragma once
#include <cstdint>

// Compact, anonymized persistent ring buffer in the diagnostic flash partition.
enum class MotoDiagEvent : std::uint16_t {
  Boot = 1, Link = 2, Snapshot = 3, MapScene = 4, Render = 5
};
void moto_diag_init() noexcept;
void moto_diag_event(MotoDiagEvent event, std::int32_t a = 0,
                     std::int32_t b = 0, std::int32_t c = 0,
                     std::int32_t d = 0, std::int32_t e = 0) noexcept;
void moto_diag_flush() noexcept;
