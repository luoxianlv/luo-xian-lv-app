# 只允许明确布尔值，默认发布仍执行测试。
select_release_tasks() {
  local native="$1" skip="$2"
  [[ "$native" == true || "$native" == false ]] || { echo "Invalid native package switch" >&2; return 2; }
  [[ "$skip" == true || "$skip" == false ]] || { echo "Invalid test skip switch" >&2; return 2; }
  release_tasks=()
  if [[ "$native" == true ]]; then
    if [[ "$skip" == false ]]; then
      release_tasks+=(:buildSrc:test :hot-core:testDebugUnitTest :app:testDebugUnitTest)
      for module in app-runtime input-core update-core; do
        if [[ -d "modules/$module" || -d "$module" ]]; then
          release_tasks+=(":$module:testDebugUnitTest")
        fi
      done
    fi
    release_tasks+=(:app-host:exportReleaseNativeBuildReport)
  else
    if [[ "$skip" == false ]]; then release_tasks+=(testDebugUnitTest); fi
    release_tasks+=(assembleRelease)
  fi
}
