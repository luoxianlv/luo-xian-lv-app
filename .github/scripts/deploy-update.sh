set -euo pipefail

python3 .github/scripts/validate_manifest.py dist/stable.json

key="$RUNNER_TEMP/deploy_key"
known_hosts="$RUNNER_TEMP/deploy_known_hosts"
trap 'rm -f "$key" "$known_hosts"' EXIT
umask 077
printf '%s\n' "$SERVER_SSH_KEY" | tr -d '\r' > "$key"
printf '%s\n' "$SERVER_KNOWN_HOSTS" > "$known_hosts"
ssh_args=(-i "$key" -o "UserKnownHostsFile=$known_hosts" -o StrictHostKeyChecking=yes -o BatchMode=yes -o ConnectTimeout=15)
remote="/app/public/updates/.stable-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}.json"
scp -q -P "$SERVER_PORT" "${ssh_args[@]}" dist/stable.json "root@$SERVER_HOST:$remote"
ssh -p "$SERVER_PORT" "${ssh_args[@]}" "root@$SERVER_HOST" "python3 - '$remote'" < .github/scripts/deploy_manifest.py
printf 'GitHub and OSS published; stable manifest updated.\n' >> "$GITHUB_STEP_SUMMARY"
