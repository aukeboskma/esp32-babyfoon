#pragma once

#ifdef USE_ESP32

#include "esphome/components/binary_sensor/binary_sensor.h"
#include "esphome/components/microphone/microphone_source.h"
#include "esphome/components/ring_buffer/ring_buffer.h"
#include "esphome/components/sensor/sensor.h"
#include "esphome/components/speaker/speaker.h"
#include "esphome/core/component.h"

#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include <atomic>
#include <memory>
#include <string>
#include <vector>

namespace esphome::babyfoon_receiver {

static const char *const RECEIVER_VERSION = "1.0.0";

/// Eén babyfoon waar de ouderunit naar luistert.
struct Source {
  std::string name;
  std::string host;
  uint16_t port{8080};
  std::string key;
  sensor::Sensor *level_sensor{nullptr};
  binary_sensor::BinarySensor *connected_sensor{nullptr};

  std::shared_ptr<ring_buffer::RingBuffer> buffer;  // ontvangen audio (16 kHz, 16 bit mono)
  std::atomic<uint32_t> last_data_ms{0};
  std::atomic<float> level_db{-90.0f};
  std::atomic<bool> muted{false};
  std::atomic<bool> streaming{false};
  std::atomic<int> sock{-1};
  TaskHandle_t task{nullptr};
};

class BabyfoonReceiver : public Component {
 public:
  void setup() override;
  void loop() override;
  void dump_config() override;
  float get_setup_priority() const override { return setup_priority::AFTER_WIFI; }

  // ---- configuratie (codegen) ----
  void set_speaker(speaker::Speaker *spk) { this->speaker_ = spk; }
  void set_microphone_source(microphone::MicrophoneSource *mic) { this->mic_source_ = mic; }
  void set_alarm_after(uint32_t ms) { this->alarm_after_ms_ = ms; }
  void set_gain(float gain) { this->gain_ = gain; }
  void add_source(const std::string &name, const std::string &host, uint16_t port, const std::string &key,
                  sensor::Sensor *level, binary_sensor::BinarySensor *connected);

  // ---- bediening (vanuit YAML-lambdas / het scherm) ----
  /// Volume van het afspelen, 0.0 – 1.0
  void set_volume(float v) { this->volume_ = v < 0 ? 0 : (v > 1 ? 1 : v); }
  float get_volume() const { return this->volume_; }
  void set_muted(int index, bool muted);
  bool is_muted(int index) const;
  /// Alles stil (bijv. 's nachts alleen kijken), bewaking loopt door
  void set_all_muted(bool m) { this->all_muted_ = m; }
  bool is_all_muted() const { return this->all_muted_; }
  /// Terugpraten naar babyfoon `index` (knop ingedrukt) / stoppen (knop los)
  void start_talk(int index);
  void stop_talk();
  bool is_talking() const { return this->talk_index_.load() >= 0; }
  int talking_index() const { return this->talk_index_.load(); }

  int source_count() const { return (int) this->sources_.size(); }
  const std::string &source_name(int i) const { return this->sources_[i]->name; }
  float level_db(int i) const { return this->sources_[i]->level_db.load(); }
  bool is_connected(int i) const;
  /// Laatste foutmelding van terugpraten (leeg = geen fout)
  std::string talk_error() const { return this->talk_error_; }

 protected:
  static void receive_task_(void *param);
  static void mixer_task_(void *param);
  static void talk_task_(void *param);
  void receive_loop_(Source *src);
  void mixer_loop_();
  void talk_loop_(int index);

  speaker::Speaker *speaker_{nullptr};
  microphone::MicrophoneSource *mic_source_{nullptr};
  uint32_t alarm_after_ms_{10000};
  float gain_{4.0f};
  float volume_{0.7f};
  std::atomic<bool> all_muted_{false};

  std::vector<std::unique_ptr<Source>> sources_;
  TaskHandle_t mixer_task_handle_{nullptr};

  // terugpraten
  std::atomic<int> talk_index_{-1};
  std::atomic<bool> talk_stop_{false};
  std::atomic<bool> mic_should_run_{false};
  bool mic_running_{false};
  std::shared_ptr<ring_buffer::RingBuffer> talk_buffer_;
  TaskHandle_t talk_task_handle_{nullptr};
  std::string talk_error_;

  uint32_t last_publish_ms_{0};
};

}  // namespace esphome::babyfoon_receiver

#endif
