#pragma once
#include <cstdint>

namespace moto::signal {

// SIGNAL's Apache-2.0 Espressif ES8311 coefficient table contains these four
// 256*fs combinations, all with pre-divider 1 and 16x oversampling.
// Never advertise a rate without a verified matching codec coefficient.
constexpr bool codec_rate_supported(std::uint32_t hz) noexcept {
  return hz == 22050 || hz == 32000 || hz == 44100 || hz == 48000;
}
constexpr std::uint32_t codec_mclk_hz(std::uint32_t hz) noexcept {
  return codec_rate_supported(hz) ? hz * 256U : 0U;
}
}  // namespace moto::signal
