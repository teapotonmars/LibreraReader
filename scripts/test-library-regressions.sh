#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$task_root"
device_serial=""
gradle_options=(--no-configuration-cache)
while (($#)); do
    case "$1" in
        --offline) gradle_options+=(--offline); shift ;;
        --device) device_serial=${2:?Provide an adb device serial}; shift 2 ;;
        *) echo "Usage: $0 [--offline] [--device SERIAL]" >&2; exit 2 ;;
    esac
done
./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug :app:assembleFdroidDebugAndroidTest "${gradle_options[@]}"
arm64_apk=$(python3 scripts/validate-reader-apk.py app/build/outputs/apk/fdroid/debug)
echo "Validated native reader libraries in $arm64_apk"
if [[ -n "$device_serial" ]]; then
    adb -s "$device_serial" install -r "$arm64_apk"
    adb -s "$device_serial" install -r app/build/outputs/apk/androidTest/fdroid/debug/app-fdroid-debug-androidTest.apk
    test_log=app/build/library-regression-device.log
    adb -s "$device_serial" shell am instrument -w -r \
        com.foobnix.pro.pdf.reader.test/androidx.test.runner.AndroidJUnitRunner > "$test_log" 2>&1
    python3 - "$test_log" <<'PY'
import collections, pathlib, re, sys
log = pathlib.Path(sys.argv[1]).read_text()
statuses = collections.Counter(re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', log, re.M))
summary = re.search(r'OK \((\d+) tests\)', log)
if not summary or statuses['-1'] or statuses['-2']:
    print('\n'.join(log.splitlines()[-120:]), file=sys.stderr)
    raise SystemExit('Android regressions failed; see ' + sys.argv[1])
if int(summary[1]) != statuses['0'] + statuses['-4']:
    raise SystemExit('Incomplete instrumentation results; see ' + sys.argv[1])
print(f"Android: {statuses['0']} passed, {statuses['-4']} skipped; full log: {sys.argv[1]}")
PY
fi
