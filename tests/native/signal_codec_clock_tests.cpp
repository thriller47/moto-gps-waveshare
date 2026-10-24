#include <cassert>
#include "signal_codec_clock.h"

int main() {
  using moto::signal::codec_rate_supported;
  using moto::signal::codec_mclk_hz;
  assert(codec_rate_supported(22050));
  assert(codec_rate_supported(32000));
  assert(codec_rate_supported(44100));
  assert(codec_rate_supported(48000));
  assert(codec_mclk_hz(22050) == 5644800U);
  assert(codec_mclk_hz(32000) == 8192000U);
  assert(codec_mclk_hz(44100) == 11289600U);
  assert(codec_mclk_hz(48000) == 12288000U);
  assert(!codec_rate_supported(96000));
  assert(codec_mclk_hz(96000) == 0);
  return 0;
}
