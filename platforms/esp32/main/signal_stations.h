#pragma once

namespace moto::signal {
enum class StreamFormat { DirectMp3, Hls };

struct RadioStation {
  const char* name;
  const char* webpage;
  const char* stream_url;
  StreamFormat format;
};

// First test station is already among SIGNAL's original seeded defaults.
// The station's own website publishes direct MP3 endpoints. Prefer an EU
// server for testing from Italy; no playlist/HLS parsing is necessary.
constexpr RadioStation kFirstStation{
    "Radio Paradise - Main Mix",
    "https://radioparadise.com/",
    "http://stream-uk1.radioparadise.com/mp3-128",
    StreamFormat::DirectMp3,
};

// User-requested Radio Capital Classic Rock is retained for a later HLS
// milestone. This external playlist URL is not yet device-verified.
constexpr RadioStation kCapitalClassicRock{
    "Radio Capital Classic Rock",
    "https://www.capital.it/webradio/02/",
    "https://streamcdng7-4c4b867c89244861ac216426883d1ad0.msvdn.net/webradio/capitalclassicrock/live.m3u8",
    StreamFormat::Hls,
};
}  // namespace moto::signal
