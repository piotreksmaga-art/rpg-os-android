#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/r1-android-evidence
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PrpgosEmulatorX86=true --stacktrace

app_apk="$(find app/build/outputs/apk/debug -name '*-debug.apk' -type f | head -n 1)"
test_apk="$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -type f | head -n 1)"
test -n "$app_apk"
test -n "$test_apk"

adb install -r "$app_apk"
adb install -r "$test_apk"

runner="$(adb shell pm list instrumentation | sed -n 's/^instrumentation:\([^ ]*\) (target=com.rpgos.app)$/\1/p' | head -n 1 | tr -d '\r')"
test -n "$runner"

run_test() {
  local test_class="$1" evidence="$2"
  adb shell am instrument -w -r -e class "$test_class" "$runner" | tee "$evidence"
  # am instrument can return host exit code zero when JUnit failed or the process crashed.
  grep -Eq '^OK \([1-9][0-9]* tests?\)' "$evidence"
  ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "$evidence"
}

run_test 'com.rpgos.app.Phase61To62ActivityContractDeviceSmokeTest,com.rpgos.app.Phase62NpcDomainsDeviceTest,com.rpgos.app.Phase62NpcAuthorityDeviceTest,com.rpgos.app.Phase62NpcWitnessDeviceTest,com.rpgos.app.Phase55To59DeviceAcceptanceTest' \
  app/build/r1-android-evidence/activity-contract.txt

run_test 'com.rpgos.app.Phase62NpcTravelProcessDeathDeviceTest#seedPendingTravel' \
  app/build/r1-android-evidence/process-death-seed.txt

# Host-side process death: the next instrumentation invocation is a fresh process and must recover
# the pending checkpoint without changing canonical location or resources.
adb shell am force-stop com.rpgos.app
sleep 1
if adb shell pidof com.rpgos.app | grep -q '[0-9]'; then
  echo "target process survived force-stop" | tee app/build/r1-android-evidence/process-death-host-failure.txt
  exit 1
fi
echo "force-stop confirmed" | tee app/build/r1-android-evidence/process-death-host.txt

run_test 'com.rpgos.app.Phase62NpcTravelProcessDeathDeviceTest#resumePendingTravelAfterProcessDeath' \
  app/build/r1-android-evidence/process-death-resume.txt
