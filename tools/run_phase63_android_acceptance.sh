#!/usr/bin/env bash
set -euo pipefail

# One APK/test build per exact SHA. All scenarios call the real Core/application ports;
# no model host, generative 100-turn run or direct canonical fixture mutation is started.
mkdir -p app/build/phase63-android-evidence
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PrpgosEmulatorX86=true --stacktrace

app_apk="$(find app/build/outputs/apk/debug -name '*-debug.apk' -type f | head -n 1)"
test_apk="$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -type f | head -n 1)"
test -n "$app_apk" && test -n "$test_apk"
adb install -r "$app_apk"
adb install -r "$test_apk"
runner="$(adb shell pm list instrumentation | sed -n 's/^instrumentation:\([^ ]*\) (target=com.rpgos.app)$/\1/p' | head -n 1 | tr -d '\r')"
test -n "$runner"

run_test() {
  local test_class="$1" evidence="$2"
  adb shell am instrument -w -r -e class "$test_class" "$runner" | tee "$evidence"
  grep -Eq '^OK \([1-9][0-9]* tests?\)' "$evidence"
  ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "$evidence"
}

run_test 'com.rpgos.app.Phase63WorldDeviceTest,com.rpgos.app.Phase61To62ActivityContractDeviceSmokeTest,com.rpgos.app.Phase62NpcTravelCompletionDeviceTest,com.rpgos.app.Phase62NpcDomainsDeviceTest,com.rpgos.app.Phase62NpcAuthorityDeviceTest,com.rpgos.app.Phase62NpcWitnessDeviceTest,com.rpgos.app.Phase55To59DeviceAcceptanceTest' \
  app/build/phase63-android-evidence/shared-contracts.txt

run_test 'com.rpgos.app.Phase62NpcTravelProcessDeathDeviceTest#seedPendingTravel' \
  app/build/phase63-android-evidence/process-death-seed.txt
adb shell am force-stop com.rpgos.app
# API28 can report the dying process immediately after ActivityManager returns.
# Require actual disappearance before the resume test; transport errors must not
# be mistaken for a stopped process. This is a bounded wait, not a skipped check.
target_stopped=false
for attempt in {1..50}; do
  target_pid="$(adb shell 'pidof com.rpgos.app; exit 0' | tr -d '\r')"
  if [[ -z "$target_pid" ]]; then
    target_stopped=true
    break
  fi
  sleep 0.1
done
if [[ "$target_stopped" != true ]]; then
  echo "target process survived force-stop: $target_pid" >&2
  exit 1
fi
echo 'force-stop confirmed' | tee app/build/phase63-android-evidence/process-death-host.txt
run_test 'com.rpgos.app.Phase62NpcTravelProcessDeathDeviceTest#resumePendingTravelAfterProcessDeath' \
  app/build/phase63-android-evidence/process-death-resume.txt
