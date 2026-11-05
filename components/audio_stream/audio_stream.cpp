#include "audio_stream.h"

#ifdef USE_ESP32

#include "esphome/components/audio/audio.h"
#include "esphome/core/hal.h"
#include "esphome/core/log.h"

#include <lwip/sockets.h>
#include <lwip/netdb.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>

namespace esphome::audio_stream {

static const char *const TAG = "audio_stream";

static const size_t SEND_CHUNK = 1024;  // bytes per send (32 ms bij 16 kHz/16 bit)
static const uint32_t SERVER_STACK = 4096;
static const uint32_t CLIENT_STACK = 4096;

struct ClientTaskArgs {
  AudioStreamComponent *self;
  uint8_t slot;
};

// ---------------------------------------------------------------------
void AudioStreamComponent::setup() {
  this->sample_rate_ = this->mic_source_->get_audio_stream_info().get_sample_rate();

  // Microfoondata naar de buffer van elke verbonden luisteraar kopieren.
  // RingBuffer::write overschrijft de oudste data als hij vol is: bij een
  // trage verbinding hoor je dus het nu, niet een steeds grotere vertraging.
  this->mic_source_->add_data_callback([this](const std::vector<uint8_t> &data) {
    std::shared_ptr<ring_buffer::RingBuffer> targets[MAX_SLOTS];
    bool any = false;
    {
      std::lock_guard<std::mutex> lock(this->slots_mutex_);
      for (uint8_t i = 0; i < MAX_SLOTS; i++) {
        targets[i] = this->slots_[i].buffer;
        any = any || (targets[i] != nullptr);
      }
    }
    if (!any)
      return;  // niemand luistert: geen werk doen

    const std::vector<uint8_t> *send = &data;
    const uint32_t now_ms = millis();
    if (this->talking_.load() || (int32_t) (this->mute_until_.load() - now_ms) > 0) {
      // Tijdens terugpraten stilte sturen, anders hoor je jezelf terug
      this->gated_.assign(data.size(), 0);
      this->gate_gain_ = 0.0f;
      send = &this->gated_;
    } else if (this->gate_enabled_.load()) {
      this->apply_gate_(data, this->gated_);
      send = &this->gated_;
    } else {
      this->gate_gain_ = 1.0f;
    }
    for (auto &rb : targets) {
      if (rb != nullptr)
        rb->write(send->data(), send->size());
    }
  });

  // De microfoon blijft altijd aan (de geluidssensor gebruikt hem ook).
  this->mic_source_->start();

  if (xTaskCreate(AudioStreamComponent::server_task_, "audio_srv", SERVER_STACK, this, 5,
                  &this->server_task_handle_) != pdPASS) {
    ESP_LOGE(TAG, "Kon servertaak niet starten");
    this->mark_failed();
  }
}

// Ruisonderdrukking: is het stukje geluid zachter dan de grens (en al even
// stil), dan fade de stream naar stilte. Boven de grens direct weer open.
// Er blijft altijd data komen, dus de app ziet geen "verbinding kwijt".
void AudioStreamComponent::apply_gate_(const std::vector<uint8_t> &in, std::vector<uint8_t> &out) {
  const size_t n = in.size() / 2;
  out.resize(n * 2);
  const int16_t *src = reinterpret_cast<const int16_t *>(in.data());
  int16_t *dst = reinterpret_cast<int16_t *>(out.data());

  double sum = 0;
  for (size_t i = 0; i < n; i++)
    sum += (double) src[i] * src[i];
  double rms = n > 0 ? std::sqrt(sum / n) : 0.0;
  float db = 20.0f * std::log10(std::max(rms, 1.0) / 32768.0);

  const uint32_t now = millis();
  if (db > this->gate_threshold_db_.load())
    this->gate_open_until_ = now + this->gate_hold_ms_;
  const float target = ((int32_t) (this->gate_open_until_ - now) > 0) ? 1.0f : 0.0f;

  const float up = 1.0f / (this->sample_rate_ * 0.01f);    // 10 ms open
  const float down = 1.0f / (this->sample_rate_ * 0.4f);   // 400 ms dicht
  float g = this->gate_gain_;
  for (size_t i = 0; i < n; i++) {
    if (g < target)
      g = std::min(target, g + up);
    else if (g > target)
      g = std::max(target, g - down);
    dst[i] = (int16_t) (src[i] * g);
  }
  this->gate_gain_ = g;
}

void AudioStreamComponent::loop() {
  int n = this->active_clients_.load();
  if (n != this->last_published_) {
    this->last_published_ = n;
    ESP_LOGI(TAG, "Luisteraars: %d", n);
    if (this->clients_sensor_ != nullptr)
      this->clients_sensor_->publish_state(n);
  }
}

void AudioStreamComponent::dump_config() {
  ESP_LOGCONFIG(TAG,
                "Audio stream (versie %s):\n"
                "  Poort: %u\n"
                "  Max luisteraars: %u\n"
                "  Sample rate: %u Hz, 16 bit mono\n"
                "  Buffer: %u ms\n"
                "  Ruisonderdrukking: %s (grens %.0f dB)",
                AUDIO_STREAM_VERSION, this->port_, this->max_clients_, (unsigned) this->sample_rate_,
                (unsigned) this->buffer_ms_, this->gate_enabled_.load() ? "aan" : "uit", this->gate_threshold_db_.load());
}

// ---------------------------------------------------------------------
int AudioStreamComponent::claim_slot_(int sock) {
  // 16 bit mono = 2 bytes per sample
  size_t size = (size_t) this->sample_rate_ * 2 * this->buffer_ms_ / 1000;
  size &= ~((size_t) 1);
  std::lock_guard<std::mutex> lock(this->slots_mutex_);
  for (uint8_t i = 0; i < this->max_clients_; i++) {
    if (!this->slots_[i].in_use) {
      std::shared_ptr<ring_buffer::RingBuffer> rb = ring_buffer::RingBuffer::create(size);
      if (rb == nullptr)
        return -1;
      this->slots_[i].in_use = true;
      this->slots_[i].sock = sock;
      this->slots_[i].buffer = rb;
      this->active_clients_++;
      return i;
    }
  }
  return -1;
}

void AudioStreamComponent::release_slot_(uint8_t slot) {
  std::shared_ptr<ring_buffer::RingBuffer> old;
  {
    std::lock_guard<std::mutex> lock(this->slots_mutex_);
    old = this->slots_[slot].buffer;  // laatste referentie buiten de lock vrijgeven
    this->slots_[slot].buffer.reset();
    this->slots_[slot].sock = -1;
    this->slots_[slot].in_use = false;
    this->active_clients_--;
  }
}

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

static void put_le32(uint8_t *p, uint32_t v) {
  p[0] = v & 0xFF;
  p[1] = (v >> 8) & 0xFF;
  p[2] = (v >> 16) & 0xFF;
  p[3] = (v >> 24) & 0xFF;
}
static void put_le16(uint8_t *p, uint16_t v) {
  p[0] = v & 0xFF;
  p[1] = (v >> 8) & 0xFF;
}

void AudioStreamComponent::server_task_(void *param) {
  auto *self = (AudioStreamComponent *) param;

  int listen_sock = -1;
  while (true) {
    if (listen_sock < 0) {
      listen_sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
      if (listen_sock < 0) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        continue;
      }
      int opt = 1;
      setsockopt(listen_sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
      struct sockaddr_in addr {};
      addr.sin_family = AF_INET;
      addr.sin_addr.s_addr = htonl(INADDR_ANY);
      addr.sin_port = htons(self->port_);
      if (bind(listen_sock, (struct sockaddr *) &addr, sizeof(addr)) != 0 || listen(listen_sock, 2) != 0) {
        ESP_LOGW(TAG, "Kon niet luisteren op poort %u, opnieuw proberen", self->port_);
        close(listen_sock);
        listen_sock = -1;
        vTaskDelay(pdMS_TO_TICKS(2000));
        continue;
      }
      ESP_LOGI(TAG, "Stream beschikbaar op http://<ip>:%u/audio.wav", self->port_);
    }

    struct sockaddr_in client_addr {};
    socklen_t addr_len = sizeof(client_addr);
    int sock = accept(listen_sock, (struct sockaddr *) &client_addr, &addr_len);
    if (sock < 0) {
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }

    int slot = self->claim_slot_(sock);
    if (slot < 0) {
      static const char busy[] =
          "HTTP/1.0 503 Service Unavailable\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
          "Te veel luisteraars\n";
      send_all(sock, busy, sizeof(busy) - 1);
      close(sock);
      continue;
    }

    auto *args = new ClientTaskArgs{self, (uint8_t) slot};
    if (xTaskCreate(AudioStreamComponent::client_task_, "audio_cli", CLIENT_STACK, args, 5, nullptr) != pdPASS) {
      delete args;
      close(sock);
      self->release_slot_(slot);
    }
  }
}

void AudioStreamComponent::client_task_(void *param) {
  auto *args = (ClientTaskArgs *) param;
  AudioStreamComponent *self = args->self;
  uint8_t slot = args->slot;
  delete args;

  self->handle_client_(slot);

  vTaskDelete(nullptr);
}

void AudioStreamComponent::handle_client_(uint8_t slot) {
  int sock;
  std::shared_ptr<ring_buffer::RingBuffer> rb;
  {
    std::lock_guard<std::mutex> lock(this->slots_mutex_);
    sock = this->slots_[slot].sock;
    rb = this->slots_[slot].buffer;
  }

  struct timeval tv {};
  tv.tv_sec = 3;
  setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  tv.tv_sec = 5;  // na 5 s niet kunnen versturen = verbinding dood
  setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
  int one = 1;
  setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

  // Verzoek lezen tot de lege regel
  char req[512];
  size_t got = 0;
  while (got < sizeof(req) - 1) {
    int r = recv(sock, req + got, sizeof(req) - 1 - got, 0);
    if (r <= 0)
      break;
    got += r;
    req[got] = '\0';
    if (strstr(req, "\r\n\r\n") != nullptr)
      break;
  }
  req[got] = '\0';

  bool is_talk = strncmp(req, "POST /talk", 10) == 0;
  bool is_status = strncmp(req, "GET /status", 11) == 0;
  bool is_stream = strncmp(req, "GET /audio.wav", 14) == 0 || strncmp(req, "GET / ", 6) == 0;

  if (is_talk) {
    // Terugpraten gebruikt geen luisterplek: meteen vrijgeven
    this->release_slot_(slot);
    const char *hdr_end = strstr(req, "\r\n\r\n");
    size_t off = hdr_end != nullptr ? (size_t) (hdr_end - req) + 4 : got;
    this->handle_talk_(sock, req + off, got > off ? got - off : 0);
    shutdown(sock, SHUT_RDWR);
    close(sock);
    return;
  }

  if (is_status) {
    char body[200];
    snprintf(body, sizeof(body),
             "{\"version\":\"%s\",\"sample_rate\":%u,\"bits\":16,\"channels\":1,\"clients\":%u,\"max_clients\":%u,"
             "\"talk\":%s}\n",
             AUDIO_STREAM_VERSION, (unsigned) this->sample_rate_, (unsigned) this->active_clients_.load(),
             this->max_clients_, this->talk_speaker_ != nullptr ? "true" : "false");
    char head[160];
    snprintf(head, sizeof(head),
             "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: %u\r\n"
             "Connection: close\r\n\r\n",
             (unsigned) strlen(body));
    send_all(sock, head, strlen(head));
    send_all(sock, body, strlen(body));
  } else if (is_stream) {
    static const char head[] =
        "HTTP/1.0 200 OK\r\nContent-Type: audio/wav\r\nCache-Control: no-cache, no-store\r\n"
        "Connection: close\r\n\r\n";
    bool ok = send_all(sock, head, sizeof(head) - 1);

    // WAV-header met "oneindige" lengte
    uint8_t wav[44];
    memcpy(wav, "RIFF", 4);
    put_le32(wav + 4, 0xFFFFFFFF);
    memcpy(wav + 8, "WAVEfmt ", 8);
    put_le32(wav + 16, 16);
    put_le16(wav + 20, 1);  // PCM
    put_le16(wav + 22, 1);  // mono
    put_le32(wav + 24, this->sample_rate_);
    put_le32(wav + 28, this->sample_rate_ * 2);
    put_le16(wav + 32, 2);
    put_le16(wav + 34, 16);
    memcpy(wav + 36, "data", 4);
    put_le32(wav + 40, 0xFFFFFFFF - 36);
    ok = ok && send_all(sock, wav, sizeof(wav));

    ESP_LOGI(TAG, "Luisteraar verbonden (slot %u)", slot);
    uint8_t chunk[SEND_CHUNK];
    while (ok) {
      size_t n = rb->read(chunk, sizeof(chunk), pdMS_TO_TICKS(200));
      if (n == 0)
        continue;  // geen microfoondata; de app merkt dat aan de stilstand
      ok = send_all(sock, chunk, n);
    }
    ESP_LOGI(TAG, "Luisteraar weg (slot %u)", slot);
  } else {
    static const char nf[] =
        "HTTP/1.0 404 Not Found\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
        "Gebruik /audio.wav of /status\n";
    send_all(sock, nf, sizeof(nf) - 1);
  }

  shutdown(sock, SHUT_RDWR);
  close(sock);
  this->release_slot_(slot);
}

// ---------------------------------------------------------------------
//  Terugpraten: de app stuurt "POST /talk" en daarna ruwe audio
//  (16 bit mono, 16 kHz) zolang de knop is ingedrukt.
// ---------------------------------------------------------------------
void AudioStreamComponent::handle_talk_(int sock, const char *leftover, size_t leftover_len) {
  if (this->talk_speaker_ == nullptr) {
    static const char nf[] = "HTTP/1.0 404 Not Found\r\nConnection: close\r\n\r\nTerugpraten staat uit\n";
    send_all(sock, nf, sizeof(nf) - 1);
    return;
  }
  if (this->talking_.exchange(true)) {
    static const char busy[] = "HTTP/1.0 409 Conflict\r\nConnection: close\r\n\r\nIemand anders praat al\n";
    send_all(sock, busy, sizeof(busy) - 1);
    return;
  }
  static const char ok[] = "HTTP/1.0 200 OK\r\nConnection: close\r\n\r\n";
  send_all(sock, ok, sizeof(ok) - 1);
  ESP_LOGI(TAG, "Terugpraten gestart");

  this->talk_speaker_->set_audio_stream_info(audio::AudioStreamInfo(16, 1, 16000));
  this->talk_speaker_->start();

  uint8_t buf[1024];
  size_t carry = 0;
  if (leftover_len > 0) {
    carry = std::min(leftover_len, sizeof(buf));
    memcpy(buf, leftover, carry);
  }
  const float vol = this->talk_volume_;

  while (true) {
    size_t n = carry;
    if (n < sizeof(buf)) {
      int r = recv(sock, buf + n, sizeof(buf) - n, 0);
      if (r <= 0)
        break;  // knop losgelaten (verbinding dicht) of 3 s niets
      n += r;
    }
    size_t even = n & ~((size_t) 1);
    int16_t *samples = reinterpret_cast<int16_t *>(buf);
    for (size_t i = 0; i < even / 2; i++)
      samples[i] = (int16_t) (samples[i] * vol);

    size_t written = 0;
    int stalls = 0;
    while (written < even && stalls < 20) {
      size_t k = this->talk_speaker_->play(buf + written, even - written, pdMS_TO_TICKS(50));
      written += k;
      stalls = (k == 0) ? stalls + 1 : 0;
    }
    if (n & 1) {
      buf[0] = buf[n - 1];
      carry = 1;
    } else {
      carry = 0;
    }
  }

  this->talk_speaker_->finish();
  this->mute_until_ = millis() + 700;  // speaker nog even laten uitspelen
  this->talking_ = false;
  ESP_LOGI(TAG, "Terugpraten gestopt");
}

}  // namespace esphome::audio_stream

#endif
