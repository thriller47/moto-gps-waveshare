#include <cassert>
#include <cstring>
#include "signal_serial_line.h"

int main() {
  using Line = moto::signal::SerialLine<160>;
  Line input;

  // Native USB can return EOF between bursts; a caller simply does not call
  // push() for those empty reads. Partial bytes must remain intact.
  const char* first = "wifi set Galaxy";
  for (const char* p = first; *p; ++p)
    assert(input.push(*p) == Line::Result::Pending);
  assert(input.length() == std::strlen(first));

  const char* second = " S24 FE|temporary123\r\n";
  bool complete = false;
  for (const char* p = second; *p; ++p) {
    if (input.push(*p) == Line::Result::Complete) {
      complete = true;
      break;
    }
  }
  assert(complete);
  assert(std::strcmp(input.data(), "wifi set Galaxy S24 FE|temporary123") == 0);

  char* ssid = nullptr;
  char* password = nullptr;
  assert(moto::signal::parse_wifi_credentials(input.data() + 9, ssid, password));
  assert(std::strcmp(ssid, "Galaxy S24 FE") == 0);
  assert(std::strcmp(password, "temporary123") == 0);
  input.clear();

  // Ignore CRLF's extra newline; each new command must be separate.
  assert(input.push('\n') == Line::Result::Pending);
  const char* status = "wifi status\n";
  for (const char* p = status; *p; ++p) {
    auto result = input.push(*p);
    if (p[1] == '\0') assert(result == Line::Result::Complete);
  }
  assert(std::strcmp(input.data(), "wifi status") == 0);
  input.clear();

  // Support quoted SSID and the original no-space syntax.
  char quoted[] = "\"Galaxy S24 FE\" temporary123";
  assert(moto::signal::parse_wifi_credentials(quoted, ssid, password));
  assert(std::strcmp(ssid, "Galaxy S24 FE") == 0);
  assert(std::strcmp(password, "temporary123") == 0);
  char legacy[] = "TestHotspot temporary123";
  assert(moto::signal::parse_wifi_credentials(legacy, ssid, password));
  assert(std::strcmp(ssid, "TestHotspot") == 0);

  // Overflow drops the whole line safely and accepts the next one.
  for (int i = 0; i < 200; ++i)
    assert(input.push('x') == Line::Result::Pending);
  assert(input.push('\n') == Line::Result::TooLong);
  assert(input.length() == 0);
  for (const char* p = status; *p; ++p) input.push(*p);
  assert(std::strcmp(input.data(), "wifi status") == 0);
}
