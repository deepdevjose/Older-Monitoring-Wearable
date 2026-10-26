# Care Monitor Android v0.8.0 integration candidate

See the package root `START-HERE.md` for server provisioning and the physical-device acceptance checklist. The account UI is implemented and compiled; no Android phone or emulator was available for this release's runtime tests.

The welcome screen, Settings and overflow menu provide **Server account**. The backend supplies the role and assigned patient devices. The server account screen is independent of the existing Bluetooth/local demo monitor and machine-upload API keys. The new account files are `AccountActivity`, `AccountApiClient`, `AccountSessionStore` and `AccountPresentationPolicy`.

Run `python scripts/build_apk.py --help` to specify an Android SDK, JDK and signing keystore. The build requires SDK platform 36 and build-tools 36.0.0. It produces `artifacts/CareMonitor-demo-debug.apk` plus `BUILD_EVIDENCE.json`. The private signing keystore is not included. A different signing key cannot update an already installed project build without the normal Android signing restrictions. Do not delete an existing App's data just to work around a key mismatch.

`scripts/test_reading_summary.py` exercises the unchanged monitor policies on a plain JVM; `scripts/test_account_policy.py` checks the new presentation/address rules. Neither runs the Android UI, Android Keystore or the networking classes on a phone.

The existing Classic Bluetooth and upload implementation is unchanged. `firmware/team_spp_no_sound/` contains only the active verified baseline sources and a credential template. No new firmware binary was generated or flashed. The older experimental FIFO reader and its build receipts are deliberately not part of this integration package.
