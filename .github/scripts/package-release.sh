set -euo pipefail

native_package="${NATIVE_RELEASE_PACKAGE:-false}"
[[ "$native_package" == true || "$native_package" == false ]] || { echo 'Invalid native package switch' >&2; exit 1; }
if [[ "$native_package" == true ]]; then
  test -f app-host/build.gradle.kts && test -f tools/verify-native-release.ps1 \
    || { echo 'This source tag does not support native packaging' >&2; exit 1; }
  command -v pwsh >/dev/null || { echo 'PowerShell 7 is required for the native artifact audit' >&2; exit 1; }
  test -n "${NATIVE_HOT_CONFIG_JSON:-}" || { echo 'Missing public native hot-update configuration' >&2; exit 1; }
  printf '%s' "$NATIVE_HOT_CONFIG_JSON" | jq -e \
    '.applicationId == "app.luoxianlv" and .environment == "production" and (.origin | startswith("https://"))' >/dev/null \
    || { echo 'Invalid production native hot-update scope' >&2; exit 1; }
fi

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
if [[ "$native_package" == true ]]; then
  hot_config="$RUNNER_TEMP/native-hot-config.json"
  printf '%s' "$NATIVE_HOT_CONFIG_JSON" > "$hot_config"
  build_args+=("-PhotUpdateConfig=$hot_config" '-PnativeOptimize=true' '-PnativeRequireReleaseSigning=true')
  build_args+=('-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8' '--max-workers=2')
  bash ./gradlew :buildSrc:test :hot-core:testDebugUnitTest :app-business:testDebugUnitTest \
    :app-host:exportReleaseNativeBuildReport --no-daemon "${build_args[@]}"
  apk=app-host/build/outputs/apk/release/app-host-release.apk
  pwsh -NoProfile -File tools/verify-native-release.ps1 -ExpectOptimized \
    -HostApk "$apk" -ExpectedCertificateSha256 "$ANDROID_SIGNING_CERT_SHA256"
  mkdir -p native-release-artifacts
  for role in host runtime business; do
    cp -R "app-$role/build/native-report/release" "native-release-artifacts/$role"
  done
  cp app-host/build/native-release-verification.json native-release-artifacts/
  cp hot-contract/build/native-sdk/release/host-contract-sdk.jar native-release-artifacts/
  cp app-runtime/build/native-sdk/release/runtime-sdk.jar native-release-artifacts/
  cp app-runtime/build/native-link/release/runtime.apk native-release-artifacts/runtime/
  cp app-business/build/native-link/release/business.apk native-release-artifacts/business/
else
  bash ./gradlew testDebugUnitTest assembleRelease --no-daemon "${build_args[@]}"
  apk=app/build/outputs/apk/release/app-release.apk
fi
apksigner=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -1)
aapt=$(find "$ANDROID_HOME/build-tools" -name aapt -type f | sort -V | tail -1)
signature=$("$apksigner" verify --verbose --print-certs "$apk")
certificate=$(sed -nE 's/.*certificate SHA-256 digest[^:]*: ([0-9a-f]+).*/\1/p' <<< "$signature" | head -1)
if [ "$certificate" != "$ANDROID_SIGNING_CERT_SHA256" ]; then
  printf 'Signing certificate mismatch: actual=%s expected=%s\n' "$certificate" "$ANDROID_SIGNING_CERT_SHA256" >&2
  printf '%s\n' "$signature" >&2
  exit 1
fi
if [[ "$native_package" == false ]]; then
  test -s app/build/outputs/mapping/release/mapping.txt || { echo 'Missing R8 mapping' >&2; exit 1; }
fi
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
