#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
toolchain_dir="${OMNIBOT_TOOLCHAIN_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/omnibot-toolchains}"
if [[ -f "$toolchain_dir/env.sh" ]]; then
    source "$toolchain_dir/env.sh"
fi
jdk17_dir="${OMNIBOT_JDK17:-$toolchain_dir/jdk-17.0.20.1+1}"
jdk21_dir="${OMNIBOT_JDK21:-$toolchain_dir/jdk-21.0.12.1+1}"
libtalloc_file="${OMNIBOT_LIBTALLOC_DEB:-$toolchain_dir/libtalloc_2.4.3_aarch64.deb}"
for jdk_dir in "$jdk17_dir" "$jdk21_dir"; do
    [[ -x "$jdk_dir/bin/java" ]] || { echo "Missing JDK: $jdk_dir" >&2; exit 1; }
done
[[ -f "$libtalloc_file" ]] || { echo "Missing pinned libtalloc package: $libtalloc_file" >&2; exit 1; }
command -v flutter >/dev/null || { echo "Add Flutter 3.47.2 to PATH." >&2; exit 1; }
command -v pnpm >/dev/null || { echo "Add pnpm 10.28.0 to PATH." >&2; exit 1; }
export JAVA_HOME="$jdk21_dir"
export PATH="$JAVA_HOME/bin:$PATH"
export NO_PROXY="${NO_PROXY:+$NO_PROXY,}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+$no_proxy,}localhost,127.0.0.1,::1"
case "${1:-apk}" in
    apk) task_name=:app:assembleDevelopStandardDebug ;;
    test) task_name=:app:testDevelopStandardDebugUnitTest ;;
    *) echo "Usage: scripts/build-codex-debug.sh [apk|test]" >&2; exit 2 ;;
esac
if [[ "$task_name" == *assemble* && "$repo_dir" == *[[:space:]]* ]]; then
    echo "Android ndk-build cannot resolve this checkout path because it contains whitespace." >&2
    echo "Build the committed source in a separate Git worktree at a path without spaces." >&2
    exit 2
fi
cd -- "$repo_dir"
exec ./gradlew --no-daemon "$task_name" -Ptarget=lib/main_standard.dart \
    "-Porg.gradle.java.installations.paths=$jdk17_dir,$jdk21_dir" \
    "-POMNIBOT_LIBTALLOC_DEB=$libtalloc_file"
