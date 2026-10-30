"""English presentation of system messages, including previously stored defaults.

Only exact messages shipped by the application are translated. Names, reminder
instructions and any other user-authored text retain their original wording.
Encrypted historical records are not rewritten by this presentation adapter.
"""

ALERT_TEXT = {
    "pulse_low": (
        "Simulated low pulse",
        "The synthetic sequence illustrates a low pulse. Check the sample; this is not a diagnosis.",
    ),
    "pulse_high": (
        "Simulated high pulse",
        "The synthetic sequence illustrates a high pulse. Check the sample; this is not a diagnosis.",
    ),
    "oxygen_low": (
        "Simulated low oxygen",
        "The synthetic sequence illustrates low oxygen saturation. This is not a clinically validated reading.",
    ),
    "environment_extreme": (
        "Simulated extreme ambient temperature",
        "Review the environment and thermal comfort in the synthetic scenario.",
    ),
    "surface_temperature_extreme": (
        "Simulated surface temperature",
        "The GY906 represents surface temperature, which is affected by the environment; it cannot establish fever or hypothermia.",
    ),
}

_LEGACY_ALERT_TEXT = {
    "pulse_low": (
        "Pulso bajo simulado",
        "La secuencia sintética ilustra pulso bajo. Verificar la muestra; no es un diagnóstico.",
    ),
    "pulse_high": (
        "Pulso alto simulado",
        "La secuencia sintética ilustra pulso alto. Verificar la muestra; no es un diagnóstico.",
    ),
    "oxygen_low": (
        "Oxígeno bajo simulado",
        "La secuencia sintética ilustra saturación baja. No constituye una lectura clínica validada.",
    ),
    "environment_extreme": (
        "Ambiente extremo simulado",
        "Revisar el entorno del escenario sintético y su confort térmico.",
    ),
    "surface_temperature_extreme": (
        "Temperatura superficial simulada",
        "El GY906 representa temperatura superficial, influida por el ambiente; no permite concluir fiebre o hipotermia.",
    ),
}

REMINDER_MESSAGE = "Care reminder. Confirm the agreed instructions; do not double a missed dose."
_LEGACY_REMINDER_MESSAGE = "Recordatorio de cuidado. Confirma la indicación acordada; no dupliques una dosis omitida."


def english_system_text(detail, kind):
    """Return English system copy without altering the original detail mapping."""
    result = dict(detail)
    if kind == "alert":
        kind = detail.get("type")
    if kind in ALERT_TEXT:
        for index, field in enumerate(("title", "message")):
            if result.get(field) == _LEGACY_ALERT_TEXT[kind][index]:
                result[field] = ALERT_TEXT[kind][index]
    elif kind == "reminder" and result.get("message") == _LEGACY_REMINDER_MESSAGE:
        result["message"] = REMINDER_MESSAGE
    return result
