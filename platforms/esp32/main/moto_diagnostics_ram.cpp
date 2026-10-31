#include "moto_diagnostics.h"

#include <atomic>
#include <cstddef>
#include "esp_attr.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/portmacro.h"

// No NVS, filesystem, partition scan, erase or flash write. Numeric event
// samples only. The buffer occupies 64 KiB of external PSRAM.
namespace {
constexpr std::size_t kCapacity = 2048;
constexpr std::uint32_t kCaptureTimeoutMs = 10U * 60U * 1'000U;
EXT_RAM_BSS_ATTR moto::ble::DiagnosticRecord kRing[kCapacity];
portMUX_TYPE ring_lock = portMUX_INITIALIZER_UNLOCKED;
std::atomic<std::uint8_t> mode{0};
std::atomic<std::uint32_t> expires_at_ms{0};
std::uint32_t next_sequence = 1;
std::size_t tail = 0, count = 0;

bool enabled() noexcept {
  if (mode.load(std::memory_order_relaxed) == 0) return false;
  const auto now = static_cast<std::uint32_t>(esp_timer_get_time() / 1000);
  if (static_cast<std::int32_t>(
          now - expires_at_ms.load(std::memory_order_relaxed)) >= 0) {
    mode.store(0, std::memory_order_relaxed);
    return false;
  }
  return true;
}
}
void moto_diag_init() noexcept {
  // Deliberately empty: avoid scanning or initializing flash on boot.
}
void moto_diag_set_mode(std::uint8_t requested) noexcept {
  mode.store(0, std::memory_order_relaxed);
  portENTER_CRITICAL(&ring_lock);
  tail = 0;
  count = 0;
  portEXIT_CRITICAL(&ring_lock);
  if (requested == 1 || requested == 2) {
    expires_at_ms.store(
        static_cast<std::uint32_t>(esp_timer_get_time() / 1000) +
            kCaptureTimeoutMs,
        std::memory_order_relaxed);
    mode.store(requested, std::memory_order_release);
  }
}
std::uint8_t moto_diag_mode() noexcept {
  return enabled() ? mode.load(std::memory_order_relaxed) : 0;
}
void moto_diag_event(MotoDiagEvent event, std::int32_t a,
                     std::int32_t b, std::int32_t c,
                     std::int32_t d, std::int32_t e) noexcept {
  if (!enabled()) return;
  // BASE is intentionally sparse; DEBUG collects all existing numeric events.
  if (mode.load(std::memory_order_relaxed) == 1 &&
      event != MotoDiagEvent::Boot && event != MotoDiagEvent::Link &&
      event != MotoDiagEvent::RenderLockTimeout) return;
  moto::ble::DiagnosticRecord record;
  record.uptime_ms = static_cast<std::uint32_t>(esp_timer_get_time() / 1000);
  record.event = static_cast<std::uint16_t>(event);
  record.values[0] = a; record.values[1] = b; record.values[2] = c;
  record.values[3] = d; record.values[4] = e;
  portENTER_CRITICAL(&ring_lock);
  record.sequence = next_sequence++;
  if (count == kCapacity) {
    tail = (tail + 1) % kCapacity;
    --count;
  }
  kRing[(tail + count) % kCapacity] = record;
  ++count;
  portEXIT_CRITICAL(&ring_lock);
}
bool moto_diag_take_batch(moto::ble::DiagnosticBatch& out) noexcept {
  if (!enabled()) return false;
  // The real time task only ever transmits one <=142-byte BLE frame/second.
  // Never invoke std::vector or the heap while holding the spinlock.
  moto::ble::DiagnosticRecord local[4]{};
  portENTER_CRITICAL(&ring_lock);
  const std::size_t n = count < 4 ? count : 4;
  for (std::size_t i = 0; i < n; ++i) {
    local[i] = kRing[tail];
    tail = (tail + 1) % kCapacity;
    --count;
  }
  portEXIT_CRITICAL(&ring_lock);
  for (std::size_t i = 0; i < n; ++i) out.records.push_back(local[i]);
  return n != 0;
}
