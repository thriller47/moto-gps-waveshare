#pragma once
#include <cstddef>

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


// Direct MP3 presets. These URLs still need station-by-station device testing.
constexpr RadioStation kMp3Stations[] = {
    kFirstStation,
    {"Radio Paradise - Mellow Mix", "https://radioparadise.com/player/info/mellow",
     "http://stream.radioparadise.com/mellow-192", StreamFormat::DirectMp3},
    {"Radio Paradise - Rock Mix", "https://radioparadise.com/player/info/rock",
     "http://stream.radioparadise.com/rock-192", StreamFormat::DirectMp3},
    {"Virgin Radio - Rock 70", "https://www.radio-italiane.it/virgin-radio-rock-70",
     "http://icy.unitedradio.it/VirginRock70.mp3", StreamFormat::DirectMp3},
    {"Virgin Radio - Rock Ballads",
     "http://e.radio-italiane.it/embed/virgin-radio-rock-ballads-463721",
     "http://icy.unitedradio.it/Virgin_06.mp3", StreamFormat::DirectMp3},
    {"Radio Paradise - Rock Mix 128", "https://radioparadise.com/player/info/rock",
     "http://stream.radioparadise.com/rock-128", StreamFormat::DirectMp3},
    {"Virgin Radio Italia", "", "http://icecast.unitedradio.it/Virgin.mp3", StreamFormat::DirectMp3},
    {"Virgin Radio Hard Rock", "", "http://icy.unitedradio.it/VirginHardRock.mp3", StreamFormat::DirectMp3},
    {"Radiofreccia", "", "https://streamingv2.shoutcast.com/radiofreccia", StreamFormat::DirectMp3},
    {"Radio Marilu", "", "http://ice02.fluidstream.net/marilu.mp3", StreamFormat::DirectMp3},
    {"KEXP 90.3 FM Seattle", "", "https://kexp-mp3-128.streamguys1.com/kexp128.mp3", StreamFormat::DirectMp3},
    {"Radio Caroline UK", "", "http://sc6.radiocaroline.net:8040/stream.mp3", StreamFormat::DirectMp3},
    {"OUI FM France", "", "http://stream.ouifm.fr/ouifm-high.mp3", StreamFormat::DirectMp3},
    {"STAR FM Berlin", "", "https://stream.starfm.de/berlin/mp3-192/", StreamFormat::DirectMp3},
    {"STAR FM Rock Ballads", "", "https://stream.starfm.de/ballads/mp3-128/", StreamFormat::DirectMp3},
    {"SomaFM Metal Detector", "", "http://ice1.somafm.com/metal-128-mp3", StreamFormat::DirectMp3},
    {"SomaFM Indie Pop Rocks!", "", "http://ice1.somafm.com/indiepop-128-mp3", StreamFormat::DirectMp3},
    {"SomaFM Underground 80s", "", "http://ice1.somafm.com/u80s-256-mp3", StreamFormat::DirectMp3},
    {"181.fm The Eagle", "", "https://listen.181fm.com/181-eagle_128k.mp3", StreamFormat::DirectMp3},
    {"181.fm The Buzz", "", "https://listen.181fm.com/181-buzz_128k.mp3", StreamFormat::DirectMp3},
};
constexpr std::size_t kMp3StationCount = sizeof(kMp3Stations) / sizeof(kMp3Stations[0]);

// User-requested Radio Capital Classic Rock is retained for a later HLS
// milestone. This external playlist URL is not yet device-verified.
constexpr RadioStation kCapitalClassicRock{
    "Radio Capital Classic Rock",
    "https://www.capital.it/webradio/02/",
    "https://streamcdng7-4c4b867c89244861ac216426883d1ad0.msvdn.net/webradio/capitalclassicrock/live.m3u8",
    StreamFormat::Hls,
};
}  // namespace moto::signal
