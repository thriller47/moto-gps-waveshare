#include "moto_perf_math.hpp"
#include <cstdint>
#include <cstdio>

int main() {
  std::uint64_t d = 999;
  if(!moto_perf::delta(100, 5100, d) || d != 5000) return 1;
  if(moto_perf::delta(5100, 100, d) || d != 0) return 2;
  if(moto_perf::percentage(0, 5000000) != 0) return 3;
  if(moto_perf::percentage(2500000, 5000000) != 50) return 4;
  if(moto_perf::percentage(5000000, 5000000) != 100) return 5;
  if(moto_perf::percentage(6000000, 5000000) != 100) return 6;
  if(moto_perf::busy_from_idle(4000000, 5000000) != 20) return 7;
  if(moto_perf::busy_from_idle(0, 5000000) != 100) return 8;
  std::puts("moto perf interval math passed");
  return 0;
}
