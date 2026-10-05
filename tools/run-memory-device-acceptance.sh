#!/usr/bin/env bash
set -euo pipefail

# Only this test's non-sensitive stage/counter tag is streamed. It shows whether
# a slow run is in a turn, replay/Undo, or teardown, without exposing AI payloads.
adb logcat -v brief -T 1 -s RPGOS100Acceptance:I '*:S' &
acceptance_log_pid=$!
trap 'kill "$acceptance_log_pid" 2>/dev/null || true' EXIT

# Keep the original suites, three tests, full 100-turn scenario and failure exit.
# No timeout extension, new filtering, ignored failures or substitute fixture.
gradle --no-daemon :app:connectedDebugAndroidTest \
  -PrpgosEmulatorX86=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rpgos.app.Phase55To59DeviceAcceptanceTest,com.rpgos.app.Phase55To59HundredTurnAcceptanceTest \
  --stacktrace
