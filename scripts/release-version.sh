#!/usr/bin/env bash
# release-version.sh — the single place that parses the release-control
# markers (#skip-release / [skip release] / #minor / #major) and computes
# the next vX.Y.Z release tag. Both the `version` and `release` jobs in
# .github/workflows/release.yml call this, so their marker handling cannot
# drift.
#
# Markers are matched against the FIRST LINE of the input only — the commit
# subject, which for a squash merge is the PR title. Squash merges copy the
# whole PR description into the commit body, so a marker merely mentioned in
# the body must stay inert (that bug made gim-llm PR #14 skip its own release).
#
# Usage: release-version.sh [latest-tag] [subject]
#   latest-tag  highest existing vX.Y.Z tag; "" or "v0.0.0" when there is
#               none yet (first-ever release).
#   subject     commit subject (a full commit message also works; only its
#               first line is read). Read from stdin when omitted.
#
# stdout (machine-readable, one key=value line each):
#   do_release=true|false
#   tag=vX.Y.Z        next tag; "v0.0.0-dev" when do_release=false
# stderr: human-readable explanation for the CI log.

set -euo pipefail

latest="${1:-}"
input="${2:-}"
if [ -z "$input" ]; then
  input="$(cat)"
fi

# Subject = first line of the message. Everything after it (the squash PR
# body) is deliberately ignored.
subject="${input%%$'\n'*}"

# Normalize the latest tag: tolerate a missing/empty value, drop the v.
latest="${latest#v}"
if [ -z "$latest" ]; then
  latest="0.0.0"
fi
IFS='.' read -r major minor patch <<< "$latest"

if printf '%s' "$subject" | grep -qiE '#skip-release|\[skip release\]'; then
  echo "Release marker found in the commit subject; no tag or release will be created." >&2
  echo "do_release=false"
  echo "tag=v0.0.0-dev"
  exit 0
fi

if printf '%s' "$subject" | grep -qE '#major'; then
  echo "Found #major in the commit subject; bumping major version." >&2
  major=$((major + 1)); minor=0; patch=0
elif printf '%s' "$subject" | grep -qE '#minor'; then
  echo "Found #minor in the commit subject; bumping minor version." >&2
  minor=$((minor + 1)); patch=0
else
  echo "No version marker in the commit subject; bumping patch version." >&2
  patch=$((patch + 1))
fi

echo "do_release=true"
echo "tag=v${major}.${minor}.${patch}"
