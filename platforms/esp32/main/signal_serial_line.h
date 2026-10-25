#pragma once
#include <cstddef>
#include <cstring>

// Portable, allocation-free USB serial line assembly + argument parsing.
// A temporary EOF from nonblocking USB stdin means "no byte yet", NOT a line
// boundary. Feed only actual characters; keep partial lines between reads.
namespace moto::signal {

template <std::size_t Capacity>
class SerialLine {
 public:
  static_assert(Capacity >= 4, "Serial line capacity too small");
  enum class Result { Pending, Complete, TooLong };

  Result push(char ch) noexcept {
    if (ch == '\r' || ch == '\n') {
      if (overflow_) {
        clear();
        return Result::TooLong;
      }
      if (length_ == 0) return Result::Pending;  // Ignore the LF of CRLF.
      line_[length_] = '\0';
      return Result::Complete;
    }
    if (overflow_) return Result::Pending;
    if (length_ + 1 >= Capacity) {
      overflow_ = true;
      return Result::Pending;
    }
    line_[length_++] = ch;
    return Result::Pending;
  }

  char* data() noexcept { return line_; }
  std::size_t length() const noexcept { return length_; }
  void clear() noexcept {
    std::memset(line_, 0, sizeof(line_));
    length_ = 0;
    overflow_ = false;
  }

 private:
  char line_[Capacity]{};
  std::size_t length_ = 0;
  bool overflow_ = false;
};

inline char* skip_spaces(char* str) noexcept {
  while (*str == ' ' || *str == '\t') ++str;
  return str;
}

inline void trim_right(char* str) noexcept {
  std::size_t length = std::strlen(str);
  while (length != 0 && (str[length - 1] == ' ' || str[length - 1] == '\t')) {
    str[--length] = '\0';
  }
}

// Supports wifi set SSID PASSWORD, wifi set "SSID with spaces" PASSWORD,
// and wifi set SSID with spaces|PASSWORD (preferred for a Samsung hotspot).
// Modifies the input in place, avoiding heap use and password copies.
inline bool parse_wifi_credentials(char* arguments, char*& ssid,
                                   char*& password) noexcept {
  ssid = skip_spaces(arguments);
  password = nullptr;
  if (*ssid == '"') {
    ++ssid;
    char* quote = std::strchr(ssid, '"');
    if (!quote) return false;
    *quote = '\0';
    password = skip_spaces(quote + 1);
  } else {
    char* separator = std::strchr(ssid, '|');
    if (separator) {
      *separator++ = '\0';
      trim_right(ssid);
      password = separator;
    } else {
      separator = std::strpbrk(ssid, " \t");
      if (!separator) return false;
      *separator++ = '\0';
      password = skip_spaces(separator);
    }
  }
  return *ssid != '\0' && password && *password != '\0';
}

}  // namespace moto::signal
