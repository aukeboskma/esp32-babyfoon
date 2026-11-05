"""audio_stream v2.2.0: microfoon als live WAV-stream via HTTP (lokaal netwerk).

Gebruik:
    audio_stream:
      microphone:
        microphone: mic
        gain_factor: 4
      port: 8080
      max_clients: 2
      clients:
        name: "Luisteraars"

Stream:  http://<ip>:8080/audio.wav   (16 bit mono PCM, sample rate van de mic)
Status:  http://<ip>:8080/status      (JSON)
"""

from esphome import automation
import esphome.codegen as cg
from esphome.components import microphone, sensor, speaker
import esphome.config_validation as cv
from esphome.const import CONF_ID, CONF_MICROPHONE, CONF_PORT, CONF_TRIGGER_ID, PLATFORM_ESP32

AUTO_LOAD = ["audio", "ring_buffer", "sensor", "socket", "speaker"]
DEPENDENCIES = ["microphone", "network"]
CODEOWNERS = []

CONF_MAX_CLIENTS = "max_clients"
CONF_CLIENTS = "clients"
CONF_BUFFER_DURATION = "buffer_duration"
CONF_NOISE_GATE = "noise_gate"
CONF_THRESHOLD = "threshold"
CONF_HOLD = "hold"
CONF_TALK = "talk"
CONF_ON_TUNE = "on_tune"
CONF_HIGH_PASS = "high_pass"
CONF_SPEAKER = "speaker"
CONF_VOLUME = "volume"

audio_stream_ns = cg.esphome_ns.namespace("audio_stream")
AudioStreamComponent = audio_stream_ns.class_("AudioStreamComponent", cg.Component)
TuneTrigger = audio_stream_ns.class_("TuneTrigger", automation.Trigger.template())

def _consume_sockets(config):
    """Reserveer sockets: 1 luistersocket + 1 per luisteraar."""
    from esphome.components import socket

    socket.consume_sockets(1, "audio_stream", socket.SocketType.TCP_LISTEN)(config)
    socket.consume_sockets(
        config[CONF_MAX_CLIENTS] + 1, "audio_stream", socket.SocketType.TCP
    )(config)
    return config


CONFIG_SCHEMA = cv.All(
    cv.Schema(
        {
            cv.GenerateID(): cv.declare_id(AudioStreamComponent),
            cv.Optional(CONF_MICROPHONE, default={}): microphone.microphone_source_schema(
                min_bits_per_sample=16, max_bits_per_sample=16
            ),
            cv.Optional(CONF_PORT, default=8080): cv.port,
            cv.Optional(CONF_MAX_CLIENTS, default=2): cv.int_range(1, 4),
            cv.Optional(CONF_BUFFER_DURATION, default="500ms"): cv.All(
                cv.positive_time_period_milliseconds,
                cv.Range(
                    min=cv.TimePeriod(milliseconds=100), max=cv.TimePeriod(seconds=3)
                ),
            ),
            # Startwaarden; vanuit Home Assistant aan te passen (zie YAML)
            cv.Optional(CONF_NOISE_GATE, default={}): cv.Schema(
                {
                    cv.Optional("enabled", default=False): cv.boolean,
                    cv.Optional(CONF_THRESHOLD, default=-40): cv.float_range(
                        min=-90, max=0
                    ),
                    cv.Optional(CONF_HOLD, default="2500ms"): cv.positive_time_period_milliseconds,
                }
            ),
            # Terugpraten vanuit de app (stem afspelen op deze speaker)
            cv.Optional(CONF_TALK): cv.Schema(
                {
                    cv.Required(CONF_SPEAKER): cv.use_id(speaker.Speaker),
                    cv.Optional(CONF_VOLUME, default=0.5): cv.percentage,
                }
            ),
            # Bromfilter: hoogdoorlaatfilter in Hz (0 = uit)
            cv.Optional(CONF_HIGH_PASS, default="150Hz"): cv.Any(
                cv.All(cv.frequency, cv.Range(min=0, max=1000)), cv.one_of(0)
            ),
            # Wordt uitgevoerd als de app op "Ruis inmeten" drukt (GET /tune)
            cv.Optional(CONF_ON_TUNE): automation.validate_automation(
                {cv.GenerateID(CONF_TRIGGER_ID): cv.declare_id(TuneTrigger)}
            ),
            cv.Optional(CONF_CLIENTS): sensor.sensor_schema(
                accuracy_decimals=0,
                icon="mdi:headphones",
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
        ): microphone.final_validate_microphone_source_schema("audio_stream"),
    },
    extra=cv.ALLOW_EXTRA,
)


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)

    mic_source = await microphone.microphone_source_to_code(
        config[CONF_MICROPHONE], passive=False
    )
    cg.add(var.set_microphone_source(mic_source))
    cg.add(var.set_port(config[CONF_PORT]))
    cg.add(var.set_max_clients(config[CONF_MAX_CLIENTS]))
    cg.add(var.set_buffer_duration(config[CONF_BUFFER_DURATION]))

    cg.add(var.set_high_pass(float(config[CONF_HIGH_PASS])))

    gate = config[CONF_NOISE_GATE]
    cg.add(var.set_gate_enabled(gate["enabled"]))
    cg.add(var.set_gate_threshold(gate[CONF_THRESHOLD]))
    cg.add(var.set_gate_hold(gate[CONF_HOLD]))

    if CONF_TALK in config:
        spk = await cg.get_variable(config[CONF_TALK][CONF_SPEAKER])
        cg.add(var.set_talk_speaker(spk))
        cg.add(var.set_talk_volume(config[CONF_TALK][CONF_VOLUME]))

    for conf in config.get(CONF_ON_TUNE, []):
        trigger = cg.new_Pvariable(conf[CONF_TRIGGER_ID])
        cg.add(var.add_tune_trigger(trigger))
        await automation.build_automation(trigger, [], conf)

    if CONF_CLIENTS in config:
        sens = await sensor.new_sensor(config[CONF_CLIENTS])
        cg.add(var.set_clients_sensor(sens))
