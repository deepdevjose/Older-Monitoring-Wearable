"""Closed request contracts. Raw audio and arbitrary patient identifiers are not accepted."""
from datetime import datetime, timezone
import json
import math
import re
from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator


class ClosedModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, allow_inf_nan=False)


def aware_datetime(value: datetime):
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("Timezone is required")
    return value.astimezone(timezone.utc)


class Register(ClosedModel):
    email: str = Field(min_length=3,max_length=254)
    password: str = Field(min_length=12,max_length=128)
    display_name: str = Field(min_length=1,max_length=100)
    role: Literal["elder","caregiver"]
    educational_ack: Literal[True]

    @field_validator("educational_ack", mode="before")
    @classmethod
    def literal_consent(cls, value):
        if value is not True:
            raise ValueError("Explicit academic demonstration acknowledgement required")
        return value

    @field_validator("email")
    @classmethod
    def email_valid(cls, value):
        value = value.lower()
        if not re.fullmatch(r"[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}", value):
            raise ValueError("Invalid email address")
        return value


class Login(ClosedModel):
    email: str = Field(min_length=3,max_length=254)
    password: str = Field(min_length=1,max_length=128)

    @field_validator("email")
    @classmethod
    def normalize_email(cls,value):
        return value.lower()


def bounded_payload(payload):
    allowed = {"device_id","uptime_ms","timestamp","max30102","mpu6050","gy906","microphone","photoresistor","buzzer","phone_received_at","sample_id","boot_nonce"}
    if not isinstance(payload,dict) or set(payload)-allowed:
        raise ValueError("Only the canonical ESP32 sensor JSON is accepted")
    if not isinstance(payload.get("device_id"),str) or not 1 <= len(payload["device_id"]) <= 80:
        raise ValueError("device_id required")
    if not any(k in payload for k in ("max30102","mpu6050","gy906","photoresistor")):
        raise ValueError("At least one sensor is required")
    # Closed sensor keys preserve the canonical firmware's diagnostics, while forbidding audio uploads.
    sensor_keys = {
        "max30102": {"i2c_address","status","finger_detected","heart_rate_valid","spo2_valid","heart_rate_bpm","spo2_percent","ir_raw","red_raw","window_samples","window_target","online","effective_sample_hz","sample_count","calculation_count","sample_loss_events","samples_dropped","samples_dropped_is_minimum","fifo_reader","pointer_read_errors","pointer_unused_bits_observations","fifo_read_errors","adc_unused_bits_observations","fifo_restore_attempts","fifo_restore_failures","fifo_restore_successes","unrecoverable_read_events","hardware_overflow_events","hardware_lost_samples_minimum","overflow_recovery_attempts","overflow_recovery_failures","overflow_recovery_successes","overflow_discarded_samples_minimum","sample_age_ms","result_age_ms"},
        "mpu6050": {"i2c_address","online","accel_g","gyro_dps"},
        "gy906": {"i2c_address","online","status","object_temp_c","ambient_temp_c"},
        "microphone": {"pin","adc_raw"}, "photoresistor": {"pin","adc_raw","percent"}, "buzzer": {"pin"},
    }
    for name, keys in sensor_keys.items():
        if name not in payload:
            continue
        sensor = payload[name]
        if not isinstance(sensor,dict) or set(sensor)-keys:
            raise ValueError("Unsupported sensor fields")
        for key,value in sensor.items():
            if key in {"online","finger_detected","heart_rate_valid","spo2_valid","samples_dropped_is_minimum"}:
                if not isinstance(value,bool):
                    raise ValueError("Sensor validity must be boolean")
            elif key in {"accel_g","gyro_dps"}:
                if not isinstance(value,dict) or set(value) != {"x","y","z"}:
                    raise ValueError("A vector needs x,y,z")
                if any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in value.values()):
                    raise ValueError("Invalid sensor vector")
            elif key in {"i2c_address","status","fifo_reader"}:
                if not isinstance(value,str) or len(value)>64:
                    raise ValueError("Invalid sensor diagnostic")
            elif value is not None and (isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value)):
                raise ValueError("Sensor number must be finite or null")
    if "uptime_ms" in payload and (type(payload["uptime_ms"]) is not int or not 0 <= payload["uptime_ms"] <= 2**64-1):
        raise ValueError("Invalid uptime")
    for identity in ("sample_id","boot_nonce"):
        if identity in payload and not ((isinstance(payload[identity],str) and 1<=len(payload[identity])<=128) or (type(payload[identity]) is int and 0<=payload[identity]<=2**64-1)):
            raise ValueError("Invalid device identity")
    for metadata in ("timestamp","phone_received_at"):
        if metadata in payload and (not isinstance(payload[metadata],str) or len(payload[metadata])>128):
            raise ValueError("Invalid device metadata")
    if len(json.dumps(payload,allow_nan=False)) > 16384:
        raise ValueError("Sensor payload too large")
    return payload


class Reading(ClosedModel):
    sample_id: str = Field(min_length=1,max_length=128,pattern=r"^[A-Za-z0-9_.:-]+$")
    captured_at: datetime
    source: Literal["synthetic","device_diagnostic"]
    payload: dict[str,Any]

    _aware = field_validator("captured_at")(aware_datetime)
    _bounded = field_validator("payload")(bounded_payload)


class LinkRequest(ClosedModel):
    code: str = Field(pattern=r"^\d{4}$")


class Reminder(ClosedModel):
    title: str = Field(min_length=1,max_length=120)
    dose: str = Field(default="",max_length=120)
    instructions: str = Field(default="",max_length=500)
    due_at: datetime
    repeat_hours: int | None = Field(default=None,strict=True,ge=1,le=720)

    _aware = field_validator("due_at")(aware_datetime)


class Consents(ClosedModel):
    location: StrictBool
    audio: StrictBool


class Location(ClosedModel):
    outside_home: StrictBool
    latitude: float = Field(ge=-90,le=90)
    longitude: float = Field(ge=-180,le=180)
    accuracy_m: float = Field(ge=0,le=10000)
    recorded_at: datetime

    _aware = field_validator("recorded_at")(aware_datetime)


class Event(ClosedModel):
    type: Literal["sos","possible_fall","audio_impact"]
    confirmed: StrictBool
    occurred_at: datetime
    source: Literal["synthetic","device_diagnostic"]

    _aware = field_validator("occurred_at")(aware_datetime)
