"use strict";

// This page reads the local dashboard API. No sample data or cloud services are used.
const $ = id => document.getElementById(id);
const icons = {
  overview: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
  flask: '<path d="M9 3h6m-5 0v7L4 19a1.3 1.3 0 0 0 1 2h14a1.3 1.3 0 0 0 1-2l-6-9V3M7 15h10"/><path d="M10 18h1m3-1h1"/>',
  book: '<path d="M12 5v16M3 3c4-1 7 0 9 2 2-2 5-3 9-2v16c-4-1-7 0-9 2-2-2-5-3-9-2Z"/>',
  shield: '<path d="m12 3 8 3v5c0 5-4 8-8 10-4-2-8-5-8-10V6Z"/><path d="m8 12 3 3 5-6"/>',
  refresh: '<path d="M20 8V3l-3 3a8 8 0 1 0 3 9M20 8h-5"/>',
  device: '<rect x="6" y="4" width="12" height="16" rx="2"/><path d="M9 1v3m6-3v3M9 20v3m6-3v3M3 8h3M3 14h3m12-6h3m-3 6h3"/><path d="m9 12 2-3 2 6 2-3"/>',
  pulse: '<path d="M2 12h5l3-8 4 16 3-8h5"/>',
  heart: '<path d="M20.8 4.6a5.4 5.4 0 0 0-7.6 0L12 5.8l-1.2-1.2a5.4 5.4 0 0 0-7.6 7.6L12 21l8.8-8.8a5.4 5.4 0 0 0 0-7.6Z"/>',
  oxygen: '<path d="M12 2C8 8 5 10 5 15a7 7 0 0 0 14 0c0-5-3-7-7-13Z"/><path d="M8 15a4 4 0 0 0 4 4"/>',
  thermometer: '<path d="M9 14V5a3 3 0 0 1 6 0v9a5 5 0 1 1-6 0Z"/><path d="M12 8v10"/><circle cx="12" cy="18" r="1"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
  movement: '<path d="m3 8 5 1 3-4 4 4 4 1M10 9l-1 5-4 5m6-7 4 2 2 6"/><circle cx="15" cy="3" r="1.5"/>',
  bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M9 21h6"/>',
  record: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3" fill="currentColor" stroke="none"/>',
  layers: '<path d="m12 3 9 5-9 5-9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5"/>',
  download: '<path d="M12 3v12m-4-4 4 4 4-4M4 15v5h16v-5"/>'
};
document.querySelectorAll('[data-icon]').forEach(node => {
  const path = icons[node.dataset.icon];
  if (path) node.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + path + '</svg>';
});

const state = {
  device: "elder-demo-01", csrf: null, staleAfter: 10, refreshMs: 1000,
  snapshot: null, sessions: [], devices: [], trends: [], rows: [], captures: [],
  before: null, nextBefore: null, hasMore: false, selectedSession: "",
  sinceOpening: false, openingIds: new Map(), busy: false, mutation: false,
  lastSuccess: 0, lastCatalog: 0, failed: true, timer: null, pageGeneration: 0, pageBusy: false, sessionVersion: 0,
  signatures: {}, toastTimer: null, captureMutation: "", captureVersion: 0,
  captureExporting: false, captureLoaded: false, deviceChosen: false, captureFeedback: null
};
const formatter = new Intl.NumberFormat("en-GB");
const dateFormatter = new Intl.DateTimeFormat("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
const timeFormatter = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });

function text(id, value) {
  const target = $(id), content = String(value ?? "—");
  if (target && target.textContent !== content) target.textContent = content;
}
function finite(value) { return typeof value === "number" && Number.isFinite(value); }
function number(value, places = 0) { return finite(value) ? value.toFixed(places) : "—"; }
function timestamp(value) {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isFinite(parsed.getTime()) ? parsed : null;
}
function date(value) { const parsed = timestamp(value); return parsed ? dateFormatter.format(parsed) : "Not provided"; }
function shortTime(value) { const parsed = timestamp(value); return parsed ? timeFormatter.format(parsed) : "—"; }
function ageLabel(seconds) {
  if (!finite(seconds)) return "Time unavailable";
  const age = Math.max(0, Math.floor(seconds));
  if (age < 60) return `${age}s ago`;
  if (age < 3600) return `${Math.floor(age / 60)}m ago`;
  if (age < 86400) return `${Math.floor(age / 3600)}h ago`;
  return `${Math.floor(age / 86400)}d ago`;
}
function age(record) {
  if (!record) return Infinity;
  const elapsed = state.lastSuccess ? Math.max(0, (Date.now() - state.lastSuccess) / 1000) : 0;
  return finite(record.age_seconds) ? record.age_seconds + elapsed : Infinity;
}
function fresh(record) { return !state.failed && record && record.stale === false && age(record) <= state.staleAfter; }
function metric(record, key) { return record?.metrics?.[key] ?? {}; }
function currentValue(record, key) {
  const m = metric(record, key);
  return fresh(record) && m.valid === true && finite(m.value) ? m.value : null;
}
function historicalValue(record, key) {
  const m = metric(record, key);
  return m.sensor_valid === true && m.session_eligible !== false && finite(m.raw_value) ? m.raw_value : null;
}
function reasonLabel(reason) {
  const labels = {
    no_finger: "No finger detected", warming_up: "Sensor warming up", waiting: "Waiting for samples",
    read_error: "Sensor read error", invalid: "Invalid reading", invalid_flag: "Validity flag is false",
    offline: "Sensor offline", missing: "Not reported", missing_value: "Not reported", stale: "Historical reading",
    stale_sample: "Historical reading", delayed_upload: "Delayed upload", unknown_time: "Sample time unknown",
    before_session: "Sample predates test", after_session: "Sample follows test", invalid_payload: "Invalid payload",
    finger_not_detected: "No finger detected", invalid_status: "Sensor is not ready", no_data: "Not reported",
    low_quality: "Poor signal", poor_signal: "Poor signal", not_found: "Sensor not found",
    initializing: "Sensor initializing", buffering: "Collecting sensor samples",
    missing_or_non_numeric: "Measurement unavailable", sensor_not_ready_or_invalid: "Estimate unavailable or invalid",
    outside_physical_range: "Outside supported measurement range"
  };
  return labels[reason] || (reason ? String(reason).replaceAll("_", " ") : "Not reported");
}
function measurementReasonLabel(record, key, reason) {
  const optical = key === "heart_rate_bpm" || key === "spo2_percent";
  const unavailable = ["missing_or_non_numeric", "sensor_not_ready_or_invalid", "invalid_status", "missing", "missing_value", "no_data", "invalid_flag"].includes(reason);
  const status = record?.flags?.optical_status;
  const notReady = ["no_finger", "warming_up", "read_error", "low_quality", "poor_signal", "waiting", "offline", "not_found", "initializing", "buffering"].includes(status);
  // Explain missing optical estimates without obscuring range, age or payload failures.
  return optical && !finite(metric(record, key).raw_value) && unavailable && notReady
    ? reasonLabel(status) : reasonLabel(reason);
}
function setBadge(id, label, kind) {
  const node = $(id);
  if (node.dataset.label !== label || node.dataset.kind !== kind) {
    node.replaceChildren();
    node.append(document.createElement("i"), document.createTextNode(label));
    node.className = "status-pill " + kind;
    node.dataset.label = label; node.dataset.kind = kind;
  }
}
function message(id, value, error = false) {
  text(id, value); $(id).classList.toggle("error", error);
}
function toast(value) {
  text("toast", value); $("toast").classList.remove("hidden");
  clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(() => $("toast").classList.add("hidden"), 4500);
}

