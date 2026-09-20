set -euo pipefail

asset=$(jq -r .asset dist/package.json)
expected=$(sha256sum "dist/$asset" | cut -d' ' -f1)
jq -r '.sections[] | "## " + .title + "\n\n" + (.items | map("- " + .) | join("\n")) + "\n"' dist/release-notes.json > dist/release-notes.md
if gh release view "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --json isDraft > dist/release-state.json 2>/dev/null; then
  if [ "$(jq -r .isDraft dist/release-state.json)" = true ]; then
    gh release upload "$RELEASE_TAG" "dist/$asset" "dist/$asset.sha256" --repo "$GITHUB_REPOSITORY" --clobber
  else
    mkdir -p "$RUNNER_TEMP/existing-release"
    gh release download "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --pattern "$asset" --dir "$RUNNER_TEMP/existing-release"
    cmp "dist/$asset" "$RUNNER_TEMP/existing-release/$asset"
  fi
else
  gh release create "$RELEASE_TAG" "dist/$asset" "dist/$asset.sha256" \
    --repo "$GITHUB_REPOSITORY" --verify-tag --draft --title "落弦律 $RELEASE_TAG" --notes-file dist/release-notes.md
fi
gh release edit "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --notes-file dist/release-notes.md --draft=false --latest
gh api "repos/$GITHUB_REPOSITORY/releases/tags/$RELEASE_TAG" > dist/github-release.json
github_asset=$(jq -ce --arg asset "$asset" '.assets[] | select(.name == $asset)' dist/github-release.json)
test "$(jq -r .digest <<< "$github_asset")" = "sha256:$expected"
jq --argjson asset "$github_asset" \
  '.channels.github = {url:$asset.browser_download_url, assetId:$asset.id, sha256:.apkSha256, size:.apkSize}' \
  dist/stable.json > dist/stable.next.json
mv dist/stable.next.json dist/stable.json
