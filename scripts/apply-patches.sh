#!/bin/sh
#
# Apply the Android patch series to the pinned unetd submodule.
#
# The series is kept as ordered patch files rather than a fork so each commit
# stays individually submittable upstream. Re-running is safe: the submodule is
# reset to its pinned commit first.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUB="$ROOT/third_party/unetd"
SERIES="$ROOT/patches/unetd"

[ -e "$SUB/.git" ] || { echo "submodule not initialised: git submodule update --init" >&2; exit 1; }

cd "$SUB"
git checkout -q .
git clean -qfd
# Drop any previously applied series so this is idempotent.
PINNED="$(git -C "$ROOT" ls-files -s third_party/unetd | awk '{print $2}')"
[ -n "$PINNED" ] || { echo "cannot determine pinned commit for third_party/unetd" >&2; exit 1; }
git reset -q --hard "$PINNED"

git -c user.email=build@localhost -c user.name=build am --keep-cr "$SERIES"/*.patch

echo "applied $(ls "$SERIES"/*.patch | wc -l) patches to third_party/unetd"
git --no-pager log --oneline -"$(ls "$SERIES"/*.patch | wc -l)"
