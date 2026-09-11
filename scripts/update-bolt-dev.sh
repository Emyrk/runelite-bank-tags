#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
bolt_data_dir=${BOLT_DATA_DIR:-"${HOME}/.var/app/com.adamcake.Bolt/data/bolt-launcher"}
destination_dir="${bolt_data_dir}/dev-jars"
destination_jar="${destination_dir}/bank-tags-extended-dev.jar"

if pgrep -f -- "${destination_jar}" >/dev/null 2>&1; then
	echo "Close the Bank Tags Extended development RuneLite client before updating." >&2
	exit 1
fi

cd -- "${repo_root}"
git submodule update --init --recursive

build_args=(clean test shadowJar --no-daemon)
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
	./gradlew "${build_args[@]}"
elif command -v nix-shell >/dev/null 2>&1; then
	nix-shell -E 'with import <nixpkgs> {}; mkShell {
		packages = [ jdk11 ];
		LD_LIBRARY_PATH = lib.makeLibraryPath [
			xorg.libXrender xorg.libXtst xorg.libXi xorg.libXext xorg.libX11
		];
	}' --run './gradlew clean test shadowJar --no-daemon'
else
	echo "A JDK or nix-shell is required to build the development JAR." >&2
	exit 1
fi

mapfile -t built_jars < <(find build/libs -maxdepth 1 -type f -name '*-all.jar' -print)
if [[ ${#built_jars[@]} -ne 1 ]]; then
	echo "Expected exactly one build/libs/*-all.jar, found ${#built_jars[@]}." >&2
	exit 1
fi

validate_jar()
{
	local jar=$1
	local entries
	entries=$(unzip -Z1 "${jar}")
	for required in \
		com/emyrk/banktags/BankTagsPlugin.class \
		inventorysetups/InventorySetupsPlugin.class \
		runelite-plugin.properties \
		invsetups_version.txt \
		META-INF/licenses/inventory-setups-BSD-2-Clause.txt
	do
		if ! grep -Fqx -- "${required}" <<<"${entries}"; then
			echo "Combined JAR is missing ${required}." >&2
			exit 1
		fi
	done
}

validate_jar "${built_jars[0]}"
mkdir -p -- "${destination_dir}"
temporary_jar=$(mktemp "${destination_dir}/.bank-tags-extended-dev.XXXXXX.jar")
trap 'rm -f -- "${temporary_jar}"' EXIT
install -m 0644 -- "${built_jars[0]}" "${temporary_jar}"
validate_jar "${temporary_jar}"
mv -f -- "${temporary_jar}" "${destination_jar}"
trap - EXIT

echo "Updated Bolt development plugin:"
echo "  ${destination_jar}"
echo "The JAR contains Bank Tags Extended and its bundled Inventory Setups companion."
echo "Fully restart RuneLite through Bolt to load the new build."
