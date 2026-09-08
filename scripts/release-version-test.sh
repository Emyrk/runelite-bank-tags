#!/usr/bin/env bash
# Tests for scripts/release-version.sh — run in CI by the `version` job of
# .github/workflows/release.yml. Run locally with:
#   bash scripts/release-version-test.sh
#
# The regression case is the exact commit message of gim-llm e48649c (PR #14),
# the project this script was copied from: its
# body documents "#minor/#major/#skip-release overrides", and the old
# whole-message grep made that commit skip its own release. Markers must
# only ever match the subject line.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$script_dir/release-version.sh"

pass=0
fail=0

# check <name> <want_do_release> <want_tag> <latest_tag> <message>
check() {
  local name="$1" want_release="$2" want_tag="$3" latest="$4" msg="$5"
  local out do_release tag
  out="$(printf '%s' "$msg" | bash "$script" "$latest" 2>/dev/null)"
  do_release="$(awk -F= '$1 == "do_release" { print $2 }' <<<"$out")"
  tag="$(awk -F= '$1 == "tag" { print $2 }' <<<"$out")"
  if [ "$do_release" = "$want_release" ] && [ "$tag" = "$want_tag" ]; then
    pass=$((pass + 1))
    echo "ok: $name"
  else
    fail=$((fail + 1))
    echo "FAIL: $name"
    echo "  want do_release=$want_release tag=$want_tag"
    echo "  got  do_release=$do_release tag=$tag"
  fi
}

# --- skip-release / [skip release]: subject match skips; body match must not ---

check "skip-release in subject skips" \
  false v0.0.0-dev v1.2.3 '#skip-release: docs-only change'

check "SKIP-RELEASE is case-insensitive in subject" \
  false v0.0.0-dev v1.2.3 'Chore #SKIP-RELEASE sync docs'

check "[skip release] alias in subject skips" \
  false v0.0.0-dev v1.2.3 'chore: rerun ci [skip release]'

check "[SKIP RELEASE] alias is case-insensitive" \
  false v0.0.0-dev v1.2.3 'chore: rerun ci [SKIP RELEASE]'

check "skip-release only in body still releases (patch)" \
  true v1.2.4 v1.2.3 'fix: correct the bank totals
This is a docs-only change, maybe #skip-release next time.'

# --- the e48649c regression: exact message, markers only in the body ---

e48649c_msg="$(cat <<'EOF'
gim-llm-13: CI auto-tag and publish a release on every push to main (#14)

Adds a `version` job that computes the next release version — patch
bump off the highest existing vX.Y.Z tag by default, with #minor/#major/
#skip-release overrides read from the commit message — and feeds it into
the plugin build (-Pversion) and the server binaries (-X main.version).
A new `release` job (contents: write, concurrency-guarded) creates and
pushes the annotated tag itself and publishes the release in the same
run, since tags pushed with GITHUB_TOKEN don't trigger a new workflow
run. Manual `vX.Y.Z` tag pushes still publish as-is, no bump.

plugin/build.gradle now derives its version from -Pversion (falling back
to 0.0.0-dev), closing the STATUS.md gap where the jar version wasn't
derived from the git tag. README's Release section documents the new
auto-release behavior instead of manual tag-push instructions.


Claude-Session: https://claude.ai/code/session_012E3mutcyYrLatwxFYEQzLG

Co-authored-by: Dean Masley <dean@kvm22536.prepaid-host.systems>
Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"

check "e48649c exact message releases v0.1.2 (marker only in body)" \
  true v0.1.2 v0.1.1 "$e48649c_msg"

check "e48649c subject alone also releases v0.1.2" \
  true v0.1.2 v0.1.1 "${e48649c_msg%%$'\n'*}"

# --- #minor / #major math ---

check "#minor in subject bumps minor, resets patch" \
  true v1.3.0 v1.2.3 'feat: new panel (#minor)'

check "#major in subject bumps major, resets minor+patch" \
  true v2.0.0 v1.2.3 'feat!: rewrite everything (#major)'

check "no marker bumps patch" \
  true v1.2.4 v1.2.3 'fix: correct the bank totals'

check "#minor only in body still bumps patch" \
  true v1.2.4 v1.2.3 'fix: correct the bank totals
This is quite a big one, worth #minor or even #major.'

check "#Major wrong case is inert (markers are case-sensitive)" \
  true v1.2.4 v1.2.3 'fix: correct the #Major bank totals'

check "skip-release wins over #minor" \
  false v0.0.0-dev v1.2.3 'chore: docs #minor #skip-release'

# --- latest-tag normalization / arg interface ---

check "empty latest tag starts from v0.0.0" \
  true v0.0.1 '' 'first release'

check "v0.0.0 latest tag passed explicitly" \
  true v0.0.1 v0.0.0 'first release'

out="$("$script" v1.2.3 'feat: arg mode (#minor)' 2>/dev/null)"
if [ "$(awk -F= '$1 == "tag" { print $2 }' <<<"$out")" = "v1.3.0" ]; then
  pass=$((pass + 1)); echo "ok: positional-arg invocation"
else
  fail=$((fail + 1)); echo "FAIL: positional-arg invocation (got: $out)"
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
