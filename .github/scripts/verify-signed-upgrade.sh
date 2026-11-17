#!/usr/bin/env bash
# The signed APK stays on this ephemeral runner. Exercise Android's actual update path.
set -euo pipefail
old=$(gh release view --json tagName --jq .tagName)
[[ "$old" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ && "$old" != "v$VERSION" ]]
work=$(mktemp -d "${RUNNER_TEMP:?}/upgrade.XXXXXX")
gh release download "$old" --pattern "luoxianlv-$old-release.apk" --dir "$work"
candidate="modules/app-host/build/outputs/apk/release/app-host-release.apk"
package=app.luoxianlv
activity="$package/app.luoxianlv.MainActivity"
image='system-images;android-30;google_apis;x86_64'
sdkmanager --install 'emulator' 'platform-tools' "$image" >/dev/null
echo no | avdmanager create avd --force --name release-upgrade --package "$image" >/dev/null
sudo chmod a+rw /dev/kvm
adb start-server
"$ANDROID_HOME/emulator/emulator" -avd release-upgrade -port 5554 -no-window -no-audio \
  -no-snapshot -no-boot-anim -gpu swiftshader_indirect -accel on >"$work/emulator.log" 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT
export ANDROID_SERIAL=emulator-5554
timeout 240 adb wait-for-device
for _ in {1..180}; do
  [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != 1 ]] || break
  kill -0 "$emulator_pid"
  sleep 1
done
[[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]
adb shell svc wifi disable
adb shell svc data disable
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
launch() {
  adb shell am start -W -n "$activity" | tee "$work/start.txt"
  grep -Fq 'Status: ok' "$work/start.txt"
  sleep 5
  adb shell pidof "$package" >/dev/null
  adb shell dumpsys activity activities > "$work/activities.txt"
  grep -E '(mResumedActivity|topResumedActivity).*app\.luoxianlv/(\.MainActivity|app\.luoxianlv\.MainActivity)' "$work/activities.txt"
}
adb install "$work/luoxianlv-$old-release.apk"
launch
adb shell dumpsys package "$package" > "$work/before.txt"
grep -Fq "versionName=${old#v}" "$work/before.txt"
old_code=$(sed -nE 's/.*versionCode=([0-9]+).*/\1/p' "$work/before.txt" | head -1)
[[ "$old_code" =~ ^[0-9]+$ && "$CODE" -gt "$old_code" ]]
adb install -r "$candidate"
launch
adb shell dumpsys package "$package" > "$work/after.txt"
grep -Fq "versionName=$VERSION" "$work/after.txt"
grep -Fq "versionCode=$CODE " "$work/after.txt"
for field in userId firstInstallTime; do
  before=$(grep -m1 "$field=" "$work/before.txt")
  [[ "$before" == "$(grep -m1 "$field=" "$work/after.txt")" ]]
done
printf 'Signed in-place upgrade passed: %s (%s) -> %s (%s).\n' "$old" "$old_code" "$VERSION" "$CODE"
