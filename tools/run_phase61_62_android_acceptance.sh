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

adb shell am instrument -w -r   -e class com.rpgos.app.Phase61To62ActivityContractDeviceSmokeTest   "$runner" \
  | tee app/build/r1-android-evidence/activity-contract.txt

adb shell am instrument -w -r   -e class 'com.rpgos.app.Phase62NpcTravelProcessDeathDeviceTest#seedPendingTravel'   "$runner" \
  | tee app/build/r1-android-evidence/process-death-seed.txt

# Host-side process death: the next instrumentation invocation is a fresh process and must recover
# the pending checkpoint without changing canonical location or resources.
adb shell am force-stop com.rpgos.app
sleep 1
if adb shell pidof com.rpgos.app | grep -q '[0-9]'; then
  echo "target process survived force-stop" | tee app/build/r1-android-evidence/process-death-host-failure.txt
  exit 1
fi
echo "force-stop confirmed" | tee app/build/r1-android-evidence/process-death-host.txt

adb shell am instrument -w -r   -e class 'com.rpgos.app.Phase62NpcTravelProcessDeathDeviceTest#resumePendingTravelAfterProcessDeath'   "$runner" \
  | tee app/build/r1-android-evidence/process-death-resume.txt
