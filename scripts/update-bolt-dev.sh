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

if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
	./gradlew clean shadowJar
elif command -v nix >/dev/null 2>&1; then
	nix shell nixpkgs#jdk21_headless --command ./gradlew clean shadowJar
else
	echo "A JDK or Nix is required to build the development JAR." >&2
	exit 1
fi

mapfile -t built_jars < <(find build/libs -maxdepth 1 -type f -name '*-all.jar' -print)
if [[ ${#built_jars[@]} -ne 1 ]]; then
	echo "Expected exactly one build/libs/*-all.jar, found ${#built_jars[@]}." >&2
	exit 1
fi

mkdir -p -- "${destination_dir}"
install -m 0644 -- "${built_jars[0]}" "${destination_jar}"

echo "Updated Bolt development plugin:"
echo "  ${destination_jar}"
echo "Restart RuneLite through Bolt to load the new build."
