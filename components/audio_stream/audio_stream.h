#pragma once

#ifdef USE_ESP32

#include "esphome/components/microphone/microphone_source.h"
#include "esphome/components/ring_buffer/ring_buffer.h"
#include "esphome/components/sensor/sensor.h"
#include "esphome/components/speaker/speaker.h"
#include "esphome/core/component.h"

#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

namespace esphome::audio_stream {

static const char *const AUDIO_STREAM_VERSION = "1.4.0";
static const uint8_t MAX_SLOTS = 4;

struct ClientSlot {
  std::shared_ptr<ring_buffer::RingBuffer> buffer;  // gevuld door de mic-callback
  int sock{-1};
  bool in_use{false};
};

class AudioStreamComponent : public Component {
 public:
  void setup() override;
  void loop() override;
  void dump_config() override;
  float get_setup_priority() const override { return setup_priority::AFTER_WIFI; }

  void set_microphone_source(microphone::MicrophoneSource *source) { this->mic_source_ = source; }
  void set_port(uint16_t port) { this->port_ = port; }
  void set_max_clients(uint8_t n) { this->max_clients_ = n; }
  void set_buffer_duration(uint32_t ms) { this->buffer_ms_ = ms; }
  void set_clients_sensor(sensor::Sensor *s) { this->clients_sensor_ = s; }

  /// Ruisonderdrukking (noise gate): onder deze grens wordt de stream stil.
  void set_gate_enabled(bool on) { this->gate_enabled_ = on; }
  void set_gate_threshold(float db) { this->gate_threshold_db_ = db; }
  void set_gate_hold(uint32_t ms) { this->gate_hold_ms_ = ms; }
  /// Terugpraten: audio van de app afspelen op deze speaker.
  void set_talk_speaker(speaker::Speaker *spk) { this->talk_speaker_ = spk; }
  void set_talk_volume(float v) { this->talk_volume_ = v; }

  bool is_gate_enabled() const { return this->gate_enabled_; }
  float get_gate_threshold() const { return this->gate_threshold_db_; }

 protected:
  static void server_task_(void *param);
  static void client_task_(void *param);
  void handle_client_(uint8_t slot);
  int claim_slot_(int sock);
  void release_slot_(uint8_t slot);
  void apply_gate_(const std::vector<uint8_t> &in, std::vector<uint8_t> &out);
  void handle_talk_(int sock, const char *leftover, size_t leftover_len);

  microphone::MicrophoneSource *mic_source_{nullptr};
  sensor::Sensor *clients_sensor_{nullptr};
  uint16_t port_{8080};
  uint8_t max_clients_{2};
  uint32_t buffer_ms_{500};

  ClientSlot slots_[MAX_SLOTS];
  std::mutex slots_mutex_;
  std::atomic<uint8_t> active_clients_{0};
  int last_published_{-1};
  bool mic_started_{false};

  uint32_t sample_rate_{16000};

  std::atomic<bool> gate_enabled_{false};
  std::atomic<float> gate_threshold_db_{-40.0f};
  uint32_t gate_hold_ms_{2500};
  uint32_t gate_open_until_{0};
  float gate_gain_{1.0f};
  std::vector<uint8_t> gated_;

  speaker::Speaker *talk_speaker_{nullptr};
  float talk_volume_{0.5f};
  std::atomic<bool> talking_{false};
  std::atomic<uint32_t> mute_until_{0};  // stream stil tijdens/na terugpraten (geen echo)
  TaskHandle_t server_task_handle_{nullptr};
};

}  // namespace esphome::audio_stream

#endif
