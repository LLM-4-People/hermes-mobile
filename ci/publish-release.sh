#!/usr/bin/env bash
# Publish a complete, digest-verified draft; retry missing uploads after interruption.
set -euo pipefail
: "${GITHUB_REPOSITORY:?}" "${GITHUB_SHA:?}"
cd "${1:?Expected release asset directory}"
tag=$(jq -er '.tag' release.json)
[[ $tag =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]
api="repos/$GITHUB_REPOSITORY"
ghr=(gh release --repo "$GITHUB_REPOSITORY")

validate_ref() {
  local current refs tag_sha
  current=$(gh api "$api/branches/main" --jq '.commit.sha')
  if [[ $current != "$GITHUB_SHA" ]]; then
    echo "The reviewed main revision has changed. Dispatch from current main." >&2
    return 1
  fi
  refs=$(gh api "$api/git/matching-refs/tags/$tag")
  if jq -e --arg ref "refs/tags/$tag" 'any(.[]; .ref == $ref)' <<< "$refs" >/dev/null; then
    tag_sha=$(gh api "$api/commits/tags/$tag" --jq '.sha')
    if [[ $tag_sha != "$GITHUB_SHA" ]]; then
      echo "The release tag already identifies another commit." >&2
      return 1
    fi
  fi
}

validate_ref
releases=$(gh api --paginate --slurp "$api/releases?per_page=100")
release=$(jq -ce --arg tag "$tag" '[.[][] | select(.tag_name == $tag)]' <<< "$releases")
if [[ $release == '[]' ]]; then
  flags=()
  if [[ $tag == *-* ]]; then flags+=(--prerelease); fi
  "${ghr[@]}" create "$tag" --draft --target "$GITHUB_SHA" --title "Hermes Mobile $tag" \
    --generate-notes "${flags[@]}"
elif ! jq -e --arg sha "$GITHUB_SHA" 'length == 1 and .[0].target_commitish == $sha' <<< "$release" >/dev/null; then
  echo "The existing release does not identify this exact source commit." >&2
  exit 1
fi
state=$("${ghr[@]}" view "$tag" --json isDraft,assets)
for file in Hermes-Mobile.apk SHA256SUMS release.json; do
  expected="sha256:$(sha256sum "$file" | cut -d ' ' -f 1)"
  remote=$(jq -r --arg name "$file" '.assets[] | select(.name == $name) | .digest' <<< "$state")
  if [[ -z $remote ]] && jq -e '.isDraft' <<< "$state" >/dev/null; then
    "${ghr[@]}" upload "$tag" "$file"
    remote=$("${ghr[@]}" view "$tag" --json assets | jq -er --arg name "$file" '.assets[] | select(.name == $name) | .digest')
  fi
  if [[ $remote != "$expected" ]]; then
    echo "Release asset $file differs from the verified local bytes." >&2
    exit 1
  fi
done
if jq -e '.isDraft' <<< "$state" >/dev/null; then
  validate_ref
  "${ghr[@]}" edit "$tag" --draft=false
fi
