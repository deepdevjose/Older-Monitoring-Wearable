# IndiCare / CareMonitor v0.8.0 integration candidate

26 October 2026. This package integrates the newer assessment backend with the native Android application. It preserves the working v0.7.4 Bluetooth acquisition, HTTPS upload and firmware implementation. It is a candidate for device acceptance testing, not a claim of a tested phone deployment.

## What changed

- In the Android welcome screen, the top-right menu or Settings, choose **Server account**. Sign in using a password account created on this backend. The server supplies the role and assigned devices; there is no self-selected patient/caregiver role.
- Patient accounts can view their own assigned readings, charts, experimental classification, confidence and alerts. Caregivers can select their assigned patients and acknowledge their alerts. Assignment and permissions are checked on the server for each request/action.
- Account sessions expire, are encrypted with Android Keystore and are separate from upload API keys. Passwords are not saved. Logout removes the local account session; remote revocation is attempted and its failure is reported.
- Home/Sensors display stored readings and their timing, not an assumed live connection. Missing, invalid, stale, offline and model-unavailable states remain explicit. The classification request is tied to the exact database reading shown.
- The existing Bluetooth/local demo monitor remains available. Opening the account screens does not reconfigure the sensors or change the existing relay credentials. The local demo is explicitly separate from a server login.

## Model boundary

The supplied 60,000-row dataset has four binary targets only. Decision Tree and Random Forest were compared on the same four tasks with the same preprocessing and split. Both selected model families reproduced the synthetic test labels with accuracy, macro precision, macro recall and macro F1 of 1.0. The validation tie selected Decision Trees for simplicity/interpretability; there is no measured accuracy advantage. These are synthetic label-reproduction results, not clinical or cheaper-sensor accuracy.

The deployed-format trees return **NORMAL / ABNORMAL**. Android can render Warning and Critical if a future approved server model actually supplies those labels, but this release does not generate them. Confidence is an uncalibrated training-leaf class proportion, not medical certainty. MAX30102 heart rate/oxygen can be examined experimentally after timestamp/contact/quality gates. Surface temperature is not passed to a core-body-temperature model, and the device does not measure blood pressure.

**UNRESOLVED — THREE-CLASS TARGET DEFINITION REQUIRES HUMAN/TUTOR CONFIRMATION**

Provide labelled Normal/Warning/Critical data or an explicitly approved, task-specific severity mapping plus independent evaluation labels. Until then the binary model remains available. See `docs/ML_TARGET_AUDIT.txt` and `docs/INTEGRATION_STATUS.md`.

## Server setup on the teammate's computer

Use Python 3.12, the version used by the available test environment. From this extracted folder:

```sh
python3.12 -m venv .venv
. .venv/bin/activate
python -m pip install -r care_monitor_server/requirements.txt
python -m care_monitor_server.admin --db candidate-data/assessment.db init
```

Use a separate candidate database first. Do not point tests at the live database. Initialisation supplies the existing demonstration mapping `elder-demo-01` → `demo-patient-001`; those are pseudonyms, not claims about a real person. For a different deployment, use its confirmed device/patient mapping.

Create accounts locally. The following usernames are examples you choose to create; there are no default accounts/passwords:

```sh
python -m care_monitor_server.accounts_admin --database candidate-data/assessment.db create --username patient-demo --role patient --patient-id demo-patient-001
python -m care_monitor_server.accounts_admin --database candidate-data/assessment.db create --username caregiver-demo --role caregiver
python -m care_monitor_server.accounts_admin --database candidate-data/assessment.db assign --username caregiver-demo --patient-id demo-patient-001
```

Each create command privately prompts for a 12–128 character password twice. Keep passwords out of shared logs, screenshots and Git. No registration/admin HTTP route is exposed.

Start the **candidate entrypoint**, which includes the account routes and pinned model manifest:

```sh
python run_candidate.py --database candidate-data/assessment.db --host 0.0.0.0 --port 8010 --certfile /actual/path/to/server.crt --keyfile /actual/path/to/server.key
```

Replace those paths with existing files. A placeholder path will not work. The certificate must be trusted by the App and cover the current server IP/hostname. The APK retains the existing project public CA plus Android's normal certificate trust and hostname checks. Private keys/certificates for a new deployment must be provisioned locally; no private keys are distributed here. Do not disable TLS verification.

The older `uvicorn care_monitor_server.app:app` command starts the base device API; it does **not** add the account candidate routes. Use `run_candidate.py` for this integration. Port 8010 keeps the established port-8000 server separate. The phone and computer must be on a reachable network, with the chosen TCP port allowed locally.

