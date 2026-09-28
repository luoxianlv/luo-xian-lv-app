set -euo pipefail

for name in ANDROID_KEYSTORE_BASE64 ORG_GRADLE_PROJECT_releaseStorePassword ORG_GRADLE_PROJECT_releaseKeyAlias ORG_GRADLE_PROJECT_releaseKeyPassword ANDROID_SIGNING_CERT_SHA256; do
  test -n "${!name:-}" || { printf '缺少签名配置：%s\n' "$name" >&2; exit 1; }
done

key="$RUNNER_TEMP/release.jks"
trap 'rm -f "$key"' EXIT
umask 077
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$key"
export ORG_GRADLE_PROJECT_releaseStoreFile="$key"
build_args=()
if [ -n "${UPDATE_BASE_URL:-}" ]; then
  build_args+=("-PupdateBaseUrl=$UPDATE_BASE_URL")
fi
bash ./gradlew testDebugUnitTest assembleRelease --no-daemon "${build_args[@]}"

apk=app/build/outputs/apk/release/app-release.apk
apksigner=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -1)
aapt=$(find "$ANDROID_HOME/build-tools" -name aapt -type f | sort -V | tail -1)
signature=$("$apksigner" verify --verbose --print-certs "$apk")
certificate=$(sed -nE 's/.*certificate SHA-256 digest[^:]*: ([0-9a-f]+).*/\1/p' <<< "$signature" | head -1)
if [ "$certificate" != "$ANDROID_SIGNING_CERT_SHA256" ]; then
  printf 'Signing certificate mismatch: actual=%s expected=%s\n' "$certificate" "$ANDROID_SIGNING_CERT_SHA256" >&2
  printf '%s\n' "$signature" >&2
  exit 1
fi
test -s app/build/outputs/mapping/release/mapping.txt || { echo 'Missing R8 mapping' >&2; exit 1; }
badging=$("$aapt" dump badging "$apk")
code=$(sed -nE "s/^package: .*versionCode='([0-9]+)'.*/\1/p" <<< "$badging")
version=$(sed -nE "s/^package: .*versionName='([^']+)'.*/\1/p" <<< "$badging")
printf 'APK version: %s (%s); release tag: %s\n' "$version" "$code" "$RELEASE_TAG"
[[ "$code" =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid APK versionCode' >&2; exit 1; }
test "v$version" = "$RELEASE_TAG" || { echo 'APK version does not match release tag' >&2; exit 1; }

mkdir -p dist
asset="luoxianlv-${RELEASE_TAG}-release.apk"
cp "$apk" "dist/$asset"
(cd dist && sha256sum "$asset" > "$asset.sha256")
jq -n --arg name "$version" --argjson code "$code" --arg asset "$asset" \
  '{versionName:$name, versionCode:$code, asset:$asset}' > dist/package.json
notes_file=".github/release-notes/${version}.json"
test -s "$notes_file" || { echo "Missing release notes: $notes_file" >&2; exit 1; }
jq -e '.sections | type == "array"' "$notes_file" > /dev/null || { echo 'Invalid release notes format' >&2; exit 1; }
cp "$notes_file" dist/release-notes.json
printf 'Packaged %s (versionCode %s)\n' "$RELEASE_TAG" "$code" >> "$GITHUB_STEP_SUMMARY"
