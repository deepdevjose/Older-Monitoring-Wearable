package org.caremonitor.demo;

/**
 * Narrow upgrade policy for the team's move to the local computer.
 * Only the former built-in receiver changes. Credentials, queued readings, and alert
 * cache identities must never be silently carried to the new server.
 */
final class ServerEndpointMigration {
    static final String PREVIOUS_DEFAULT = "https://10.238.118.117:8000/sensor-data";

    static final class Selection {
        final String endpoint;
        final boolean requiresNewCredential;
        Selection(String endpoint, boolean requiresNewCredential) {
            this.endpoint = endpoint;
            this.requiresNewCredential = requiresNewCredential;
        }
    }

    private ServerEndpointMigration() {}

    static Selection select(String savedEndpoint) {
        if (savedEndpoint == null || savedEndpoint.trim().isEmpty()) {
            return new Selection(RelayPolicy.DEFAULT_ENDPOINT, false);
        }
        String canonical = RelayPolicy.endpoint(savedEndpoint);
        if (PREVIOUS_DEFAULT.equals(canonical)) {
            return new Selection(RelayPolicy.DEFAULT_ENDPOINT, true);
        }
        return new Selection(canonical, false);
    }
}
