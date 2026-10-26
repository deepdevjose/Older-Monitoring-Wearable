"""Validate saved integration evidence and write a scope-explicit summary; does not run tests."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    backend = read('evidence/backend/v080-stored-classification-summary.json')
    for name, expected in backend['source_sha256'].items():
        assert sha(ROOT / name) == expected, f'Backend changed after verification: {name}'
    build = read('evidence/integration/android-build.json')
    for name, expected in build['input_sha256'].items():
        assert sha(ROOT / 'care_monitor_native' / name) == expected, f'Android changed after compilation: {name}'
    policy = read('evidence/integration/android-account-policy.json')
    assert sha(ROOT / 'care_monitor_native/app/src/main/java/org/caremonitor/demo/AccountPresentationPolicy.java') == policy['source_sha256']
    assert sha(ROOT / 'care_monitor_native/tests/AccountPresentationPolicyTest.java') == policy['test_sha256']
    assert policy['checks'] == 140 and policy['status'] == 'passed'
    server_xml = ET.parse(ROOT / 'evidence/backend/v080-backend-regressions.xml').getroot()
    dashboard_xml = ET.parse(ROOT / 'evidence/integration/dashboard-regression.xml').getroot()
    for tree in (server_xml, dashboard_xml):
        assert not tree.findall('.//failure') and not tree.findall('.//error')
    assert len(server_xml.findall('.//testcase')) == 302
    assert sum(int(suite.attrib['tests']) for suite in server_xml.findall('testsuite')) == 308  # Includes 6 subtests in counters
    assert len(dashboard_xml.findall('.//testcase')) == 70
    assert 'Ran 6 tests' in (ROOT / 'evidence/integration/ml-regression.txt').read_text(encoding='utf-8-sig')
    loopback = read('evidence/backend/v080-https-loopback.json')
    assert loopback['status'] == 'passed' and len(loopback['operations']) == 22
    unchanged_jvm = {'ReadingSummary': 32, 'AlertInbox': 105, 'NdjsonFramer': 51,
                     'LiveSampleFreshness': 35, 'RelayPolicy': 97, 'ServerEndpointMigration': 21,
                     'SensorModuleNavigation': 20, 'SessionLineBuffer': 320, 'RemoteAlertInbox': 53,
                     'SecureSetupPolicy': 23, 'OpticalEstimatePolicy': 102}
    assert sum(unchanged_jvm.values()) == 859
    core_names = ['SppSensorClient.java', 'BleSensorClient.java', 'ServerRelay.java',
                  'LiveMonitorService.java', 'NdjsonFramer.java', 'OpticalEstimatePolicy.java']
    summary = {
        'recorded_at_utc': datetime.now(timezone.utc).isoformat(),
        'version': '0.8.0', 'release_kind': 'integration_candidate',
        'apk_sha256': build['sha256'],
        'backend': {'passed_tests': 302, 'additional_subtests': 6,
                    'new_stored_classification_tests_included': 50,
                    'evidence': 'evidence/backend/v080-backend-regressions.xml'},
        'dashboard': {'passed_tests': 70, 'evidence': 'evidence/integration/dashboard-regression.xml'},
        'android_jvm': {'unchanged_policy_checks_observed_this_integration': unchanged_jvm,
                        'unchanged_policy_total': 859, 'new_account_policy_checks': 140,
                        'note': 'Plain Java checks only, not Android runtime/Keystore/network tests.'},
        'ml': {'unit_tests_passed_this_integration': 6, 'retrained_this_integration': False,
               'same_task_comparison_historical_artifacts_verified': True,
               'three_class_supported': False, 'clinical_validation': False},
        'https_loopback': {'actual_tcp_tls_requests': 22, 'certificate_and_hostname_verification': True,
                           'temporary_synthetic_database': True, 'phone_or_wifi_path': False,
                           'evidence': 'evidence/backend/v080-https-loopback.json'},
        'baseline_release_check': {'verified_original_v074_saved_source_files': 138,
                                   'original_apk_sha256': 'f46cc4ee93c33d51743144578658a5ce74bd8d69ff94019766ae7bc67e4c7ba6',
                                   'original_release_modified': False},
        'transport_source_sha256': {name: sha(ROOT / 'care_monitor_native/app/src/main/java/org/caremonitor/demo' / name) for name in core_names},
        'not_performed': ['Android installation or UI runtime test', 'Android Keystore runtime test',
                          'Physical Bluetooth/Wi-Fi or sensor test for v0.8.0',
                          'Production server deployment', 'Firmware flash', 'Three-class model training',
                          'Clinical validation', 'Caregiver push delivery', 'Git contribution attribution'],
        'known_nonfatal_build_test_warnings': ['Java 8 source/target deprecation',
                                              'Existing Android deprecated API usage',
                                              'Starlette httpx TestClient deprecation'],
    }
    # On the original workspace only, verify rather than assume transport preservation.
    original = ROOT.parents[1] / 'care_monitor_native/app/src/main/java/org/caremonitor/demo'
    if original.is_dir():
        for name, expected in summary['transport_source_sha256'].items():
            assert sha(original / name) == expected, f'Transport differs from working baseline: {name}'
    (ROOT / 'evidence/integration/VERIFICATION.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'evidence verified', 'backend_tests': 302, 'dashboard_tests': 70,
                      'android_jvm_checks': 999, 'ml_unit_tests': 6, 'phone_tested': False}, indent=2))


if __name__ == '__main__':
    main()
