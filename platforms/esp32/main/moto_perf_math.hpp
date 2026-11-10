#pragma once
#include <cstdint>

// Pure arithmetic for interval-based FreeRTOS runtime statistics.
namespace moto_perf {
inline bool delta(std::uint64_t before, std::uint64_t after, std::uint64_t &out) {
  if(after < before) { out = 0; return false; }
  out = after - before;
  return true;
}
inline unsigned percentage(std::uint64_t active, std::uint64_t period) {
  if(period == 0) return 0;
  if(active >= period) return 100;
  return static_cast<unsigned>((active * 100ULL + period / 2) / period);
}
inline unsigned busy_from_idle(std::uint64_t idle, std::uint64_t period) {
  return 100U - percentage(idle, period);
}
} // namespace moto_perf