async function api(path, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch(path, { cache: "no-store", credentials: "same-origin", ...options, signal: controller.signal });
    if (!response.ok) {
      let detail;
      try { detail = (await response.json()).detail; } catch (_) { /* An HTML error is not rendered as markup. */ }
      throw new Error(typeof detail === "string" ? detail : `The local server returned ${response.status}.`);
    }
    return await response.json();
  } catch (error) {
    if (error.name === "AbortError") throw new Error("The local database took too long to respond.");
    throw error;
  } finally { clearTimeout(timeout); }
}
function query(values) {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => { if (value !== null && value !== undefined && value !== "") params.set(key, String(value)); });
  return params.toString();
}
function recordQuery(extra = {}) {
  return query({ device_id: state.device, session_id: state.selectedSession || null, ...extra });
}
function showFailure(error) {
  state.failed = true;
  text("connection-error", "The dashboard cannot reach the local database. Live values are hidden; any table rows or charts below are cached history. " + error.message);
  $("connection-error").classList.remove("hidden");
  renderCurrent();
  text("chart-cache-note", "Cached history · connection lost");
  text("acceleration-cache-note", "Cached history · connection lost");
  text("footer-status", "Database connection unavailable");
  $("start-session").disabled = true; $("stop-session").disabled = true; $("export-button").disabled = true;
  renderCapture();
}
async function poll(forceCatalog = false) {
  if (state.busy) return;
  state.busy = true;
  const deviceAtStart = state.device, sessionAtStart = state.selectedSession, sessionVersion = state.sessionVersion, captureVersion = state.captureVersion;
  try {
    if (!state.csrf) {
      const boot = await api("/api/bootstrap");
      state.csrf = boot.csrf_token;
      state.staleAfter = finite(boot.stale_after_seconds) ? boot.stale_after_seconds : 10;
      state.refreshMs = Math.max(1000, finite(boot.refresh_ms) ? boot.refresh_ms : 1000);
    }
    const catalog = forceCatalog || Date.now() - state.lastCatalog > 5000;
    const jobs = [api("/api/snapshot?" + query({device_id: deviceAtStart})), api("/api/records?" + recordQuery({limit: 120})), api("/api/captures?" + query({device_id: deviceAtStart}))];
    if (catalog) jobs.push(api("/api/devices"), api("/api/sessions"));
    const result = await Promise.all(jobs);
    if (deviceAtStart !== state.device || sessionAtStart !== state.selectedSession || sessionVersion !== state.sessionVersion) return;
    state.snapshot = result[0]; state.trends = result[1].records || [];
    if (!state.openingIds.has(state.device)) state.openingIds.set(state.device, state.snapshot.latest_record_id || 0);
    if (catalog) {
      state.devices = result[3].devices || []; state.sessions = result[4].sessions || [];
      state.lastCatalog = Date.now();
      if (!state.deviceChosen && state.devices.length === 1 && state.devices[0].device_id !== state.device) {
        state.device = state.devices[0].device_id; state.snapshot = null; state.trends = []; state.rows = [];
        state.captures = []; state.captureLoaded = false; state.signatures = {}; resetPage();
        renderCatalog(); renderCurrent(); renderCapture(); renderTable(); renderCharts();
        return;
      }
    }
    if (captureVersion === state.captureVersion && !state.captureMutation) {
      state.captures = result[2].captures || []; state.captureLoaded = true;
    }
    state.lastSuccess = Date.now(); state.failed = false;
    $("connection-error").classList.add("hidden");
    if (state.before === null) {
      const eligible = filteredRows(state.trends);
      state.rows = eligible.slice(0, 25);
      state.hasMore = eligible.length > 25 || (!state.sinceOpening && result[1].has_more === true);
      state.nextBefore = state.rows.length ? state.rows[state.rows.length - 1].id : null;
    }
    renderCatalog(); renderCurrent(); renderSession(); renderCapture(); renderCharts(); renderTable();
    text("footer-status", "Updated " + shortTime(new Date().toISOString()));
  } catch (error) { showFailure(error); }
  finally {
    state.busy = false;
    clearTimeout(state.timer);
    state.timer = setTimeout(() => poll(), state.refreshMs);
  }
}
function filteredRows(records) {
  return state.sinceOpening ? records.filter(row => row.id > (state.openingIds.get(state.device) || 0)) : records;
}
function renderCatalog() {
  const deviceSignature = JSON.stringify(state.devices.map(d => d.device_id));
  if (state.signatures.devices !== deviceSignature) {
    const devices = [...new Set([state.device, ...state.devices.map(d => d.device_id)])];
    const fragment = document.createDocumentFragment();
    devices.forEach(id => { const option = document.createElement("option"); option.value = id; option.textContent = id; fragment.append(option); });
    $("device-select").replaceChildren(fragment); $("device-select").value = state.device;
    state.signatures.devices = deviceSignature;
  }
  const sessions = deviceSessions();
  const signature = JSON.stringify(sessions.map(s => [s.id, s.name, s.status]));
  if (state.signatures.sessions !== signature) {
    const fragment = document.createDocumentFragment(), all = document.createElement("option");
    all.value = ""; all.textContent = "All test sessions"; fragment.append(all);
    sessions.forEach(session => {
      const option = document.createElement("option"); option.value = session.id;
      option.textContent = `${session.name} · ${session.status === "recording" ? "recording" : date(session.started_at)}`;
      fragment.append(option);
    });
    $("session-filter").replaceChildren(fragment); $("session-filter").value = state.selectedSession;
    state.signatures.sessions = signature;
  }
  $("export-button").disabled = !state.selectedSession || state.failed;
  $("export-button").title = state.selectedSession ? "Export the selected test session" : "Choose a test session to export its records";
  if (!state.selectedSession) message("export-message", "Choose a test session above to export its recorded data.");
}
function deviceSessions() { return state.sessions.filter(s => s.device_id === state.device); }
function activeSession() { return state.snapshot?.active_session || deviceSessions().find(s => s.status === "recording") || null; }
function deviceCaptures() {
  return state.captures.filter(capture => capture.device_id === state.device)
    .sort((a, b) => Number(b.id) - Number(a.id));
}
function activeCapture() { return deviceCaptures().find(capture => capture.status === "recording") || null; }
function pendingCapture() { return deviceCaptures().find(capture => ["recording", "finalizing"].includes(capture.status)) || null; }
function stoppedCapture() { return deviceCaptures().find(capture => capture.status === "stopped") || null; }
function captureReasonLabel(reason) {
  const labels = {
    heart_rate_invalid: "heart rate invalid", spo2_invalid: "SpO₂ invalid",
    heart_rate_outside_range: "heart rate outside supported range", spo2_outside_range: "SpO₂ outside supported range",
    movement_offline_or_missing: "accelerometer unavailable", acceleration_missing_or_non_numeric: "X/Y/Z incomplete",
    optical_not_ready: "optical sensor not ready", optical_missing: "optical data missing", optical_offline: "optical sensor offline",
    unknown_receipt_time: "receipt time unknown", future_receipt_time: "receipt time in future",
    unknown_measurement_time: "sample time unknown", future_measurement_time: "sample time in future",
    stale_at_receipt: "delayed sample", before_recording: "sample predates recording", after_recording: "sample follows recording",
    stale_or_invalid_optical_age: "optical estimate expired"
  };
  return labels[reason] || reasonLabel(reason).toLowerCase();
}
function renderCapture() {
  const active = activeCapture(), pending = pendingCapture(), finishing = pending?.status === "finalizing", stopped = stoppedCapture(), shown = pending || stopped;
  const locked = state.failed || !state.csrf || !state.captureLoaded || !!state.captureMutation || state.captureExporting;
  $("start-capture").classList.toggle("hidden", !!pending);
  $("stop-capture").classList.toggle("hidden", !pending);
  $("start-capture").disabled = locked || !!pending || !state.device;
  $("stop-capture").disabled = locked || !active || finishing;
  $("export-capture").disabled = state.failed || !state.captureLoaded || !!state.captureMutation || state.captureExporting || !!pending || !stopped;
  $("export-capture").title = finishing ? "The server is finishing the fixed recording; export becomes available when it is saved" : active ? "Stop this recording before exporting its fixed set of rows" : stopped ? `Export recording #${stopped.id}: ${stopped.name}` : "Start and stop a recording to export valid data";
  setBadge("capture-badge", state.captureMutation === "start" ? "Starting…" : state.captureMutation === "stop" ? "Stopping…" : finishing ? "Finishing recording…" : active ? "Recording valid data" : stopped ? "Recording saved" : "Ready to record", finishing ? "stale" : active || state.captureMutation ? "recording" : stopped ? "live" : "neutral");
  document.querySelector(".capture-toolbar").setAttribute("aria-busy", String(!!state.captureMutation || state.captureExporting));
  const valid = shown?.valid_count ?? 0, scanned = shown?.scanned_count ?? 0, skipped = shown?.skipped_count ?? 0;
  text("capture-counts", `${formatter.format(valid)} valid · ${formatter.format(scanned)} checked · ${formatter.format(skipped)} skipped`);
  text("capture-context", `${state.device} · ${shown ? `recording #${shown.id} · ${pending ? "started" : "saved"} ${shortTime(pending ? shown.started_at : shown.stopped_at)}` : "only fresh, valid HR + SpO₂ + X/Y/Z"}`);
  $("capture-context").title = shown ? `${shown.name} · started ${date(shown.started_at)}${shown.stopped_at ? " · stopped " + date(shown.stopped_at) : ""}. Each row contains heart rate, blood oxygen and all three acceleration axes from the same sample.` : "All five primary measurements must be valid in the same fresh sensor sample.";
  if (state.captureMutation || state.captureExporting) return;
  if (state.captureFeedback && state.captureFeedback.device === state.device && Date.now() < state.captureFeedback.until) {
    message("capture-message", state.captureFeedback.value, state.captureFeedback.error);
    return;
  }
  if (state.failed) {
    message("capture-message", "Connection unavailable. Any active recording remains saved on the local server; reconnect to check its progress.", true);
  } else if (!state.captureLoaded) {
    message("capture-message", "Loading saved recordings for this device…");
  } else if (finishing) {
    message("capture-message", `Finishing recording #${pending.id} · the stop time is fixed. The server is checking the remaining rows; export will be available when all rows are saved.`);
  } else if (active) {
    const reasons = Object.entries(active.skip_reasons || {}).filter(([, count]) => Number(count) > 0)
      .sort((a, b) => b[1] - a[1]).slice(0, 2)
      .map(([reason, count]) => `${captureReasonLabel(reason)} (${formatter.format(count)})`).join("; ");
    message("capture-message", valid > 0
      ? `Recording fresh, valid HR + SpO₂ + X/Y/Z from the same sample. ${reasons ? "Skipped: " + reasons + ". " : ""}Stop when finished to export CSV.`
      : scanned > 0 ? `Waiting for a sample with valid HR, SpO₂ and X/Y/Z together. ${reasons ? "Skipped: " + reasons + ". " : ""}Original readings remain in the database.`
        : "Recording started. Waiting for the next fresh sensor upload; earlier records are not included.");
  } else if (stopped) {
    message("capture-message", valid > 0
      ? `Recording #${stopped.id} saved with ${formatter.format(valid)} valid rows. Export CSV to download this recording. Original readings remain in the database.`
      : `Recording #${stopped.id} stopped with no valid paired samples. Export CSV contains column headers only; invalid readings remain in the database.`);
  } else {
    message("capture-message", "Start a recording to collect fresh, valid HR + SpO₂ + X/Y/Z together. Invalid readings stay in the database.");
  }
}
function renderCurrent() {
  const snapshot = state.snapshot, record = snapshot?.latest;
  const isFresh = fresh(record);
  if (snapshot) {
    text("total-count", formatter.format(snapshot.selected_records ?? snapshot.total_records ?? 0));
    text("latest-record-id", record ? "#" + record.id : "—");
    text("latest-record-status", record ? "Saved in the local database" : "No saved readings yet");
  }
  text("session-count", formatter.format(deviceSessions().length));
  text("last-received", record ? date(record.server_received_at) : "Waiting for data");
  const sample = record?.measurement_timestamp || record?.sensor_timestamp || record?.phone_received_at;
  text("sample-time", sample ? date(sample) : "Not provided");
  $("sample-time").title = record?.measurement_time_source === "phone" ? "Phone receipt time; the device did not provide an absolute sample timestamp" : "Timestamp reported by the sensor device";
  if (state.failed) {
    setBadge("connection-badge", "Connection lost", "offline");
    text("poll-status", state.lastSuccess ? "Last checked " + shortTime(new Date(state.lastSuccess).toISOString()) : "Local database unavailable");
    text("measurement-state", "LIVE VALUES UNAVAILABLE");
  } else if (!record) {
    setBadge("connection-badge", "Waiting for readings", "neutral");
    text("poll-status", "Database connected · checks every second");
    text("measurement-state", "AWAITING SENSOR DATA");
  } else if (isFresh) {
    setBadge("connection-badge", "Receiving data", "live");
    const provenance = record.measurement_time_source === "phone" ? "Phone receipt" : "Sample";
    text("poll-status", provenance + " · " + ageLabel(age(record)));
    text("measurement-state", "FRESH SAMPLE · NOT A DIAGNOSIS");
  } else {
    setBadge("connection-badge", record.delayed_upload ? "Delayed readings" : "Historical readings", "stale");
    text("poll-status", finite(age(record)) ? "Latest sample · " + ageLabel(age(record)) : "Sample time unavailable");
    text("measurement-state", "NO FRESH SAMPLE");
  }
  document.querySelectorAll(".metric-card").forEach(card => card.classList.toggle("is-stale", !isFresh));
  [
    ["heart", "heart_rate_bpm", 0], ["oxygen", "spo2_percent", 1],
    ["surface", "surface_temperature_c", 1], ["ambient", "ambient_temperature_c", 1]
  ].forEach(([name, key, digits]) => {
    const m = metric(record, key), value = currentValue(record, key);
    text(name + "-value", number(value, digits));
    const status = $(name + "-status");
    const label = !record ? "No reading yet" : state.failed ? "Connection unavailable" : !isFresh ? "No fresh sample" : finite(value) ? "Valid sensor reading" : measurementReasonLabel(record, key, m.reason || m.sensor_reason);
    if (status.textContent !== label) status.textContent = label;
    status.className = "reading-label " + (finite(value) ? "valid" : "invalid");
    status.title = m.sensor_reason ? measurementReasonLabel(record, key, m.sensor_reason) : label;
  });
  const accelKeys=["accel_x_g","accel_y_g","accel_z_g"];
  const liveVector=accelKeys.map(key=>currentValue(record,key));
  const savedVector=accelKeys.map(key=>historicalValue(record,key));
  const allLive=liveVector.every(finite), anySaved=savedVector.some(finite);
  const displayedVector=allLive ? liveVector : savedVector;
  ["x","y","z"].forEach((axis,index)=>text(`accel-${axis}-value`,number(displayedVector[index],3)));
  setBadge("acceleration-badge",allLive ? "Fresh acceleration" : anySaved ? (state.failed ? "Cached · last saved" : record?.delayed_upload ? "Delayed · last saved" : "Historical · last saved") : "No valid acceleration",allLive ? "live" : anySaved ? "stale" : "neutral");
  text("acceleration-time",record ? `#${record.id} · ${date(sample || record.server_received_at)}` : "Waiting for a saved sample");
  text("acceleration-detail",allLive ? "MPU6050 · acceleration includes gravity · not a fall classification" : anySaved ? "Last saved sensor-valid vector, not a current live reading · includes gravity" : "MPU6050 · no sensor-valid X / Y / Z values in the latest record");
  const light = currentValue(record, "light_adc"), percent = currentValue(record, "light_percent");
  text("light-value", finite(light) ? `${number(light)} ADC` : "— ADC");
  text("light-detail", finite(percent) ? `${number(percent,1)}% · sensor-reported scale, not lux` : "Photoresistor · raw signal, not lux");
  const flags = record?.flags || {};
  text("buzzer-value", isFresh && typeof flags.buzzer_active === "boolean" ? (flags.buzzer_active ? "On" : "Off") : "State not reported");
  text("buzzer-detail", Number.isInteger(flags.buzzer_pin) ? `GPIO ${flags.buzzer_pin} · no remote control` : "Actuator · no remote control");
  text("chart-cache-note", state.failed ? "Cached history · connection lost" : "Recorded history · gaps are invalid readings");
  text("acceleration-cache-note",state.failed ? "Cached history · connection lost" : "Recorded X / Y / Z · includes gravity");
}
function renderSession() {
  const active = activeSession(), signature = JSON.stringify(active ? [active.id, active.name, active.record_count, active.started_at] : null);
  if (state.signatures.active !== signature) {
    setBadge("recording-badge", active ? "Test in progress" : "No active test", active ? "recording" : "neutral");
    text("session-summary", active ? `Recording · ${active.name}` : "No test currently recording");
    text("recording-title", active ? `Recording: ${active.name}` : "Start a new test session");
    text("recording-detail", active ? `${active.participant_code} · ${formatter.format(active.record_count ?? 0)} records · started ${date(active.started_at)}` : "Ready to mark incoming readings");
    $("start-session").classList.toggle("hidden", !!active);
    $("stop-session").classList.toggle("hidden", !active);
    $("stop-session").title = active ? `Stop the active test: ${active.name}` : "No active test";
    $("session-form").querySelectorAll("input").forEach(input => { input.disabled = !!active; });
    state.signatures.active = signature;
  }
  $("start-session").disabled = state.failed || state.mutation;
  $("stop-session").disabled = state.failed || state.mutation;
}

const SVG_NS = "http://www.w3.org/2000/svg";
function svgElement(name, attrs = {}) {
  const element = document.createElementNS(SVG_NS, name);
  Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, String(value)));
  return element;
}
function chart(containerId, series, records, startId, endId) {
  const container = $(containerId), plotRecords = [...records].sort((a,b) => a.id - b.id);
  const allValues = series.flatMap(s => plotRecords.map(record => historicalValue(record, s.key)).filter(finite));
  const signature = JSON.stringify([plotRecords.map(r => [r.id, ...series.map(s => historicalValue(r,s.key))]), series]);
  if (state.signatures[containerId] === signature) return;
  state.signatures[containerId] = signature;
  if (!allValues.length) {
    const empty = document.createElement("div"); empty.className = "chart-empty";
    empty.textContent = plotRecords.length ? "No valid readings in these saved samples" : "Your recorded samples will appear here";
    container.replaceChildren(empty);
    container.setAttribute("aria-label", series.map(s=>s.name).join(" and ") + ". No valid saved samples.");
    text(startId, "—"); text(endId, "—"); return;
  }
  const width = 560, height = 166, left = 30, right = 16, top = 12, bottom = 11;
  let min = Math.min(...allValues), max = Math.max(...allValues);
  const acceleration = series.every(s=>s.key.startsWith("accel_"));
  const padding = Math.max((max-min)*.18, acceleration ? .08 : series.some(s=>s.key.includes("temperature")) ? 1 : 3);
  min -= padding; max += padding;
  const svg = svgElement("svg", {viewBox:`0 0 ${width} ${height}`,preserveAspectRatio:"none","aria-hidden":"true"});
  const y = value => top + (max-value)/(max-min)*(height-top-bottom);
  const x = index => left + (plotRecords.length > 1 ? index/(plotRecords.length-1) : .5)*(width-left-right);
  for (let index=0; index<4; index++) {
    const value = min + (max-min)*index/3, lineY = y(value);
    svg.append(svgElement("line",{x1:left,y1:lineY,x2:width-right,y2:lineY,stroke:"#eaf0e4","stroke-width":1,"stroke-dasharray":"3 4"}));
    const tick=svgElement("text",{x:left-8,y:lineY+3,"text-anchor":"end",fill:"#a8b69e","font-size":8,"font-family":"Segoe UI,Arial,sans-serif"});
    tick.textContent=number(value, acceleration ? 2 : series.some(s=>s.key.includes("temperature")) ? 1 : 0);svg.append(tick);
  }
  series.forEach(s => {
    let path = "", penDown = false, lastPoint = null;
    plotRecords.forEach((record,index) => {
      const value = historicalValue(record,s.key);
      if (!finite(value)) {penDown=false; return;}
      path += `${penDown ? "L" : "M"}${x(index).toFixed(2)} ${y(value).toFixed(2)} `;
      penDown=true; lastPoint = {x:x(index),y:y(value)};
      const dot = svgElement("circle",{cx:x(index),cy:y(value),r:plotRecords.length > 70 ? 1.1 : 1.8,fill:s.color});
      const title=svgElement("title");title.textContent=`${s.name}: ${number(value,s.digits)} ${s.unit} · record #${record.id} · ${date(record.measurement_timestamp || record.server_received_at)}`;dot.append(title);svg.append(dot);
    });
    if (path) svg.append(svgElement("path",{d:path,fill:"none",stroke:s.color,"stroke-width":1.9,"stroke-linecap":"round","stroke-linejoin":"round","vector-effect":"non-scaling-stroke"}));
    if (lastPoint) svg.append(svgElement("circle",{cx:lastPoint.x,cy:lastPoint.y,r:3.3,fill:s.color,stroke:"#fff","stroke-width":2}));
  });
  container.replaceChildren(svg);
  container.setAttribute("aria-label", `${series.map(s=>s.name).join(" and ")} recorded trends. ${plotRecords.length} saved samples. Invalid readings appear as gaps. The corresponding values are in the records table.`);
  const first=plotRecords[0],last=plotRecords[plotRecords.length-1];
  text(startId, shortTime(first?.measurement_timestamp || first?.server_received_at));
  text(endId, shortTime(last?.measurement_timestamp || last?.server_received_at));
}
function renderCharts() {
  const records = filteredRows(state.trends);
  chart("acceleration-chart",[{key:"accel_x_g",name:"Acceleration X",color:"#4b9a87",unit:"g",digits:3},{key:"accel_y_g",name:"Acceleration Y",color:"#b79261",unit:"g",digits:3},{key:"accel_z_g",name:"Acceleration Z",color:"#798bc5",unit:"g",digits:3}],records,"acceleration-chart-start","acceleration-chart-end");
  chart("vitals-chart",[{key:"heart_rate_bpm",name:"Heart rate",color:"#c26d7e",unit:"bpm",digits:0},{key:"spo2_percent",name:"Blood oxygen",color:"#7597c9",unit:"%",digits:1}],records,"vitals-chart-start","vitals-chart-end");
  chart("temperature-chart",[{key:"surface_temperature_c",name:"Surface temperature",color:"#d39565",unit:"°C",digits:1},{key:"ambient_temperature_c",name:"Ambient temperature",color:"#748dbe",unit:"°C",digits:1}],records,"temperature-chart-start","temperature-chart-end");
  text("vitals-chart-description", state.selectedSession ? "Selected test · valid recorded samples only" : state.sinceOpening ? "Since opening this page · valid recorded samples" : "Recent saved readings · valid recorded samples only");
  text("acceleration-chart-description",state.selectedSession ? "Selected test · recorded acceleration in g" : state.sinceOpening ? "Since opening · recorded acceleration in g" : "MPU6050 · actual recorded X, Y and Z values");
}
function cell(value, secondary, strong = false) {
  const td = document.createElement("td"), node=document.createElement(strong ? "strong" : "span");
  node.textContent = value; td.append(node);
  if (secondary) { const small=document.createElement("small");small.textContent=secondary;td.append(small); }
  return td;
}
function metricCell(record,key,digits,unit) {
  const m=metric(record,key), value=historicalValue(record,key);
  const reason=m.session_eligible === false ? m.reason : m.sensor_reason || m.reason;
  const td=cell(finite(value) ? `${number(value,digits)} ${unit}` : "—", finite(value) ? null : measurementReasonLabel(record, key, reason), finite(value));
  if (!finite(value)) td.title = finite(m.raw_value)
    ? (m.sensor_valid === true ? `Recorded raw value: ${m.raw_value} ${unit}. Excluded from this test: ${reasonLabel(m.reason)}.` : `Invalid raw estimate: ${m.raw_value} ${unit}. Excluded from the chart.`)
    : measurementReasonLabel(record, key, reason);
  return td;
}
function renderTable() {
  // Age fields change on every poll; update rows only when displayed data changes.
  const visibleRows = state.rows.map(record => [record.id, record.device_id, record.server_received_at,
    record.measurement_timestamp, record.sensor_timestamp, record.phone_received_at, record.uptime_ms,
    record.measurement_time_source, record.quality, record.stale, record.delayed_upload,
    record.session_sample_alignment, record.flags?.optical_status,
    ...["heart_rate_bpm","spo2_percent","accel_x_g","accel_y_g","accel_z_g","surface_temperature_c","ambient_temperature_c"].map(key=>{
      const m=metric(record,key);return [m.raw_value,m.sensor_valid,m.session_eligible,m.sensor_reason];
    })]);
  const signature = JSON.stringify([visibleRows,state.device,state.selectedSession,state.sinceOpening,state.before]);
  if (state.signatures.table !== signature) {
    const fragment=document.createDocumentFragment();
    state.rows.forEach(record => {
      const tr=document.createElement("tr");
      const id=cell(""); id.firstChild.className="record-id";id.firstChild.textContent="#"+record.id;tr.append(id);
      const received=cell(shortTime(record.server_received_at), date(record.server_received_at).split(",")[0]);received.title=date(record.server_received_at);tr.append(received);
      const sample=record.measurement_timestamp || record.sensor_timestamp || record.phone_received_at;
      const timing = sample ? `${record.measurement_time_source === "phone" ? "Phone" : "Sample"}: ${shortTime(sample)}` : `Uptime: ${finite(record.uptime_ms) ? number(record.uptime_ms/1000,1)+" s" : "unknown"}`;
      const device=cell(record.device_id,timing);device.title=sample ? date(sample) : "No absolute sample timestamp provided";tr.append(device);
      tr.append(metricCell(record,"heart_rate_bpm",0,"bpm"),metricCell(record,"spo2_percent",1,"%"),metricCell(record,"accel_x_g",3,"g"),metricCell(record,"accel_y_g",3,"g"),metricCell(record,"accel_z_g",3,"g"),metricCell(record,"surface_temperature_c",1,"°C"),metricCell(record,"ambient_temperature_c",1,"°C"));
      const validHr=finite(historicalValue(record,"heart_rate_bpm")),validOxygen=finite(historicalValue(record,"spo2_percent"));
      const validAcceleration=["accel_x_g","accel_y_g","accel_z_g"].every(key=>finite(historicalValue(record,key)));
      const quality=cell("");const badge=document.createElement("span");
      const preSession=record.session_sample_alignment==="before_session", timeUnknown=record.quality==="unknown_time";
      badge.className="quality "+((validHr && validOxygen) || validAcceleration ? "valid" : "review");
      badge.textContent=preSession ? "Queued before test" : validHr && validOxygen ? (validAcceleration ? "Optical & accel valid" : "Optical valid") : validAcceleration ? "Acceleration valid" : reasonLabel(record.flags?.optical_status || "Optical unavailable");
      quality.replaceChildren(badge);
      const note=document.createElement("small");
      const opticalNote=!(validHr && validOxygen) && validAcceleration ? " · optical " + reasonLabel(record.flags?.optical_status || "unavailable").toLowerCase() : "";
      note.textContent=(preSession ? "Excluded from test values" : timeUnknown ? "Sample time unknown" : record.delayed_upload ? "Delayed upload" : record.stale ? "Historical sample" : "Saved sample")+opticalNote;
      quality.append(note);tr.append(quality);fragment.append(tr);
    });
    if (!state.rows.length) {
      const tr=document.createElement("tr"), td=document.createElement("td");td.colSpan=11;td.className="table-empty";
      td.textContent=state.sinceOpening ? "No new readings since you opened this page. Connect your phone and enable server uploads to collect new data." : state.selectedSession ? "No readings were received during this test yet." : "No saved readings for this device yet. Incoming phone uploads will appear automatically.";
      tr.append(td);fragment.append(tr);
    }
    $("records-body").replaceChildren(fragment);state.signatures.table=signature;
  }
  text("table-count", `${state.rows.length} RECORDS SHOWN`);
  $("older-page").disabled=state.failed || state.pageBusy || !state.hasMore || !state.nextBefore;
  $("latest-page").classList.toggle("hidden",state.before===null);
  const session=deviceSessions().find(s=>String(s.id)===String(state.selectedSession));
  const context=session ? `Test: ${session.name}` : state.sinceOpening ? "Only records received since this page opened" : "Showing saved readings · newest first";
  text("table-footer-note",context+(state.before!==null ? " · viewing older records" : ""));
}
function resetPage() {state.before=null;state.nextBefore=null;state.hasMore=false;state.pageBusy=false;state.pageGeneration++;state.signatures.table=null;}
async function olderPage() {
  if (!state.nextBefore || state.failed || state.pageBusy) return;
  const generation=state.pageGeneration, selection=[state.device,state.selectedSession,state.sinceOpening].join("|");
  state.pageBusy=true;
  $("older-page").disabled=true;
  try {
    const cursor=state.nextBefore;
    const result=await api("/api/records?"+recordQuery({before_id:cursor,limit:25}));
    if(generation!==state.pageGeneration || selection!==[state.device,state.selectedSession,state.sinceOpening].join("|"))return;
    state.rows=filteredRows(result.records||[]);state.before=cursor;
    state.nextBefore=result.next_before_id || (state.rows.length ? state.rows[state.rows.length-1].id : null);
    state.hasMore=result.has_more===true && (!state.sinceOpening || state.rows.length===25);
    renderTable();
  } catch(error) {if(generation===state.pageGeneration)toast(error.message);} finally {
    if(generation===state.pageGeneration){state.pageBusy=false;$("older-page").disabled=state.failed||!state.hasMore||!state.nextBefore;}
  }
}
async function mutate(path, body) {
  if (state.mutation) return null;
  state.mutation=true;renderSession();
  try {
    const result=await api(path,{method:"POST",headers:{"Content-Type":"application/json","X-CSRF-Token":state.csrf},body:JSON.stringify(body)});
    state.sessionVersion++;
    if(result && result.id && result.device_id){
      state.sessions=[result,...state.sessions.filter(session=>session.id!==result.id)];
      if(state.snapshot && state.device===result.device_id)state.snapshot.active_session=result.status==="recording"?result:null;
      state.signatures.sessions=null;state.lastCatalog=0;renderCatalog();
    }
    return result;
  } finally {state.mutation=false;renderSession();}
}
function captureFeedback(value, error = false) {
  state.captureFeedback = {value, error, device: state.device, until: Date.now() + 10000};
  message("capture-message", value, error);
}
async function mutateCapture(action, capture = null) {
  if (state.captureMutation || state.captureExporting || state.failed || !state.csrf || !state.captureLoaded) return;
  const device = state.device;
  state.captureMutation = action; state.captureFeedback = null; state.captureVersion++;
  message("capture-message", action === "start" ? "Starting valid-data recording…" : "Finishing the recording and saving its valid rows…");
  renderCapture();
  try {
    const path = action === "start" ? "/api/captures" : `/api/captures/${encodeURIComponent(capture.id)}/stop`;
    const response = await api(path, {method: "POST", headers: {"Content-Type": "application/json", "X-CSRF-Token": state.csrf}, body: JSON.stringify(action === "start" ? {device_id: device} : {})});
    const saved = response.capture || response;
    if (!saved.id || saved.device_id !== device) throw new Error("The server returned an unexpected recording. Refresh to check its saved state.");
    state.captureVersion++;
    if (state.device === device) state.captures = [saved, ...state.captures.filter(item => item.id !== saved.id)];
    toast(action === "start" ? "Recording started · valid paired samples only" : "Recording stopped · CSV is ready to export");
  } catch (error) {
    if (state.device === device) captureFeedback(error.message, true);
  } finally {
    state.captureMutation = ""; renderCapture();
    await poll(true);
  }
}
$("start-capture").addEventListener("click", () => {
  if (!pendingCapture()) mutateCapture("start");
});
$("stop-capture").addEventListener("click", () => {
  const active = activeCapture();
  if (active) mutateCapture("stop", active);
});
$("export-capture").addEventListener("click", async () => {
  const stopped = stoppedCapture();
  if (!stopped || pendingCapture() || state.failed || state.captureExporting || state.captureMutation) return;
  const device = state.device, controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 30000);
  state.captureExporting = true; state.captureFeedback = null;
  message("capture-message", `Preparing valid-data CSV for recording #${stopped.id}…`); renderCapture();
  try {
    const response = await fetch(`/api/captures/${encodeURIComponent(stopped.id)}/export.csv?limit=20000`, {credentials: "same-origin", cache: "no-store", signal: controller.signal});
    if (!response.ok) throw new Error(`Valid-data CSV export failed (${response.status}).`);
    const blob = await response.blob(), url = URL.createObjectURL(blob), link = document.createElement("a");
    link.href = url; link.download = `care-monitor-valid-data-${String(stopped.id).replace(/[^a-zA-Z0-9_-]/g, "")}.csv`;
    document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 10000);
    const truncated = ["true", "1"].includes((response.headers.get("X-Export-Truncated") || "").toLowerCase());
    if (state.device === device) captureFeedback(truncated
      ? "Partial CSV downloaded: the export contains the first 20,000 valid rows. The full recording remains in the database."
      : stopped.valid_count > 0 ? "CSV downloaded · every row contains valid heart rate, SpO₂ and X/Y/Z from the same sensor sample."
        : "CSV downloaded with column headers only. This recording did not receive a valid paired sample.", truncated);
    toast(truncated ? "Partial CSV downloaded · 20,000-row limit" : "Valid-data CSV downloaded");
  } catch (error) {
    if (state.device === device) captureFeedback(error.name === "AbortError" ? "CSV export timed out. Please try again." : error.message, true);
  } finally {
    clearTimeout(timeout); state.captureExporting = false; renderCapture();
  }
});
$("session-form").addEventListener("submit",async event=>{
  event.preventDefault();if (!$("session-form").reportValidity()) return;
  const body={device_id:state.device,participant_code:$("participant-id").value.trim(),name:$("test-label").value.trim(),firmware_version:$("firmware-version").value.trim(),placement:$("placement").value.trim()};
  message("session-message","Starting test…");
  try {
    await mutate("/api/sessions",body);
    message("session-message","Test started. New server records will be marked for this session.");
    toast("Test started · incoming readings are being recorded");state.lastCatalog=0;await poll(true);
  } catch(error) {message("session-message",error.message,true);}
});
$("stop-session").addEventListener("click",async()=>{
  const active=activeSession();if(!active)return;
  message("session-message",`Stopping test: ${active.name}…`);
  try {
    await mutate(`/api/sessions/${encodeURIComponent(active.id)}/stop`,{});
    message("session-message",`Test “${active.name}” saved. Device uploads continue to the database.`);
    toast("Test saved · your recorded data is preserved");state.lastCatalog=0;await poll(true);
  } catch(error) {message("session-message",error.message,true);}
});
$("session-filter").addEventListener("change",()=>{
  state.selectedSession=$("session-filter").value;resetPage();
  state.trends=[];state.rows=[];renderCharts();renderTable();
  const placeholder=$("records-body").querySelector(".table-empty");if(placeholder)placeholder.textContent="Loading the selected test records…";
  message("export-message",state.selectedSession ? "CSV export contains this session’s received records, quality flags and raw values. Large exports are capped at 10,000 records." : "Choose a test session above to export its recorded data.");
  poll();
});
$("device-select").addEventListener("change",()=>{
  state.deviceChosen=true;state.captures=[];state.captureLoaded=false;state.captureFeedback=null;
  state.device=$("device-select").value;state.selectedSession="";state.snapshot=null;state.trends=[];state.rows=[];
  state.lastCatalog=0;state.signatures={};resetPage();renderCurrent();renderCapture();renderTable();renderCharts();poll(true);
});
function setMode(isNew) {
  state.sinceOpening=isNew;resetPage();
  $("all-data-button").classList.toggle("selected",!isNew);$("new-data-button").classList.toggle("selected",isNew);
  $("all-data-button").setAttribute("aria-pressed",String(!isNew));$("new-data-button").setAttribute("aria-pressed",String(isNew));
  state.rows=filteredRows(state.trends).slice(0,25);renderCharts();renderTable();poll();
}
$("all-data-button").addEventListener("click",()=>setMode(false));
$("new-data-button").addEventListener("click",()=>setMode(true));
$("older-page").addEventListener("click",olderPage);
$("latest-page").addEventListener("click",()=>{resetPage();poll();});
$("refresh-button").addEventListener("click",()=>{state.lastCatalog=0;poll(true);});
$("export-button").addEventListener("click",async()=>{
  if (!state.selectedSession || state.failed) return;
  const id=state.selectedSession;$("export-button").disabled=true;
  message("export-message","Preparing the selected session’s CSV…");
  const controller=new AbortController(), timeout=setTimeout(()=>controller.abort(),30000);
  try {
    const response=await fetch(`/api/sessions/${encodeURIComponent(id)}/export.csv?limit=10000`,{credentials:"same-origin",cache:"no-store",signal:controller.signal});
    if (!response.ok) throw new Error(`CSV export failed (${response.status}).`);
    const blob=await response.blob(), url=URL.createObjectURL(blob), link=document.createElement("a");
    link.href=url;link.download=`care-monitor-test-${String(id).replace(/[^a-zA-Z0-9_-]/g,"")}.csv`;
    document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);
    const truncated=["true","1"].includes((response.headers.get("X-Export-Truncated")||"").toLowerCase());
    message("export-message",truncated ? "Downloaded a partial export: this session exceeds 10,000 records. The CSV contains the first 10,000 records, with quality flags. Keep this limit in mind when analysing results." : "CSV downloaded. Invalid raw estimates remain labelled; they are not valid measurements.",truncated);
    toast(truncated ? "Partial CSV downloaded · 10,000-record limit reached" : "Test session CSV downloaded");
  } catch(error) {message("export-message",error.name==="AbortError" ? "CSV export timed out. Please try again." : error.message,true);}
  finally {clearTimeout(timeout);$("export-button").disabled=!state.selectedSession||state.failed;}
});
document.querySelectorAll(".nav-item").forEach(link=>link.addEventListener("click",()=>{
  document.querySelectorAll(".nav-item").forEach(item=>item.classList.toggle("active",item===link));
}));
if ("IntersectionObserver" in window) {
  const observer=new IntersectionObserver(entries=>{
    const visible=entries.filter(entry=>entry.isIntersecting).sort((a,b)=>b.intersectionRatio-a.intersectionRatio)[0];
    if (visible) document.querySelectorAll(".nav-item").forEach(link=>link.classList.toggle("active",link.hash==="#"+visible.target.id));
  },{rootMargin:"-5% 0px -45% 0px",threshold:[0,.2,.5]});
  document.querySelectorAll(".page-section").forEach(section=>observer.observe(section));
}
function tick() {
  text("clock",new Intl.DateTimeFormat("en-GB",{day:"2-digit",month:"short",year:"numeric"}).format(new Date()));
  if(state.lastSuccess)renderCurrent();
}
document.addEventListener("visibilitychange",()=>{if(!document.hidden)poll(true);});
window.addEventListener("online",()=>poll(true));
tick();setInterval(tick,1000);renderCharts();poll(true);
