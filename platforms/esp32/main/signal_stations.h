#pragma once

namespace moto::signal {
struct RadioStation {
  const char* name;
  const char* webpage;
  const char* playlist_url;
  bool requires_hls;
};

// User's first radio. Playlist from a third-party directory; may change.
// The website is NOT itself an audio stream. No HLS decoder is active yet.
constexpr RadioStation kFirstStation{
    "Radio Capital Classic Rock",
    "https://www.capital.it/webradio/02/",
    "https://streamcdng7-4c4b867c89244861ac216426883d1ad0.msvdn.net/webradio/capitalclassicrock/live.m3u8",
    true,
};
}  // namespace moto::signal