For a new candidate database, create an upload credential separately:

```sh
python -m care_monitor_server.admin --db candidate-data/assessment.db create --label "Candidate test phone" --device-id elder-demo-01 --scope sensor:write
```

This command prints the new secret once. Enter it directly in the existing App Connections upload settings, using `https://ACTUAL_SERVER_ADDRESS:8010/sensor-data`. It is not the account password. Changing the server endpoint can require handling queued uploads through the App's existing approval flow; do not silently discard them. No production key/queue/server configuration was changed by this integration work.

## Android installation and acceptance test

The companion APK uses the existing package ID `org.caremonitor.demo`, versionName `0.8.0`, versionCode `17`, and the existing development signing identity. It is intended as an update of the project App. No phone was connected for this work, so installation, device-specific permission behaviour and UI/network operation are not yet verified.

1. Keep the stable v0.7.4 APK/source. Install the candidate APK through Android's normal install prompt, or use `adb install -r PATH_TO_APK` after connecting and authorising the phone. Do not uninstall the stable App merely to solve a signing error because that may erase its local settings/queues.
2. Open **Server account**. Enter `https://ACTUAL_SERVER_ADDRESS:8010`, the locally provisioned username and its password. A `404` here can mean the base/old server is running instead of `run_candidate.py`.
3. Verify the expected role and assigned device. Check Home, Sensors, Alerts and Profile. Use only synthetic/approved assessment data. Account views can read stored server data even without a Bluetooth connection.
4. Confirm the displayed reading ID corresponds to the backend response. Valid recent optical values may show a binary classification and confidence. Old, missing or invalid values must show unavailable rather than Normal.
5. Sign in as a different assigned caregiver and check access; an unassigned account must not see the other patient's records. Patients must not be able to acknowledge alerts. Test expiry/logout and server offline/recovery.
6. Separately verify the unchanged Bluetooth/HTTPS relay path on the actual devices when available. This step has not been performed for v0.8.0.

No Firebase project or push transport is set up. The existing 15-second backend response queue is software infrastructure, not confirmed caregiver delivery. Live alert policy stays disabled until its rules are approved/configured. There is no validated live fall, wearing or dehydration detector in this package.

## Build and software verification

The Android build uses the existing standalone SDK/JDK script (no Gradle dependency):

```sh
python care_monitor_native/scripts/build_apk.py --help
python care_monitor_native/scripts/test_reading_summary.py --help
python care_monitor_native/scripts/test_account_policy.py --help
python -m pip install -r care_monitor_server/requirements-test.txt
python -m pytest care_monitor_server/tests
```

On Windows, activate `.venv\Scripts\Activate.ps1` instead of the Linux activation command, or invoke `.venv\Scripts\python.exe` directly. Tests use temporary synthetic databases. Historical evidence copied from the earlier candidate is labelled separately from this integration's `evidence/integration/` results. A passing desktop test is not a physical-phone test.

The source archive includes verified unchanged firmware sources and the local dashboard, with no live databases, credentials, private keys, source datasets or virtual environments. The dataset's source/licence remains unresolved; reproduction requires the original CSV with the SHA-256 recorded in `docs/ML_TARGET_AUDIT.txt`. The existing dashboard reads SQLite directly on the server computer and is not a public authenticated web portal. Its older launcher is workspace-specific; see `care_monitor_dashboard/README.md` before using it on a different computer.

## Recorded software checks for this integration

- 302 backend tests plus 6 separately counted subtests passed, including 50 new stored-reading classification tests.
- 70 dashboard regression tests passed against temporary databases.
- 859 existing monitor-policy JVM checks and 140 new account-presentation JVM checks passed.
- Six ML export/split regression tests passed; existing trained model artifacts were verified, not retrained.
- 22 real localhost HTTPS requests passed with certificate/hostname verification enabled, temporary synthetic data and temporary accounts/credentials. This exercised server login, ingest, assigned reads, classification, alert review/acknowledgement and session invalidation. It did not use the Android client's runtime or phone Wi-Fi.
- The APK compiled, aligned and passed Android signature verification. The original v0.7.4 saved release still verifies all 138 source files and its existing APK hash.

See `evidence/integration/VERIFICATION.json` for scope and source hashes. Java/API deprecation warnings and the existing Starlette TestClient warning were nonfatal. No physical installation or phone result is claimed.
