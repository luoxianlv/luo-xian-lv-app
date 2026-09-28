set -euo pipefail

# 官方固定版本与公开 SHA-256；不读取或写入凭据配置。
directory="$RUNNER_TEMP/ossutil-2.4.0"
mkdir -p "$directory"
curl --fail --silent --show-error --connect-timeout 10 --max-time 120 --retry 2 \
  https://gosspublic.alicdn.com/ossutil/v2/2.4.0/ossutil-2.4.0-linux-amd64.zip \
  -o "$directory/package.zip"
printf '%s  %s\n' 85edf66b2fb7238f5c7e25cab820cf29312319fe4935b7c86a6b8485eb434f3c "$directory/package.zip" | sha256sum --check
unzip -q -o "$directory/package.zip" -d "$directory"
binary="$directory/ossutil-2.4.0-linux-amd64/ossutil"
chmod 755 "$binary"
"$binary" version
printf '%s\n' "$(dirname "$binary")" >> "$GITHUB_PATH"
