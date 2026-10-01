#!/bin/sh
#
# Apply the Android patch series to the pinned upstream submodules.
#
# Each series is kept as ordered patch files rather than a fork so every commit
# stays individually submittable upstream. Re-running is safe: each submodule is
# reset to its pinned commit first.
#
# Usage: apply-patches.sh [submodule...]   (default: every series in patches/)
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

apply_series() {
	name="$1"
	sub="$ROOT/third_party/$name"
	series="$ROOT/patches/$name"

	[ -e "$sub/.git" ] || { echo "submodule not initialised: git submodule update --init third_party/$name" >&2; exit 1; }
	[ -d "$series" ] || { echo "no patch series at patches/$name" >&2; exit 1; }

	pinned="$(git -C "$ROOT" ls-files -s "third_party/$name" | awk '{print $2}')"
	[ -n "$pinned" ] || { echo "cannot determine pinned commit for third_party/$name" >&2; exit 1; }

	(
		cd "$sub"
		git checkout -q .
		git clean -qfd
		# Drop any previously applied series so this is idempotent.
		git reset -q --hard "$pinned"
		git -c user.email=build@localhost -c user.name=build am -q --keep-cr "$series"/*.patch
		n="$(ls "$series"/*.patch | wc -l)"
		echo "applied $n patches to third_party/$name"
		git --no-pager log --oneline -"$n"
	)
}

if [ $# -eq 0 ]; then
	for d in "$ROOT"/patches/*/; do
		apply_series "$(basename "$d")"
	done
else
	for name in "$@"; do
		apply_series "$name"
	done
fi
