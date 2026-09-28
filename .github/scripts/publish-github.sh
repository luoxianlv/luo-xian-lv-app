set -euo pipefail

asset=$(jq -r .asset dist/package.json)
expected=$(sha256sum "dist/$asset" | cut -d' ' -f1)
jq -r '.sections[] | "## " + .title + "\n\n" + (.items | map("- " + .) | join("\n")) + "\n"' dist/release-notes.json > dist/release-notes.md
if gh release view "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --json isDraft > dist/release-state.json 2>/dev/null; then
  if [ "$(jq -r .isDraft dist/release-state.json)" = true ]; then
    gh release upload "$RELEASE_TAG" "dist/$asset" "dist/$asset.sha256" dist/package.json dist/release-notes.json --repo "$GITHUB_REPOSITORY" --clobber
  else
    mkdir -p "$RUNNER_TEMP/existing-release"
    gh release download "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --pattern "$asset" --dir "$RUNNER_TEMP/existing-release"
    cmp "dist/$asset" "$RUNNER_TEMP/existing-release/$asset"
    # 已公开的同版本文件不可替换；重跑只接受完全一致的发布资料。
    for file in package.json release-notes.json "$asset.sha256"; do
      gh release download "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --pattern "$file" --dir "$RUNNER_TEMP/existing-release"
      cmp "dist/$file" "$RUNNER_TEMP/existing-release/$file"
    done
  fi
else
  gh release create "$RELEASE_TAG" "dist/$asset" "dist/$asset.sha256" dist/package.json dist/release-notes.json \
    --repo "$GITHUB_REPOSITORY" --verify-tag --draft --title "落弦律 $RELEASE_TAG" --notes-file dist/release-notes.md
fi
gh release edit "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --notes-file dist/release-notes.md --draft=false --latest
gh api "repos/$GITHUB_REPOSITORY/releases/tags/$RELEASE_TAG" > dist/github-release.json
github_asset=$(jq -ce --arg asset "$asset" '.assets[] | select(.name == $asset)' dist/github-release.json)
test "$(jq -r .digest <<< "$github_asset")" = "sha256:$expected"
printf 'GitHub Release 已发布；OSS 上传与 APP 更新清单部署需分别触发。\n' >> "$GITHUB_STEP_SUMMARY"
