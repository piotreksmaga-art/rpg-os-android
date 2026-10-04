#!/usr/bin/env bash
set -euo pipefail

# Separate test identity also works on a phone with an existing signed lab APK.
# Never clear/uninstall the developer's original campaign storage.
package='com.rpgos.app.phase64acceptance'
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
run_uid="${ACCEPTANCE_SHA:-local_$(date +%s)}"
[[ "$run_uid" =~ ^[A-Za-z0-9_-]{1,64}$ ]]
evidence='app/build/phase64-android-evidence'
mkdir -p "$evidence"
gradle --no-daemon :app:assembleLabDebug :app:assembleLabDebugAndroidTest \
  -PrpgosEmulatorX86=true -PrpgosDeviceAcceptance=true \
  -PrpgosDeviceAcceptanceSuffix=.phase64acceptance
adb install -r app/build/outputs/apk/labDebug/app-labDebug.apk
adb install -r app/build/outputs/apk/androidTest/labDebug/app-labDebug-androidTest.apk

run_test() {
  local test_class="$1" result_file="$2"
  adb shell am instrument -w -r -e phase64RunUid "$run_uid" -e class "$test_class" "$runner" | tee "$result_file"
  grep -Eq '^OK \([1-9][0-9]* tests?\)' "$result_file"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "$result_file"; then
    return 1
  fi
}

run_test 'com.rpgos.app.Phase64BackgroundAcceptanceTest,com.rpgos.app.Phase64DeliveryAcceptanceTest,com.rpgos.app.Phase64InstitutionPopulationAcceptanceTest,com.rpgos.app.Phase64CombatDeviceAcceptanceTest,com.rpgos.app.Phase64ChatApplicationAcceptanceTest' "$evidence/domain-contracts.txt"
# Same legal-route fixture as the unchanged 100-turn gate, only a short round trip here.
run_test 'com.rpgos.app.Phase55To59HundredTurnAcceptanceTest#knownRouteRoundTripReopenUndoAndDifferentDestination' "$evidence/known-route-round-trip.txt"
run_test 'com.rpgos.app.Phase64ProcessDeathDeviceTest#seedPendingProcess' "$evidence/process-death-seed.txt"
adb shell am force-stop "$package"
stopped=false
for attempt in {1..50}; do
  target_pid="$(adb shell "pidof $package; exit 0" | tr -d '\r')"
  if [[ -z "$target_pid" ]]; then stopped=true; break; fi
  sleep 0.1
done
[[ "$stopped" == true ]]
echo 'force-stop confirmed' | tee "$evidence/process-death-host.txt"
run_test 'com.rpgos.app.Phase64ProcessDeathDeviceTest#resumePendingProcessAfterProcessDeath' "$evidence/process-death-resume.txt"
