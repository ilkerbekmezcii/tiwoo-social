#!/usr/bin/env bash
set -euo pipefail

APK="ILET/app/build/outputs/apk/debug/app-debug.apk"

adb install -r "$APK"
adb shell pm grant com.yakintalk.app android.permission.RECORD_AUDIO || true
adb shell pm grant com.yakintalk.app android.permission.POST_NOTIFICATIONS || true
adb shell pm grant com.yakintalk.app android.permission.NEARBY_WIFI_DEVICES || true

adb logcat -c
adb shell am force-stop com.yakintalk.app
adb shell am start -W -n com.yakintalk.app/.MainActivity
sleep 8

adb wait-for-device
for i in 1 2 3 4 5; do
  if adb get-state >/dev/null 2>&1; then break; fi
  sleep 2
done

PID="$(adb shell pidof com.yakintalk.app 2>/dev/null | tr -d '\r' || true)"
for i in 1 2 3; do
  if adb logcat -d > /tmp/ilet-logcat.txt 2>/dev/null; then break; fi
  adb wait-for-device || true
  sleep 2
done
test -s /tmp/ilet-logcat.txt

if [[ -z "$PID" ]]; then
  echo "FAIL: ILET process exited after launch"
  grep -E -A40 -B10 "FATAL EXCEPTION|Process: com\.yakintalk\.app|AndroidRuntime|SecurityException|RuntimeException|IllegalStateException|UnsupportedOperationException" /tmp/ilet-logcat.txt || tail -n 500 /tmp/ilet-logcat.txt
  exit 1
fi
echo "PASS: ILET process alive after launch: $PID"

if grep -E "FATAL EXCEPTION|Process: com\.yakintalk\.app.*has died|AndroidRuntime.*com\.yakintalk\.app" /tmp/ilet-logcat.txt; then
  echo "FAIL: fatal runtime exception detected"
  grep -E -A40 -B10 "FATAL EXCEPTION|Process: com\.yakintalk\.app|AndroidRuntime" /tmp/ilet-logcat.txt
  exit 1
fi
echo "PASS: no fatal runtime exception"

adb shell dumpsys package com.yakintalk.app > /tmp/ilet-package.txt
if grep -q "com.yakintalk.app.IletService" /tmp/ilet-package.txt; then
  echo "FAIL: IletService is still declared in installed manifest"
  exit 1
fi
echo "PASS: IletService absent from installed manifest"

adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell dumpsys activity services com.yakintalk.app > /tmp/ilet-services.txt
if grep -q "ServiceRecord" /tmp/ilet-services.txt; then
  echo "FAIL: background service remained after HOME"
  cat /tmp/ilet-services.txt
  exit 1
fi
echo "PASS: no background service after HOME"

adb shell am start -W -n com.yakintalk.app/.MainActivity
sleep 3
PID2="$(adb shell pidof com.yakintalk.app 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$PID2" ]]; then
  echo "FAIL: ILET did not survive reopen"
  adb logcat -d
  exit 1
fi
echo "PASS: ILET reopened successfully: $PID2"
