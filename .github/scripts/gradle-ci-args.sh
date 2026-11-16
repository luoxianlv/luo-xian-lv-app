# 根据实际 Runner 资源限制并行，避免 Kotlin 与 R8 同时争抢内存。
configure_ci_gradle() {
  local cores memory
  cores=$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf '2')
  memory=$(awk '/MemTotal:/ {print int($2 / 1024)}' /proc/meminfo 2>/dev/null || printf '0')
  [[ "$cores" =~ ^[1-9][0-9]*$ ]] || cores=2
  [[ "$memory" =~ ^[0-9]+$ ]] || memory=0
  local workers=$cores heap=4096
  (( workers <= 4 )) || workers=4
  if (( memory >= 12000 )); then heap=6144; fi
  if (( memory > 0 && memory < 6000 )); then heap=2048; workers=2; fi
  ci_gradle_args=(--build-cache --parallel --configure-on-demand "--max-workers=$workers"
    "-Dorg.gradle.jvmargs=-Xmx${heap}m -Dfile.encoding=UTF-8" --no-daemon --console=plain)
  printf 'Gradle：%s 个并行任务，堆上限 %s MiB\n' "$workers" "$heap"
}
