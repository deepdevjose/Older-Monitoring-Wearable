package org.caremonitor.demo;

/** UI copy for alert events. The host screen identifies simulated events. */
public final class AlertCatalog {
    private AlertCatalog() {
    }

    public enum Severity {
        URGENT, ATTENTION, NOTICE, INFO
    }

    public enum Type {
        POSSIBLE_FALL(
                "possible_fall", "Possible fall detected",
                "Check on the person and review the movement event.",
                "Motion sensor", "Fall classification is not connected.",
                Severity.URGENT, true),
        BLOOD_PRESSURE_HIGH(
                "blood_pressure_high", "Blood pressure increased",
                "Review the event and confirm the blood pressure reading source.",
                "Blood pressure", "No blood pressure sensor is connected.",
                Severity.ATTENTION, true),
        HEART_RATE_HIGH(
                "heart_rate_high", "High heart rate",
                "Check the latest heart rate reading and signal quality.",
                "Optical pulse sensor", "Live heart rate alert detection is not connected.",
                Severity.ATTENTION, true),
        HEART_RATE_LOW(
                "heart_rate_low", "Low heart rate",
                "Review the heart rate reading and check sensor contact.",
                "Optical pulse sensor", "Live heart rate alert detection is not connected.",
                Severity.ATTENTION, true),
        OXYGEN_LOW(
                "oxygen_low", "Low blood oxygen",
                "Check the latest SpO2 estimate and sensor contact.",
                "Optical pulse sensor", "Live SpO2 alert detection is not connected.",
                Severity.ATTENTION, true),
        OXYGEN_HIGH(
                "oxygen_high", "High oxygen reading",
                "Review the oxygen reading and signal quality.",
                "Optical pulse sensor", "Live SpO2 alert detection is not connected.",
                Severity.ATTENTION, true),
        BODY_TEMP_HIGH(
                "body_temp_high", "High surface temperature",
                "Review the surface reading and confirm the measurement conditions.",
                "Surface temperature sensor",
                "The current sensor measures surface temperature, not validated core body temperature.",
                Severity.ATTENTION, true),
        BODY_TEMP_LOW(
                "body_temp_low", "Low surface temperature",
                "Check the surface reading and sensor placement.",
                "Surface temperature sensor",
                "The current sensor measures surface temperature, not validated core body temperature.",
                Severity.ATTENTION, true),
        AMBIENT_TEMP_HIGH(
                "ambient_temp_high", "High ambient temperature",
                "Check the surrounding temperature and sensor placement.",
                "Ambient temperature sensor", "Live ambient temperature alert detection is not connected.",
                Severity.ATTENTION, true),
        AMBIENT_TEMP_LOW(
                "ambient_temp_low", "Low ambient temperature",
                "Review the surrounding temperature and measurement conditions.",
                "Ambient temperature sensor", "Live ambient temperature alert detection is not connected.",
                Severity.ATTENTION, true),
        WATCH_NOT_WORN(
                "watch_not_worn", "Watch not worn",
                "Check that the watch is worn and review the monitoring status.",
                "Wear status", "Watch-wear detection is not connected.",
                Severity.NOTICE, true),
        WATCH_WORN(
                "watch_worn", "Watch worn",
                "Watch-wear event received. Review device status.",
                "Wear status", "Watch-wear detection is not connected.",
                Severity.INFO, false),
        HYDRATION_CHECK(
                "hydration_check", "Hydration check needed",
                "Check in with the person and review the hydration reminder.",
                "Hydration reminder", "No hydration or dehydration detector is connected.",
                Severity.NOTICE, true);

        public final String id;
        public final String title;
        public final String message;
        public final String source;
        public final String capability;
        public final Severity severity;
        public final boolean requiresReview;

        Type(String id, String title, String message, String source,
                String capability, Severity severity, boolean requiresReview) {
            this.id = id;
            this.title = title;
            this.message = message;
            this.source = source;
            this.capability = capability;
            this.severity = severity;
            this.requiresReview = requiresReview;
        }
    }

    public static Type find(String id) {
        if (id == null) {
            return null;
        }
        for (Type type : Type.values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}
