#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source_dir="${repo_root}/vendor/inventory-setups"
generated_dir=${1:-"${repo_root}/build/generated/inventory-setups"}
expected_commit=6f9678715ef3f4c5293480e3926a51c8418e4c44
patch_dir="${repo_root}/patches/inventory-setups"

if [[ ! -e "${source_dir}/.git" ]]; then
	echo "Inventory Setups submodule is missing. Run: git submodule update --init --recursive" >&2
	exit 1
fi
actual_commit=$(git -C "${source_dir}" rev-parse HEAD)
if [[ "${actual_commit}" != "${expected_commit}" ]]; then
	echo "Inventory Setups submodule is at ${actual_commit}, expected ${expected_commit}." >&2
	exit 1
fi
if [[ -n $(git -C "${source_dir}" status --porcelain) ]]; then
	echo "Inventory Setups submodule has local changes; refusing to build from a dirty vendor tree." >&2
	exit 1
fi

rm -rf -- "${generated_dir}"
mkdir -p -- "${generated_dir}"
cp -a -- "${source_dir}/src/main/java" "${generated_dir}/java"
find "${generated_dir}/java" -type f -name '*.java' -exec sed -i -e 's/\r$//' -e 's/[[:space:]]*$//' -e 's/^ \t/\t/' {} +
cp -a -- "${source_dir}/src/main/resources" "${generated_dir}/resources"
printf 'version=v1.25.0-bundled-%s\n' "${expected_commit:0:7}" > "${generated_dir}/resources/invsetups_version.txt"

for patch in "${patch_dir}"/*.patch; do
	patch -d "${generated_dir}/java" -p1 --forward --batch < "${patch}"
done

if grep -Rqs 'net\.runelite\.client\.plugins\.banktags' "${generated_dir}/java"; then
	echo "Generated Inventory Setups sources still reference built-in Bank Tags." >&2
	exit 1
fi
