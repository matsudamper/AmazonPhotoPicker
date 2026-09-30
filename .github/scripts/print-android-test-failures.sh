#!/usr/bin/env bash
# レポートは artifact でしか見られないため、失敗原因をジョブログから追えるよう出力する
set -u
echo "::group::Instrumentation test failures"
find app/build/outputs/androidTest-results -name "*.xml" -print0 2>/dev/null \
  | xargs -0 -r grep -h -A 30 "<failure" || true
echo "::endgroup::"
echo "::group::logcat"
adb logcat -d -v time 2>/dev/null \
  | grep -E "PickerBridge|AmazonPhotoBrowser|GeckoConsole|Gecko|AndroidRuntime|TestRunner|FATAL" \
  | tail -n 400 || true
echo "::endgroup::"
