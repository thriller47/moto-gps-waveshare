#include "moto_diagnostics.h"
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "esp_attr.h"
#include "esp_log.h"
#include "esp_partition.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/portmacro.h"

namespace {
constexpr char kTag[] = "moto_diag";
constexpr std::uint32_t kMagic = 0x4D4F544FU;
constexpr std::size_t kRecordSize = 64;
constexpr std::uint32_t kSectorSize = 4096;

struct Record {
  std::uint32_t magic, sequence, uptime_ms;
  std::uint16_t event, version;
  std::int32_t values[5];
  std::uint32_t checksum;
  std::uint8_t reserved[24];
};
static_assert(sizeof(Record) == kRecordSize, "diagnostic record ABI");
static_assert(offsetof(Record, checksum) == 36, "checksum offset");

const esp_partition_t* partition = nullptr;
portMUX_TYPE queue_lock = portMUX_INITIALIZER_UNLOCKED;
constexpr int kQueueCount = 32;
EXT_RAM_BSS_ATTR Record records[kQueueCount];
int read_pos = 0, write_pos = 0, queued = 0;
std::uint32_t next_sequence = 1;
std::uint32_t next_offset = 0;

std::uint32_t fnv(const Record& record) {
  const auto* bytes = reinterpret_cast<const std::uint8_t*>(&record);
  std::uint32_t hash = 2166136261U;
  for (std::size_t i = 0; i < offsetof(Record, checksum); ++i)
    hash = (hash ^ bytes[i]) * 16777619U;
  return hash;
}
bool valid(const Record& r) {
  return r.magic == kMagic && r.version == 1U && r.checksum == fnv(r);
}
bool blank(const Record& r) {
  const auto* data = reinterpret_cast<const std::uint8_t*>(&r);
  for (std::size_t i = 0; i < sizeof(r); ++i) if (data[i] != 0xff) return false;
  return true;
}
}

void moto_diag_init() noexcept {
  partition = esp_partition_find_first(ESP_PARTITION_TYPE_DATA,
                                        ESP_PARTITION_SUBTYPE_ANY, "diag");
  if (!partition || partition->size < kSectorSize ||
      partition->size % kSectorSize != 0) {
    ESP_LOGW(kTag, "persistent diagnostics partition unavailable");
    partition = nullptr;
    return;
  }
  // Fixed-size scan at startup, no heap allocation or NVS overhead.
  std::uint32_t best_seq = 0, best_offset = 0;
  for (std::uint32_t offset = 0; offset < partition->size; offset += kRecordSize) {
    Record record{};
    if (esp_partition_read(partition, offset, &record, sizeof(record)) != ESP_OK)
      break;
    if (valid(record) && record.sequence > best_seq) {
      best_seq = record.sequence;
      best_offset = offset;
    }
  }
  next_sequence = best_seq + 1U;
  next_offset = best_seq ? (best_offset + kRecordSize) % partition->size : 0;
  ESP_LOGI(kTag, "road-test trace ready, %lu KiB, sequence=%lu",
           static_cast<unsigned long>(partition->size / 1024U),
           static_cast<unsigned long>(next_sequence));
}

void moto_diag_event(MotoDiagEvent event, std::int32_t a, std::int32_t b,
                     std::int32_t c, std::int32_t d, std::int32_t e) noexcept {
  if (!partition) return;
  Record record{};
  record.magic = kMagic;
  record.uptime_ms = static_cast<std::uint32_t>(esp_timer_get_time() / 1000);
  record.version = 1;
  record.event = static_cast<std::uint16_t>(event);
  record.values[0] = a; record.values[1] = b; record.values[2] = c;
  record.values[3] = d; record.values[4] = e;
  std::memset(record.reserved, 0xff, sizeof(record.reserved));
  portENTER_CRITICAL(&queue_lock);
  if (queued < kQueueCount) {
    records[write_pos] = record;
    write_pos = (write_pos + 1) % kQueueCount;
    ++queued;
  }
  portEXIT_CRITICAL(&queue_lock);
}

void moto_diag_flush() noexcept {
  if (!partition) return;
  Record record{};
  bool available = false;
  portENTER_CRITICAL(&queue_lock);
  if (queued > 0) {
    record = records[read_pos];
    read_pos = (read_pos + 1) % kQueueCount;
    --queued;
    available = true;
  }
  portEXIT_CRITICAL(&queue_lock);
  if (!available) return;

  record.sequence = next_sequence;
  record.checksum = fnv(record);
  esp_err_t status = ESP_OK;
  if (next_offset % kSectorSize == 0) {
    status = esp_partition_erase_range(partition, next_offset, kSectorSize);
  } else {
    Record old{};
    status = esp_partition_read(partition, next_offset, &old, sizeof(old));
    if (status == ESP_OK && !blank(old)) {
      // Torn record after power loss: skip remainder of its sector.
      next_offset = (next_offset + kSectorSize) & ~(kSectorSize - 1U);
      if (next_offset >= partition->size) next_offset = 0;
      status = esp_partition_erase_range(partition, next_offset, kSectorSize);
    }
  }
  if (status == ESP_OK)
    status = esp_partition_write(partition, next_offset, &record, sizeof(record));
  if (status == ESP_OK) {
    ++next_sequence;
    next_offset = (next_offset + kRecordSize) % partition->size;
  }
}
