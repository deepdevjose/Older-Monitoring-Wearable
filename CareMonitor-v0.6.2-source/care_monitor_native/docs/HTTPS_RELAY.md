# Android HTTPS relay

`ServerRelay` is owned by the real-data foreground service. It does not start a
service, create fake data, derive clinical alerts, or automatically run in demo mode.
In v0.6.2 the caller passes each complete real classic SPP or BLE JSON snapshot to `enqueue` and controls
forwarding with `start`, `pause`, and `close`.

## Runtime configuration

The default form endpoint is `ServerRelay.DEFAULT_ENDPOINT`. The full HTTPS POST
URL and exact API key must be supplied by the team at runtime. The key must contain
16 to 1024 printable ASCII characters without whitespace. The application never
contains a default API key and never logs the key, a snapshot, or a server body.

`configure(endpoint, key)` validates immediately and can throw
`IllegalArgumentException`. Initial settings loading must finish first. Saving is
asynchronous; `isConfigured()` and the main-thread listener confirm the result.
The key is encrypted using an AES-GCM key in AndroidKeyStore; the endpoint is
authenticated as additional data. Ciphertext and IV are held in private
SharedPreferences. Loss/invalidation of the keystore key requires re-entering the
server key. App backup is disabled by the application manifest.

`endpoint()` returns an empty string while loading. `queuedEndpoint()` identifies
the endpoint bound to pending samples, for the explicit clear-queue confirmation.
Changing to another endpoint is rejected while pending or in-flight queued samples
belong to the original endpoint. The user must send those samples or explicitly
clear the queue first. Updating the API key for the same endpoint preserves the queue.

## Wi-Fi, certificates, and acknowledgements

Each connection uses `Network.openConnection` on an actual Wi-Fi transport, excluding
VPN transports. Cellular connectivity is never a fallback. No validated-internet
capability is required, so a local hotspot can reach a LAN server. The existing
phone/server firewall, Wi-Fi addressing, route, certificate SAN, and phone date
must still be correct.

The TLS trust manager accepts certificates trusted by Android or the public
`assets/server_ca.crt`. Normal HTTPS hostname verification remains enabled.
Bundled-certificate chains also have explicit validity-date checks. There is no
trust-all manager, hostname override, HTTP fallback, or redirect following. Only
the supplied public certificate is included; no server private key is packaged.

The relay only performs `POST`; it does not immediately poll with `GET`. Successful
requests are separated by at least 250 ms, above this server's shared 100 ms rate
limit. Each POST is limited to 16 KiB UTF-8 and must contain device ID
`elder-demo-01`. Other JSON fields remain present, including `sample_id` and
`boot_nonce` when supplied by the selected firmware. The relay does not invent
device-side identifiers absent from the payload. Network connect and socket read
timeouts are 5 seconds each.

An entry leaves the queue only after HTTP 2xx with JSON `status: "received"` and
a positive integer `record_id`, followed by a successful atomic queue update.
The bounded response reader permits 64 KiB because the existing endpoint echoes
the accepted payload in its acknowledgement.

**Delivery is at least once, not exactly once.** If the server commits a sample but
the response is lost, the retained sample may be sent again. The existing server
does not enforce `sample_id` uniqueness. Keeping that field allows the team to add
server-side idempotency later; this relay does not claim to prevent duplicates.

## Bounded durable queue and lifecycle

The private internal `AtomicFile` stores at most 200 complete samples in FIFO order,
bound to one canonical endpoint. API keys are never written into this file. A
maximum of 32 incoming snapshot tasks can wait behind network I/O. If either limit
is reached, the newest sample is dropped and the listener reports a visible drop
counter. Queue overflow preserves the older pending samples.

Missing Wi-Fi keeps the queue and retries periodically. Network errors, 408, 425,
429, and server failures retain the head with exponential retries from 1 to 30
seconds. Numeric `Retry-After` is bounded to 1–60 seconds. Redirects and other 4xx
responses pause forwarding until corrected settings are saved. There is no automatic
discard on an authentication or payload error. An unreadable queue or failed queue
write pauses forwarding and requires an explicit user recovery action; it does not
silently overwrite the stored file.

`pause` cancels the current delivery generation and keeps pending samples.
`clearQueue` is destructive and must be attached to a user confirmation. `close`
cancels HTTP, unregisters connectivity callbacks, drains already accepted sample
persistence tasks, and shuts down both owned executors. No network, encryption,
or file I/O runs on the main thread. A cancellation executor keeps potentially
blocking socket disconnection off the UI thread. HTTP completions are checked
against the current delivery generation before applying an acknowledged dequeue.

## Validation boundary

`RelayPolicyTest` runs on a plain JVM and exercises endpoint canonicalization and
rejection, API-key header validation, UTF-8 limits, retry and rate-limit policy,
HTTP pause decisions, and acknowledgement fields. The Android relay classes are
compiled against Android API 36 with Java 8 compatibility. These checks do not
prove physical Wi-Fi reachability, AndroidKeyStore behavior on a particular phone,
background service lifecycle, or successful TLS/POST delivery to the team's server.
The relay policy checks made no requests to the teammate's server. A separate read-only HTTPS GET health check from the development computer could not verify reachability; no sensor POST or API key was sent.
