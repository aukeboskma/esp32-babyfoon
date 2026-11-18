"""babyfoon_receiver v1.0.0: ouderunit die naar één of meer babyfoons luistert.

Per babyfoon: live audio ontvangen (zelfde stream als de app), mixen naar de
speaker, geluidsniveau meten, en "verbonden" melden. Valt een babyfoon
langer dan `alarm_after` stil, dan gaat zijn "verbonden"-sensor uit
(daar hang je in YAML het alarm aan). Terugpraten via de eigen microfoon.

    babyfoon_receiver:
      id: ontvanger
      speaker: rx_in
      microphone:
        microphone: mic
      alarm_after: 10s
      sources:
        - name: Eva
          host: 192.168.178.180
          access_key: !secret stream_key_eva
          level:
            name: "Eva niveau"
          connected:
            name: "Eva verbonden"
"""

import esphome.codegen as cg
from esphome.components import binary_sensor, microphone, sensor, speaker
import esphome.config_validation as cv
from esphome.const import (
    CONF_ID,
    CONF_MICROPHONE,
    CONF_NAME,
    CONF_PORT,
    CONF_SPEAKER,
    DEVICE_CLASS_CONNECTIVITY,
    PLATFORM_ESP32,
    UNIT_DECIBEL,
)

AUTO_LOAD = ["audio", "ring_buffer", "sensor", "binary_sensor", "socket"]
DEPENDENCIES = ["network"]
CODEOWNERS = []

CONF_SOURCES = "sources"
CONF_HOST = "host"
CONF_ACCESS_KEY = "access_key"
CONF_LEVEL = "level"
CONF_CONNECTED = "connected"
CONF_ALARM_AFTER = "alarm_after"
CONF_GAIN = "gain"

babyfoon_receiver_ns = cg.esphome_ns.namespace("babyfoon_receiver")
BabyfoonReceiver = babyfoon_receiver_ns.class_("BabyfoonReceiver", cg.Component)

SOURCE_SCHEMA = cv.Schema(
    {
        cv.Required(CONF_NAME): cv.string_strict,
        cv.Required(CONF_HOST): cv.string_strict,
        cv.Optional(CONF_PORT, default=8080): cv.port,
        cv.Optional(CONF_ACCESS_KEY, default=""): cv.string,
        cv.Optional(CONF_LEVEL): sensor.sensor_schema(
            unit_of_measurement="dBFS",
            accuracy_decimals=0,
            icon="mdi:volume-high",
        ),
        cv.Optional(CONF_CONNECTED): binary_sensor.binary_sensor_schema(
            device_class=DEVICE_CLASS_CONNECTIVITY,
        ),
    }
)


def _consume_sockets(config):
    from esphome.components import socket

    # één stream per babyfoon + één voor terugpraten
    socket.consume_sockets(
        len(config[CONF_SOURCES]) + 1, "babyfoon_receiver", socket.SocketType.TCP
    )(config)
    return config


CONFIG_SCHEMA = cv.All(
    cv.Schema(
        {
            cv.GenerateID(): cv.declare_id(BabyfoonReceiver),
            cv.Required(CONF_SPEAKER): cv.use_id(speaker.Speaker),
            cv.Optional(CONF_MICROPHONE): microphone.microphone_source_schema(
                min_bits_per_sample=16, max_bits_per_sample=16
            ),
            cv.Optional(CONF_ALARM_AFTER, default="10s"): cv.All(
                cv.positive_time_period_milliseconds,
                cv.Range(min=cv.TimePeriod(seconds=3), max=cv.TimePeriod(minutes=5)),
            ),
            cv.Optional(CONF_GAIN, default=4.0): cv.float_range(min=0.5, max=16.0),
            cv.Required(CONF_SOURCES): cv.All(
                cv.ensure_list(SOURCE_SCHEMA), cv.Length(min=1, max=4)
            ),
        }
    ).extend(cv.COMPONENT_SCHEMA),
    cv.only_on([PLATFORM_ESP32]),
    _consume_sockets,
)

FINAL_VALIDATE_SCHEMA = cv.Schema(
    {
        cv.Optional(
            CONF_MICROPHONE
        ): microphone.final_validate_microphone_source_schema("babyfoon_receiver"),
    },
    extra=cv.ALLOW_EXTRA,
)


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)

    spk = await cg.get_variable(config[CONF_SPEAKER])
    cg.add(var.set_speaker(spk))
    cg.add(var.set_alarm_after(config[CONF_ALARM_AFTER]))
    cg.add(var.set_gain(config[CONF_GAIN]))

    if CONF_MICROPHONE in config:
        mic = await microphone.microphone_source_to_code(
            config[CONF_MICROPHONE], passive=False
        )
        cg.add(var.set_microphone_source(mic))

    for src in config[CONF_SOURCES]:
        level = cg.nullptr
        conn = cg.nullptr
        if CONF_LEVEL in src:
            level = await sensor.new_sensor(src[CONF_LEVEL])
        if CONF_CONNECTED in src:
            conn = await binary_sensor.new_binary_sensor(src[CONF_CONNECTED])
        cg.add(
            var.add_source(
                src[CONF_NAME],
                src[CONF_HOST],
                src[CONF_PORT],
                src[CONF_ACCESS_KEY],
                level,
                conn,
            )
        )
