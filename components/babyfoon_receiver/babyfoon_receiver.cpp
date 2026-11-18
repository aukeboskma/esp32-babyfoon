#include "babyfoon_receiver.h"

#ifdef USE_ESP32

#include "esphome/components/audio/audio.h"
#include "esphome/core/hal.h"
#include "esphome/core/log.h"

#include <lwip/netdb.h>
#include <lwip/sockets.h>

#include <algorithm>
#include <cmath>
#include <cstring>

namespace esphome::babyfoon_receiver {

static const char *const TAG = "babyfoon_receiver";

static const uint32_t SAMPLE_RATE = 16000;
static const size_t FRAME_SAMPLES = 320;  // 20 ms
static const size_t FRAME_BYTES = FRAME_SAMPLES * 2;

struct TalkArgs {
  BabyfoonReceiver *self;
  int index;
};

struct RxArgs {
  BabyfoonReceiver *self;
  Source *src;
};

// ---------------------------------------------------------------------
//  Hulpjes
// ---------------------------------------------------------------------
static bool send_all(int sock, const void *data, size_t len) {
  const uint8_t *p = (const uint8_t *) data;
  while (len > 0) {
    int sent = send(sock, p, len, 0);
    if (sent <= 0)
      return false;
    p += sent;
    len -= sent;
  }
  return true;
}

static bool recv_all(int sock, uint8_t *buf, size_t len) {
  size_t got = 0;
  while (got < len) {
    int r = recv(sock, buf + got, len - got, 0);
    if (r <= 0)
      return false;
    got += r;
  }
  return true;
}

/// Verbindt met host:port. Geeft socket of -1.
static int connect_to(const std::string &host, uint16_t port, int timeout_s) {
  struct addrinfo hints {};
  hints.ai_family = AF_INET;
  hints.ai_socktype = SOCK_STREAM;
  struct addrinfo *res = nullptr;
  char port_str[8];
  snprintf(port_str, sizeof(port_str), "%u", port);
  if (getaddrinfo(host.c_str(), port_str, &hints, &res) != 0 || res == nullptr)
    return -1;
  int sock = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
  if (sock < 0) {
    freeaddrinfo(res);
    return -1;
  }
  struct timeval tv {};
  tv.tv_sec = timeout_s;
  setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
  int one = 1;
  setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
  if (connect(sock, res->ai_addr, res->ai_addrlen) != 0) {
    close(sock);
    freeaddrinfo(res);
    return -1;
  }
  freeaddrinfo(res);
  return sock;
}

/// Leest de HTTP-statusregel + headers. Geeft de statuscode, of -1.
static int read_http_head(int sock) {
  char head[512];
  size_t got = 0;
  while (got < sizeof(head) - 1) {
    int r = recv(sock, head + got, 1, 0);  // byte voor byte: we mogen geen audio "opeten"
    if (r <= 0)
      return -1;
    got += r;
    head[got] = '\0';
    if (got >= 4 && memcmp(head + got - 4, "\r\n\r\n", 4) == 0)
      break;
  }
  int code = -1;
  if (sscanf(head, "HTTP/%*d.%*d %d", &code) != 1)
    return -1;
  return code;
}

// ---------------------------------------------------------------------
//  Setup / loop
// ---------------------------------------------------------------------
void BabyfoonReceiver::add_source(const std::string &name, const std::string &host, uint16_t port,
                                  const std::string &key, sensor::Sensor *level,
                                  binary_sensor::BinarySensor *connected) {
  auto s = std::make_unique<Source>();
  s->name = name;
  s->host = host;
  s->port = port;
  s->key = key;
  s->level_sensor = level;
  s->connected_sensor = connected;
  this->sources_.push_back(std::move(s));
}

void BabyfoonReceiver::setup() {
  // 1 s buffer per babyfoon; oude audio wordt overschreven (geen opbouwende vertraging)
  for (auto &s : this->sources_) {
    s->buffer = ring_buffer::RingBuffer::create(SAMPLE_RATE * 2);
    if (s->buffer == nullptr) {
      ESP_LOGE(TAG, "Geen geheugen voor %s", s->name.c_str());
      this->mark_failed();
      return;
    }
    // Bij het starten even respijt voordat "verbinding kwijt" telt
    s->last_data_ms = millis() + 5000;
  }

  if (this->speaker_ != nullptr) {
    this->speaker_->set_audio_stream_info(audio::AudioStreamInfo(16, 1, SAMPLE_RATE));
  }

  if (this->mic_source_ != nullptr) {
    this->talk_buffer_ = ring_buffer::RingBuffer::create(SAMPLE_RATE * 2 / 2);  // 0,5 s
    this->mic_source_->add_data_callback([this](const std::vector<uint8_t> &data) {
      if (this->talk_index_.load() >= 0 && this->talk_buffer_ != nullptr)
        this->talk_buffer_->write(data.data(), data.size());
    });
  }

  for (auto &s : this->sources_) {
    auto *args = new RxArgs{this, s.get()};
    if (xTaskCreate(BabyfoonReceiver::receive_task_, "bf_rx", 4096, args, 5, &s->task) != pdPASS) {
      delete args;
      ESP_LOGE(TAG, "Kon ontvangsttaak voor %s niet starten", s->name.c_str());
    }
  }
  xTaskCreate(BabyfoonReceiver::mixer_task_, "bf_mix", 4096, this, 6, &this->mixer_task_handle_);
}

void BabyfoonReceiver::loop() {
  // Microfoon aan/uit vanuit de hoofdlus (thread-veilig)
  if (this->mic_source_ != nullptr) {
    bool want = this->mic_should_run_.load();
    if (want && !this->mic_running_) {
      this->mic_source_->start();
      this->mic_running_ = true;
    } else if (!want && this->mic_running_) {
      this->mic_source_->stop();
      this->mic_running_ = false;
    }
  }

  uint32_t now = millis();
  if (now - this->last_publish_ms_ < 500)
    return;
  this->last_publish_ms_ = now;
  for (int i = 0; i < (int) this->sources_.size(); i++) {
    auto &s = this->sources_[i];
    bool conn = this->is_connected(i);
    if (s->connected_sensor != nullptr)
      s->connected_sensor->publish_state(conn);  // publiceert alleen bij verandering
    if (s->level_sensor != nullptr) {
      float lvl = conn ? s->level_db.load() : NAN;
      if (std::isnan(lvl) != std::isnan(s->level_sensor->get_state()) ||
          std::fabs(lvl - s->level_sensor->get_state()) >= 0.5f)
        s->level_sensor->publish_state(lvl);
    }
  }
}

void BabyfoonReceiver::dump_config() {
  ESP_LOGCONFIG(TAG, "Babyfoon-ontvanger (versie %s):", RECEIVER_VERSION);
  ESP_LOGCONFIG(TAG, "  Alarm na: %u ms zonder geluid", (unsigned) this->alarm_after_ms_);
  ESP_LOGCONFIG(TAG, "  Versterking: %.1fx", this->gain_);
  ESP_LOGCONFIG(TAG, "  Terugpraten: %s", this->mic_source_ != nullptr ? "ja" : "nee");
  for (auto &s : this->sources_) {
    ESP_LOGCONFIG(TAG, "  - %s: %s:%u (sleutel: %s)", s->name.c_str(), s->host.c_str(), s->port,
                  s->key.empty() ? "geen" : "ja");
  }
}

bool BabyfoonReceiver::is_connected(int i) const {
  if (i < 0 || i >= (int) this->sources_.size())
    return false;
  auto &s = this->sources_[i];
  uint32_t last = s->last_data_ms.load();
  return (int32_t) (millis() - last) < (int32_t) this->alarm_after_ms_;
}

void BabyfoonReceiver::set_muted(int index, bool muted) {
  if (index >= 0 && index < (int) this->sources_.size())
    this->sources_[index]->muted = muted;
}

bool BabyfoonReceiver::is_muted(int index) const {
  if (index < 0 || index >= (int) this->sources_.size())
    return false;
  return this->sources_[index]->muted.load();
}

// ---------------------------------------------------------------------
//  Ontvangen (één taak per babyfoon)
// ---------------------------------------------------------------------
void BabyfoonReceiver::receive_task_(void *param) {
  auto *args = (RxArgs *) param;
  BabyfoonReceiver *self = args->self;
  Source *src = args->src;
  delete args;
  self->receive_loop_(src);
  vTaskDelete(nullptr);
}

void BabyfoonReceiver::receive_loop_(Source *src) {
  uint8_t buf[FRAME_BYTES];
  while (true) {
    src->streaming = false;
    int sock = connect_to(src->host, src->port, 4);
    if (sock < 0) {
      vTaskDelay(pdMS_TO_TICKS(1500));
      continue;
    }
    src->sock = sock;

    std::string req = "GET /audio.wav HTTP/1.0\r\nHost: " + src->host + "\r\n";
    if (!src->key.empty())
      req += "X-Babyfoon-Key: " + src->key + "\r\n";
    req += "\r\n";

    bool ok = send_all(sock, req.data(), req.size());
    int code = ok ? read_http_head(sock) : -1;
    if (code != 200) {
      if (code == 401)
        ESP_LOGW(TAG, "%s: toegangssleutel klopt niet", src->name.c_str());
      else if (code == 429)
        ESP_LOGW(TAG, "%s: tijdelijk geblokkeerd (te veel foute sleutels)", src->name.c_str());
      else if (code > 0)
        ESP_LOGW(TAG, "%s: antwoord %d", src->name.c_str(), code);
      ok = false;
    }

    uint8_t wav[44];
    if (ok && recv_all(sock, wav, sizeof(wav))) {
      uint32_t rate = wav[24] | (wav[25] << 8) | (wav[26] << 16) | ((uint32_t) wav[27] << 24);
      if (memcmp(wav, "RIFF", 4) != 0 || rate != SAMPLE_RATE) {
        ESP_LOGW(TAG, "%s: onverwacht audioformaat (%u Hz)", src->name.c_str(), (unsigned) rate);
        ok = false;
      }
    } else {
      ok = false;
    }

    if (ok) {
      ESP_LOGI(TAG, "%s: verbonden", src->name.c_str());
      src->streaming = true;
      double sum = 0;
      size_t count = 0;
      size_t pending = 0;  // halve sample van vorige recv
      while (true) {
        int r = recv(sock, buf + pending, sizeof(buf) - pending, 0);
        if (r <= 0)
          break;
        size_t n = pending + r;
        size_t even = n & ~((size_t) 1);
        src->last_data_ms = millis();

        const int16_t *samples = reinterpret_cast<const int16_t *>(buf);
        for (size_t i = 0; i < even / 2; i++) {
          sum += (double) samples[i] * samples[i];
        }
        count += even / 2;
        if (count >= SAMPLE_RATE / 10) {  // elke 100 ms een niveau
          double rms = std::sqrt(sum / count);
          src->level_db = 20.0f * std::log10(std::max(rms, 1.0) / 32768.0);
          sum = 0;
          count = 0;
        }
        src->buffer->write(buf, even);
        if (n & 1) {
          buf[0] = buf[n - 1];
          pending = 1;
        } else {
          pending = 0;
        }
      }
      ESP_LOGW(TAG, "%s: verbinding verbroken", src->name.c_str());
    }

    src->streaming = false;
    src->sock = -1;
    shutdown(sock, SHUT_RDWR);
    close(sock);
    vTaskDelay(pdMS_TO_TICKS(1500));
  }
}

// ---------------------------------------------------------------------
//  Mixen en afspelen (alle babyfoons samen naar de speaker)
// ---------------------------------------------------------------------
void BabyfoonReceiver::mixer_task_(void *param) {
  auto *self = (BabyfoonReceiver *) param;
  self->mixer_loop_();
  vTaskDelete(nullptr);
}

void BabyfoonReceiver::mixer_loop_() {
  std::vector<int16_t> in(FRAME_SAMPLES);
  std::vector<float> mix(FRAME_SAMPLES);
  std::vector<int16_t> out(FRAME_SAMPLES);
  bool started = false;

  while (true) {
    if (this->speaker_ == nullptr) {
      vTaskDelay(pdMS_TO_TICKS(1000));
      continue;
    }
    if (!started) {
      this->speaker_->start();
      started = true;
    }

    std::fill(mix.begin(), mix.end(), 0.0f);
    const bool silent = this->all_muted_.load() || this->talk_index_.load() >= 0;
    const float g = this->gain_ * this->volume_;

    for (auto &s : this->sources_) {
      size_t got = s->buffer->read(in.data(), FRAME_BYTES, 0);
      size_t n = got / 2;
      if (n == 0 || silent || s->muted.load())
        continue;
      for (size_t i = 0; i < n; i++)
        mix[i] += in[i] * g;
    }

    for (size_t i = 0; i < FRAME_SAMPLES; i++) {
      float v = mix[i];
      out[i] = (int16_t) (v > 32767.0f ? 32767.0f : (v < -32768.0f ? -32768.0f : v));
    }

    // play() wacht tot er plek is in de speaker-buffer: dat bepaalt het tempo
    size_t written = 0;
    const uint8_t *p = reinterpret_cast<const uint8_t *>(out.data());
    int tries = 0;
    while (written < FRAME_BYTES && tries < 10) {
      size_t w = this->speaker_->play(p + written, FRAME_BYTES - written, pdMS_TO_TICKS(40));
      written += w;
      if (w == 0)
        tries++;
    }
    if (written == 0)
      vTaskDelay(pdMS_TO_TICKS(20));
  }
}

// ---------------------------------------------------------------------
//  Terugpraten
// ---------------------------------------------------------------------
void BabyfoonReceiver::start_talk(int index) {
  if (this->mic_source_ == nullptr || index < 0 || index >= (int) this->sources_.size())
    return;
  if (this->talk_index_.load() >= 0)
    return;  // al bezig
  this->talk_error_.clear();
  this->talk_stop_ = false;
  this->talk_index_ = index;
  this->mic_should_run_ = true;
  if (this->talk_buffer_ != nullptr)
    this->talk_buffer_->reset();
  auto *args = new TalkArgs{this, index};
  if (xTaskCreate(BabyfoonReceiver::talk_task_, "bf_talk", 4096, args, 5, &this->talk_task_handle_) != pdPASS) {
    delete args;
    this->talk_index_ = -1;
    this->mic_should_run_ = false;
  }
}

void BabyfoonReceiver::stop_talk() { this->talk_stop_ = true; }

void BabyfoonReceiver::talk_task_(void *param) {
  auto *args = (TalkArgs *) param;
  BabyfoonReceiver *self = args->self;
  int index = args->index;
  delete args;
  self->talk_loop_(index);
  self->mic_should_run_ = false;
  self->talk_index_ = -1;
  self->talk_task_handle_ = nullptr;
  vTaskDelete(nullptr);
}

void BabyfoonReceiver::talk_loop_(int index) {
  Source *src = this->sources_[index].get();
  int sock = connect_to(src->host, src->port, 3);
  if (sock < 0) {
    this->talk_error_ = "Babyfoon niet bereikbaar";
    return;
  }
  std::string req = "POST /talk HTTP/1.0\r\nHost: " + src->host + "\r\n";
  if (!src->key.empty())
    req += "X-Babyfoon-Key: " + src->key + "\r\n";
  req += "Content-Type: audio/L16;rate=16000;channels=1\r\n\r\n";
  int code = send_all(sock, req.data(), req.size()) ? read_http_head(sock) : -1;
  if (code != 200) {
    this->talk_error_ = code == 401   ? "Toegangssleutel klopt niet"
                        : code == 409 ? "Iemand anders praat al"
                        : code == 404 ? "Terugpraten staat uit op deze babyfoon"
                                      : "Terugpraten lukt niet";
    close(sock);
    return;
  }
  ESP_LOGI(TAG, "Terugpraten naar %s", src->name.c_str());

  uint8_t buf[FRAME_BYTES];
  while (!this->talk_stop_.load()) {
    size_t n = this->talk_buffer_->read(buf, sizeof(buf), pdMS_TO_TICKS(100));
    if (n == 0) {
      // Microfoon nog niet gestart: stilte sturen, zodat het bordje niet afhaakt
      memset(buf, 0, sizeof(buf));
      n = sizeof(buf);
    }
    n &= ~((size_t) 1);
    if (!send_all(sock, buf, n)) {
      this->talk_error_ = "Verbinding verbroken tijdens praten";
      break;
    }
  }
  shutdown(sock, SHUT_RDWR);
  close(sock);
  ESP_LOGI(TAG, "Terugpraten gestopt");
}

}  // namespace esphome::babyfoon_receiver

#endif
