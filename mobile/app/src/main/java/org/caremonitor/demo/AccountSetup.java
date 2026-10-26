package org.caremonitor.demo;

/** Registration steps retain profile input only; the final password stays in the UI. */
final class AccountSetup {
    int step = 0;
    String role = "";
    String name = "";
    String email = "";

    /** Empty means the current step is valid; the server remains the final validator. */
    String problem() {
        switch (step) {
            case 0:
                return elder() || "caregiver".equals(role)
                    ? "" : "Elige si la cuenta es de adulto o cuidador.";
            case 1:
                int length = name == null ? 0 : name.trim().length();
                return length >= 1 && length <= 100
                    ? "" : "Escribe un nombre de entre 1 y 100 caracteres.";
            case 2:
                return validEmail(email)
                    ? "" : "Escribe un correo válido de hasta 254 caracteres.";
            case 3:
                return "";
            default:
                return "Vuelve al inicio del registro.";
        }
    }

    boolean next() {
        if (step >= 3 || !problem().isEmpty()) return false;
        step++;
        return true;
    }

    void back() {
        if (step > 0) step--;
    }

    boolean elder() {
        return "elder".equals(role);
    }

    private static boolean validEmail(String input) {
        if (input == null) return false;
        String value = input.trim();
        if (value.length() > 254) return false;
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@')) return false;
        String domain = value.substring(at + 1);
        if (!domain.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i)) || Character.isISOControl(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
