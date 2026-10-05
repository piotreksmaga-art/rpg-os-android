#!/usr/bin/env bash
set -euo pipefail

# Partition the original three cases; both partitions are mandatory in CI.
case "${1:-all}" in
  all) acceptance_classes='com.rpgos.app.Phase55To59DeviceAcceptanceTest,com.rpgos.app.Phase55To59HundredTurnAcceptanceTest'; expected_tests=3 ;;
  contracts) acceptance_classes='com.rpgos.app.Phase55To59DeviceAcceptanceTest,com.rpgos.app.Phase55To59HundredTurnAcceptanceTest#knownRouteRoundTripReopenUndoAndDifferentDestination'; expected_tests=2 ;;
  hundred) acceptance_classes='com.rpgos.app.Phase55To59HundredTurnAcceptanceTest#hundredTurnSaveReopenUndoAndAlternateFuture'; expected_tests=1 ;;
  *) printf 'Unknown acceptance partition\n' >&2; exit 2 ;;
esac

# Only this test's non-sensitive stage/counter tag is streamed. It shows whether
# a slow run is in a turn, replay/Undo, or teardown, without exposing AI payloads.
adb logcat -v brief -T 1 -s RPGOS100Acceptance:I '*:S' &
acceptance_log_pid=$!
trap 'kill "$acceptance_log_pid" 2>/dev/null || true' EXIT

# Install the exact-SHA, checksum-verified artifacts directly: Gradle's connected
# task otherwise recompiles even when its APK packaging tasks are excluded.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
evidence_dir='app/build/outputs/androidTest-results/memory-acceptance'
mkdir -p "$evidence_dir"
result="$evidence_dir/${1:-all}.txt"
adb shell am instrument -w -r -e class "$acceptance_classes" \
  com.rpgos.app.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$result"

# ActivityManager can return exit 0 after a failed/crashed JUnit run. Require
# exactly the selected number of successful cases; skips and missing cases fail.
grep -Eq "^OK \($expected_tests tests?\)$" "$result"
test "$(grep -Ec '^INSTRUMENTATION_STATUS_CODE: 0$' "$result")" -eq "$expected_tests"
grep -Eq '^INSTRUMENTATION_CODE: -1$' "$result"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|^INSTRUMENTATION_STATUS_CODE: -[1-4]$' "$result"
