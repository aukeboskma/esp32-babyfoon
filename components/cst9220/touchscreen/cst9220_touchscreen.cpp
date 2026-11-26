#include "cst9220_touchscreen.h"
#include "esphome/core/helpers.h"

#include <cinttypes>

namespace esphome::cst9220 {

// Id 0 is the base Touchscreen class touch timeout.
static constexpr uint32_t REREAD_TIMEOUT_ID = 1;
static constexpr uint32_t REREAD_DELAY_MS = 20;

void CST9220Touchscreen::setup() {
  pulse_reset(this->reset_pin_, {.high_before_ms = 5, .low_ms = 10});
  if (this->interrupt_pin_ != nullptr) {
    this->interrupt_pin_->setup();
    this->attach_interrupt_(this->interrupt_pin_, gpio::INTERRUPT_FALLING_EDGE);
  }
  // Wait for the controller to leave its bootloader before talking to it.
  // Babyfoon patch: the ESP-Mosaico shares the touch reset with the display, so the
  // controller may still be booting. Retry instead of failing on the first try.
  this->set_timeout(100, [this] { this->continue_setup_(); });
}

bool CST9220Touchscreen::retry_() {
  if (++this->attempts_ >= MAX_ATTEMPTS)
    return false;
  this->set_timeout(200, [this] { this->continue_setup_(); });
  return true;
}

void CST9220Touchscreen::continue_setup_() {
  uint8_t buffer[4];

  // Enter command mode so the configuration registers can be read.
  if (this->write_register16(REG_CMD_MODE, buffer, 0) != i2c::ERROR_OK) {
    if (this->retry_())
      return;
    this->status_set_error(LOG_STR("Failed to enter command mode"));
    this->mark_failed();
    return;
  }
  delay(10);

  // The firmware check code confirms that valid firmware is loaded.
  if (this->read_register16(REG_CHECKCODE, buffer, 4) != i2c::ERROR_OK) {
    if (this->retry_())
      return;
    this->status_set_error(LOG_STR("Failed to read check code"));
    this->mark_failed();
    return;
  }
  uint32_t checkcode = encode_uint32(buffer[3], buffer[2], buffer[1], buffer[0]);
  this->checkcode_ = checkcode;
  if ((checkcode & 0xFFFF0000) != 0xCACA0000) {
    if (this->retry_())
      return;
    // Babyfoon patch: do not give up; some firmware reports another check code.
    ESP_LOGW(TAG, "Unexpected firmware check code: 0x%08" PRIX32 ", continuing anyway", checkcode);
  }

  // Read the panel resolution unless the user supplied calibration values.
  if (this->read_register16(REG_RESOLUTION, buffer, 4) == i2c::ERROR_OK) {
    if (this->x_raw_max_ == this->x_raw_min_)
      this->x_raw_max_ = encode_value_le<uint16_t>(buffer);
    if (this->y_raw_max_ == this->y_raw_min_)
      this->y_raw_max_ = encode_value_le<uint16_t>(&buffer[2]);
  }

  // Read the chip type and project id and validate the controller.
  if (this->read_register16(REG_CHIP_INFO, buffer, 4) != i2c::ERROR_OK) {
    this->status_set_error(LOG_STR("Failed to read chip ID"));
    this->mark_failed();
    return;
  }
  this->chip_id_ = encode_value_le<uint16_t>(&buffer[2]);
  this->project_id_ = encode_value_le<uint16_t>(buffer);
  if (this->chip_id_ != CST9220_CHIP_ID && this->chip_id_ != CST9217_CHIP_ID) {
    if (this->retry_())
      return;
    // Babyfoon patch: log it, but still try to read touches.
    ESP_LOGW(TAG, "Unknown chip ID: 0x%04X, continuing anyway", this->chip_id_);
  }

  // Fall back to the display dimensions if the resolution read failed.
  if (this->x_raw_max_ == this->x_raw_min_)
    this->x_raw_max_ = this->display_->get_native_width();
  if (this->y_raw_max_ == this->y_raw_min_)
    this->y_raw_max_ = this->display_->get_native_height();

  this->setup_complete_ = true;
}

void CST9220Touchscreen::update_touches() {
  if (!this->setup_complete_)
    return;
  uint8_t data[CST9220_DATA_LENGTH];
  // Only an actual I2C failure should skip the update; a successful read with no
  // touches is a real "all fingers lifted" state that must flow through so the
  // base class can generate the release event.
  if (!this->status_comm_result(this->read_register16(REG_TOUCH_DATA, data, sizeof(data)) == i2c::ERROR_OK)) {
    this->skip_update_ = true;
    return;
  }

  // A valid report carries the marker at offset 6. Writing an acknowledge back makes the controller return
  // invalid frames during a long press, so none is written. An invalid frame keeps the previous points; while
  // a finger is down, read again shortly so a lost release frame does not leave the touch held.
  if (data[6] != TOUCH_ACK) {
    this->skip_update_ = true;
    if (!this->touches_.empty())
      this->set_timeout(REREAD_TIMEOUT_ID, REREAD_DELAY_MS, [this] { this->store_.touched = true; });
    return;
  }

  uint8_t num_touches = data[5] & 0x7F;
  if (num_touches > CST9220_MAX_TOUCHES)
    num_touches = CST9220_MAX_TOUCHES;

  for (uint8_t i = 0; i < num_touches; i++) {
    // The first point starts at offset 0; subsequent points are offset by the
    // two status bytes that follow it.
    const uint8_t *p = data + i * 5 + (i == 0 ? 0 : 2);
    uint8_t id = p[0] >> 4;
    uint8_t event = p[0] & 0x0F;
    if (event != TOUCH_EVENT_DOWN)
      continue;
    // p[3] is shared: high nibble holds the X LSBs, low nibble the Y LSBs.
    uint16_t x = (p[1] << 4) | (p[3] >> 4);
    uint16_t y = (p[2] << 4) | (p[3] & 0x0F);
    ESP_LOGV(TAG, "Read touch %d: %d/%d", id, x, y);
    this->add_raw_touch_position_(id, x, y);
  }
}

void CST9220Touchscreen::dump_config() {
  ESP_LOGCONFIG(TAG,
                "CST9220 Touchscreen:\n"
                "  Chip ID: 0x%04X\n"
                "  Project ID: 0x%04X\n"
                "  Check code: 0x%08" PRIX32 " (attempts: %u, ready: %s)\n"
                "  X Raw Min: %d, X Raw Max: %d\n"
                "  Y Raw Min: %d, Y Raw Max: %d",
                this->chip_id_, this->project_id_, this->checkcode_, (unsigned) this->attempts_ + 1,
                YESNO(this->setup_complete_), this->x_raw_min_, this->x_raw_max_, this->y_raw_min_,
                this->y_raw_max_);
  LOG_I2C_DEVICE(this);
  LOG_PIN("  Interrupt Pin: ", this->interrupt_pin_);
  LOG_PIN("  Reset Pin: ", this->reset_pin_);
}

}  // namespace esphome::cst9220
